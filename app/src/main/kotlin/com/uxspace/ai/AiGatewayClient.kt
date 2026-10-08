package com.uxspace.ai

import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Talks to the GlassOS AI gateway on the PC (or VisualClaw on the same ports).
 *
 *   GET  /health
 *   GET  /v1/live/status
 *   POST /v1/video/frame     { session_id, image, ts }
 *   POST /v1/glasses/chat    { session_id, text }
 */
class AiGatewayClient {

    @Volatile var baseUrl: String = DEFAULT_URL

    val sessionId: String = "glassos-" + java.util.UUID.randomUUID().toString().take(8)

    fun health(): Result<String> = runCatching {
        val body = get("/health")
        val json = JSONObject(body)
        if (json.optBoolean("ok", false) || json.optString("status") == "ok") {
            json.optString("model", json.optString("status", "ok"))
        } else {
            body.take(120)
        }
    }

    fun liveStatus(): Result<JSONObject> = runCatching { JSONObject(get("/v1/live/status")) }

    fun sendFrame(jpeg: ByteArray): Result<Unit> = runCatching {
        val payload = JSONObject()
            .put("session_id", sessionId)
            .put("ts", System.currentTimeMillis() / 1000.0)
            .put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP))
        post("/v1/video/frame", payload)
        Unit
    }

    fun chat(prompt: String): Result<String> = runCatching {
        val payload = JSONObject()
            .put("session_id", sessionId)
            .put("text", prompt)
        val body = post("/api/ai/chat", payload)
        val json = JSONObject(body)
        json.optString("text")
            .ifBlank { json.optString("reply") }
            .ifBlank { json.optString("answer") }
            .ifBlank { body }
    }

    private fun get(path: String): String {
        val conn = open("GET", path)
        return try {
            read(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun post(path: String, json: JSONObject): String {
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        val conn = open("POST", path)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Content-Length", bytes.size.toString())
        return try {
            BufferedOutputStream(conn.outputStream).use { it.write(bytes) }
            read(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, path: String): HttpURLConnection {
        val root = baseUrl.trim().trimEnd('/')
        val url = URL(root + path)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 4_000
        conn.readTimeout = 60_000
        conn.useCaches = false
        return conn
    }

    private fun read(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val bytes = stream?.use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toByteArray()
        } ?: ByteArray(0)
        val text = String(bytes, StandardCharsets.UTF_8)
        if (conn.responseCode !in 200..299) {
            Log.w(TAG, "${conn.requestMethod} ${conn.url} -> ${conn.responseCode} $text")
            error("gateway HTTP ${conn.responseCode}: ${text.take(200)}")
        }
        return text
    }

    companion object {
        private const val TAG = "GlassOS/AI"
        const val LAN_URL = "http://192.168.1.112:30100"
        const val TAILSCALE_URL = "https://desktop-sotkr5k.tail37a666.ts.net"

        /**
         * Same hub over the tailnet, but by raw IP and plain HTTP. `tailscale serve`
         * needs MagicDNS + the HTTPS proxy to be healthy on both ends; this path only
         * needs the tunnel itself, so it survives the DNS half being down.
         */
        const val TAILSCALE_IP_URL = "http://100.97.64.122:30100"
        const val DEFAULT_URL = TAILSCALE_URL
        const val PREF = "glassos_ai"
        const val PREF_URL = "gateway_url"
    }
}
