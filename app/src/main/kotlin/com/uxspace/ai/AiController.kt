package com.uxspace.ai

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.tts.TextToSpeech
import android.view.Surface
import android.view.TextureView
import java.util.Locale
import com.uxspace.glasses.LumaCamera
import com.uxspace.phone.PhonePanelState
import com.uxspace.spatial.WorkspaceController
import java.util.concurrent.Executors

/**
 * Wires Luma Pro / phone camera → JPEG frames → PC gateway → overlay on the glasses.
 */
class AiController(
    context: Context,
    private val panel: PhonePanelState,
) {
    private val app = context.applicationContext
    private val capture = CameraCapture(app)
    private val client = AiGatewayClient()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "glassos-ai") }
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences(AiGatewayClient.PREF, Context.MODE_PRIVATE)

    private var preview: TextureView? = null
    private var lastHash = 0L
    private var lastSentAt = 0L
    private var tts: TextToSpeech? = null

    init {
        val saved = prefs.getString(AiGatewayClient.PREF_URL, null).orEmpty().trim()
        panel.aiGatewayUrl = when {
            saved.isBlank() -> AiGatewayClient.DEFAULT_URL
            saved.contains("192.168.1.10") -> AiGatewayClient.DEFAULT_URL
            else -> saved
        }
        client.baseUrl = panel.aiGatewayUrl
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pl", "PL")
            }
        }
        capture.onJpeg = fun(jpeg: ByteArray) {
            if (!panel.aiLive) return
            val now = System.currentTimeMillis()
            if (now - lastSentAt < 450) return
            val hash = runCatching { FrameHasher.dHash(jpeg) }.getOrDefault(0L)
            if (!FrameHasher.changed(lastHash, hash)) return
            lastHash = hash
            lastSentAt = now
            io.execute {
                client.sendFrame(jpeg)
                    .onSuccess { main.post { panel.aiFramesSent += 1 } }
                    .onFailure { Log.w(TAG, "frame upload failed", it) }
            }
        }
        refreshSources()
    }

    fun refreshSources() {
        val src = capture.sources()
        panel.cameraSources = src.map { it.label }
        val cam = LumaCamera.find(app)
        panel.cameraUsb = cam != null
        panel.cameraLabel = LumaCamera.describe(cam)
        if (panel.selectedCamera.isBlank()) {
            panel.selectedCamera = capture.preferred()?.label ?: src.firstOrNull()?.label.orEmpty()
        }
    }

    fun setGatewayUrl(url: String) {
        panel.aiGatewayUrl = url
        client.baseUrl = url.trim()
        prefs.edit().putString(AiGatewayClient.PREF_URL, client.baseUrl).apply()
    }

    fun testGateway() {
        panel.aiGatewayStatus = "sprawdzam…"
        val url = panel.aiGatewayUrl
        client.baseUrl = url.trim()
        io.execute {
            val result = client.health()
            main.post {
                panel.aiGatewayStatus = result.fold(
                    onSuccess = { "OK · $it" },
                    onFailure = { "błąd: ${it.message?.take(80)}" },
                )
            }
        }
    }

    fun selectCamera(label: String) {
        panel.selectedCamera = label
        val view = preview ?: return
        if (view.isAvailable) startOn(view)
    }

    fun attachPreview(view: TextureView) {
        preview = view
        if (view.isAvailable) {
            startOn(view)
        } else {
            view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    startOn(view)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    capture.stop()
                    panel.cameraStreaming = false
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }
    }

    fun detachPreview() {
        capture.stop()
        preview = null
        panel.cameraStreaming = false
        panel.aiLive = false
    }

    fun toggleLive() {
        panel.aiLive = !panel.aiLive
        if (panel.aiLive) {
            panel.aiGatewayStatus = "live → ${client.baseUrl}"
        }
    }

    fun ask(prompt: String) {
        if (panel.aiBusy) return
        panel.aiBusy = true
        panel.aiAnswer = ""
        val jpeg = capture.latestJpeg()
        io.execute {
            if (jpeg != null) {
                client.sendFrame(jpeg)
                    .onSuccess { main.post { panel.aiFramesSent += 1 } }
            }
            val result = client.chat(prompt)
            main.post {
                panel.aiBusy = false
                result.fold(
                    onSuccess = { text ->
                        panel.aiAnswer = text
                        WorkspaceController.announceInView(text.take(180), 10_000L)
                        runCatching {
                            tts?.speak(text.take(400), TextToSpeech.QUEUE_FLUSH, null, "glassos")
                        }
                    },
                    onFailure = { err ->
                        panel.aiAnswer = "Nie udało się: ${err.message}"
                        panel.aiGatewayStatus = panel.aiAnswer
                    },
                )
            }
        }
    }

    fun requestCameraUsbPermission() {
        LumaCamera.requestPermission(app) { granted ->
            main.post {
                panel.cameraLabel = if (granted) {
                    LumaCamera.describe(LumaCamera.find(app)) + " · USB OK"
                } else {
                    LumaCamera.describe(LumaCamera.find(app)) + " · brak zgody USB"
                }
                refreshSources()
            }
        }
    }

    private fun startOn(view: TextureView) {
        refreshSources()
        val source = capture.sources().firstOrNull { it.label == panel.selectedCamera }
            ?: capture.preferred()
            ?: return
        panel.selectedCamera = source.label
        panel.cameraSource = source.label
        val st = view.surfaceTexture ?: return
        st.setDefaultBufferSize(1280, 720)
        capture.start(source, Surface(st), view)
        panel.cameraStreaming = true
    }

    fun release() {
        detachPreview()
        runCatching { tts?.stop(); tts?.shutdown() }
        io.shutdownNow()
    }

    companion object {
        private const val TAG = "GlassOS/AI"
    }
}
