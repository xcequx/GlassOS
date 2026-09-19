package com.uxspace.hub

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.uxspace.desktop.DesktopShortcut
import com.uxspace.desktop.DesktopShortcutsStore
import com.uxspace.ai.AiGatewayClient
import com.uxspace.phone.PhonePanelState
import com.uxspace.spatial.Layout
import com.uxspace.spatial.WorkspaceController
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the phone in sync with the GlassOS Hub running on a PC / server.
 *
 * Heartbeat uploads the installed-app list so the computer can assign real packages
 * to screens. The hub queues commands (apply desktop, launch, recenter); we poll
 * and execute them on the glasses workspace.
 */
class HubClient(
    private val panel: PhonePanelState,
    private val applyLayout: (Layout, pinned: Boolean) -> Unit,
) {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "glassos-hub") }
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val tick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            io.execute {
                runCatching { cycle() }.onFailure { err ->
                    Log.w(TAG, "hub tick", err)
                    main.post {
                        panel.hubConnected = false
                        panel.hubStatus = "brak huba — włącz Tailscale na telefonie"
                    }
                }
            }
            main.postDelayed(this, 2_000L)
        }
    }

    fun start() {
        if (running.getAndSet(true)) return
        main.post(tick)
    }

    fun stop() {
        running.set(false)
        main.removeCallbacks(tick)
    }

    fun uploadPreview(jpeg: ByteArray) {
        if (jpeg.isEmpty()) return
        io.execute {
            val root = panel.aiGatewayUrl.trim().trimEnd('/')
            if (!root.startsWith("http")) return@execute
            runCatching {
                val conn = URL("$root/api/preview").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 2_000
                conn.readTimeout = 4_000
                conn.setRequestProperty("Content-Type", "image/jpeg")
                conn.setRequestProperty("Content-Length", jpeg.size.toString())
                conn.outputStream.use { it.write(jpeg) }
                conn.inputStream?.close()
                conn.errorStream?.close()
                conn.disconnect()
            }.onFailure { Log.w(TAG, "preview upload", it) }
        }
    }

    fun applyDesktop(id: String) {
        io.execute {
            runCatching { post("/api/apply", JSONObject().put("id", id)) }
                .onFailure { Log.w(TAG, "apply request failed", it) }
        }
    }

    fun refreshNow() {
        io.execute { runCatching { cycle() } }
    }

    private fun cycle() {
        val candidates = linkedSetOf(
            panel.aiGatewayUrl.trim().trimEnd('/'),
            AiGatewayClient.TAILSCALE_URL,
            AiGatewayClient.LAN_URL,
        ).filter { it.startsWith("http") }
        var lastError: Exception? = null
        for (root in candidates) {
            try {
                hello(root)
                val body = get(root, "/api/state")
                val json = JSONObject(body)
                val desktops = parseDesktops(json.optJSONArray("desktops") ?: JSONArray())
                val commands = parseCommands(json.optJSONArray("pending") ?: JSONArray())
                main.post {
                    if (panel.aiGatewayUrl.trim().trimEnd('/') != root) {
                        panel.aiGatewayUrl = root
                    }
                    panel.hubDesktops = desktops
                    panel.hubConnected = true
                    panel.hubStatus = "hub OK · ${desktops.size} pulpitów"
                }
                commands.forEach { cmd ->
                    main.post { execute(cmd) }
                    runCatching { postTo(root, "/api/ack", JSONObject().put("id", cmd.id)) }
                }
                return
            } catch (err: Exception) {
                lastError = err
                Log.w(TAG, "hub miss $root: ${err.message}")
            }
        }
        main.post {
            panel.hubConnected = false
            panel.hubStatus = "brak huba — włącz Tailscale na telefonie"
        }
    }

    private fun hello(root: String) {
        val apps = JSONArray()
        panel.apps.take(400).forEach { app ->
            apps.put(
                JSONObject()
                    .put("packageName", app.packageName)
                    .put("activityName", app.activityName)
                    .put("label", app.label),
            )
        }
        val g = panel.glasses
        val payload = JSONObject()
            .put("apps", apps)
            .put(
                "glasses",
                JSONObject()
                    .put("connected", g?.connected == true)
                    .put("model", g?.modelName ?: "")
                    .put("layout", panel.layout.name)
                    .put("pinned", panel.viewModePinned)
                    .put("dof", panel.dofActive)
                    .put("workspace", WorkspaceController.isRunning),
            )
        postTo(root, "/v1/phone/hello", payload)
    }

    private fun execute(cmd: HubCommand) {
        when (cmd.type) {
            "apply_desktop" -> {
                val desk = panel.hubDesktops.firstOrNull { it.id == cmd.desktopId } ?: return
                applyDesktopLocal(desk)
            }
            "layout" -> cmd.layout?.let { applyLayoutName(it) }
            "launch" -> {
                val pkg = cmd.packageName ?: return
                val act = cmd.activityName ?: return
                WorkspaceController.launchApp(pkg, act, cmd.label ?: pkg, cmd.screenIdx)
            }
            "recenter" -> WorkspaceController.alignVerticalToHead()
            "drawer" -> WorkspaceController.setDrawerOpen(true)
            "next_screen" -> WorkspaceController.focusNextScreen()
            "click" -> WorkspaceController.click()
            "right_click" -> WorkspaceController.requestRightClick()
            "zoom_in" -> WorkspaceController.zoomBy(1.18f)
            "zoom_out" -> WorkspaceController.zoomBy(0.85f)
            "reset_look" -> WorkspaceController.resetLook()
            "panel" -> {
                val slot = cmd.screenIdx ?: 0
                val url = cmd.url.orEmpty()
                if (url.isBlank()) WorkspaceController.setScreenPanel(slot, null)
                else WorkspaceController.setScreenPanel(
                    slot,
                    WorkspaceController.ScreenPanel(cmd.label ?: "Panel", url),
                )
            }
        }
    }

    private fun applyDesktopLocal(desk: HubDesktop) {
        applyLayoutName(desk.layout)
        WorkspaceController.clearScreenPanels()
        WorkspaceController.announceInView(desk.name, 3_000L)
        val hubRoot = panel.aiGatewayUrl.trim().trimEnd('/')
        desk.screens.forEachIndexed { screenIdx, apps ->
            val web = apps.firstOrNull { it.type != "app" && it.type.isNotBlank() }
            if (web != null) {
                val url = when (web.type) {
                    "mail" -> "https://mail.google.com"
                    "browser" -> web.url.ifBlank { "https://www.google.com" }
                    "ssh" -> "$hubRoot/view/ssh?id=${web.hostId}"
                    else -> web.url
                }
                if (url.isNotBlank()) {
                    WorkspaceController.setScreenPanel(
                        screenIdx,
                        WorkspaceController.ScreenPanel(web.label.ifBlank { web.type }, url),
                    )
                }
            }
            val native = apps.filter { it.type == "app" || (it.type.isBlank() && it.packageName.isNotBlank()) }
            val pins = native.mapIndexed { i, app ->
                DesktopShortcut(
                    packageName = app.packageName,
                    activityName = app.activityName,
                    label = app.label,
                    xFraction = 0.18f + (i % 4) * 0.22f,
                    yFraction = 0.22f + (i / 4) * 0.22f,
                )
            }
            DesktopShortcutsStore.replace(screenIdx.coerceIn(0, 2), pins)
            native.forEachIndexed { i, app ->
                main.postDelayed({
                    WorkspaceController.launchApp(
                        app.packageName,
                        app.activityName,
                        app.label,
                        screenIdx,
                    )
                }, 450L * (screenIdx * 4 + i + 1))
            }
        }
        panel.statusLine = "pulpit: ${desk.name}"
    }

    private fun applyLayoutName(name: String) {
        when (name.uppercase()) {
            "FOCUS", "PINNED" -> applyLayout(Layout.SINGLE, true)
            "SINGLE" -> applyLayout(Layout.SINGLE, false)
            "TWO", "TWO_SBS" -> applyLayout(Layout.TWO_SBS, false)
            "THREE", "THREE_SBS" -> applyLayout(Layout.THREE_SBS, false)
            "CINEMA", "SINGLE_WIDE" -> applyLayout(Layout.SINGLE_WIDE, false)
            "THREE_VHV" -> applyLayout(Layout.THREE_VHV, false)
        }
    }

    private fun parseDesktops(arr: JSONArray): List<HubDesktop> {
        val out = ArrayList<HubDesktop>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val screensJson = o.optJSONArray("screens") ?: JSONArray()
            val screens = ArrayList<List<HubApp>>(screensJson.length())
            for (s in 0 until screensJson.length()) {
                val slot = screensJson.optJSONArray(s) ?: JSONArray()
                val apps = ArrayList<HubApp>(slot.length())
                for (a in 0 until slot.length()) {
                    val app = slot.getJSONObject(a)
                    apps += HubApp(
                        packageName = app.optString("packageName"),
                        activityName = app.optString("activityName"),
                        label = app.optString("label"),
                        type = app.optString("type", "app").ifBlank { "app" },
                        url = app.optString("url"),
                        hostId = app.optString("hostId"),
                    )
                }
                screens += apps
            }
            out += HubDesktop(
                id = o.optString("id"),
                name = o.optString("name"),
                layout = o.optString("layout", "TWO_SBS"),
                screens = screens,
            )
        }
        return out
    }

    private fun parseCommands(arr: JSONArray): List<HubCommand> {
        val out = ArrayList<HubCommand>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += HubCommand(
                id = o.optString("id"),
                type = o.optString("type"),
                desktopId = o.optString("desktopId").ifBlank { null },
                layout = o.optString("layout").ifBlank { null },
                packageName = o.optString("packageName").ifBlank { null },
                activityName = o.optString("activityName").ifBlank { null },
                label = o.optString("label").ifBlank { null },
                screenIdx = if (o.has("screenIdx")) o.optInt("screenIdx") else null,
                url = o.optString("url").ifBlank { null },
                hostId = o.optString("hostId").ifBlank { null },
            )
        }
        return out
    }

    private fun get(root: String, path: String): String {
        val conn = open(root, "GET", path)
        return try {
            read(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun post(path: String, json: JSONObject): String {
        val root = panel.aiGatewayUrl.trim().trimEnd('/')
        return postTo(root, path, json)
    }

    private fun postTo(root: String, path: String, json: JSONObject): String {
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        val conn = open(root, "POST", path)
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

    private fun open(root: String, method: String, path: String): HttpURLConnection {
        val conn = URL(root + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 3_000
        conn.readTimeout = 8_000
        conn.useCaches = false
        return conn
    }

    private fun read(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.readText().orEmpty()
        if (conn.responseCode !in 200..299) {
            main.post {
                panel.hubConnected = false
                panel.hubStatus = "hub HTTP ${conn.responseCode}"
            }
            error("hub HTTP ${conn.responseCode}")
        }
        return text
    }

    companion object {
        private const val TAG = "GlassOS/Hub"
    }
}
