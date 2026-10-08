package com.uxspace.hub

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.uxspace.desktop.DesktopShortcut
import com.uxspace.diag.Diagnostics
import com.uxspace.glasses.GlassesDevice
import com.uxspace.glasses.GlassesDisplay
import com.uxspace.glasses.GlassesStatus
import com.uxspace.desktop.DesktopShortcutsStore
import com.uxspace.ai.AiGatewayClient
import com.uxspace.phone.PhonePanelState
import com.uxspace.privileged.PrivilegedService
import com.uxspace.spatial.Layout
import com.uxspace.spatial.WorkspaceController
import com.uxspace.spatial.WorkspaceRenderer
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
    private val context: Context,
    private val panel: PhonePanelState,
    private val applyLayout: (Layout, pinned: Boolean) -> Unit,
) {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "glassos-hub") }
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val tick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            refreshHealth()
            watchAutostart()
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
        loadCachedState()
        main.post(tick)
    }

    // ---- autostart: last-chosen desktop comes up by itself when the glasses connect ----

    private var workspaceWasRunning = false
    private var autostartAttempt = 0

    /**
     * Rising edge of the workspace = glasses just came up. The desktop the hub marked
     * as autostart is applied a few seconds later (the ADB helper needs a moment to
     * be READY for trusted displays); if the helper is still warming up we retry a
     * couple of times, then give up quietly — the hub button still works.
     */
    private fun watchAutostart() {
        val up = WorkspaceController.isRunning
        if (up && !workspaceWasRunning) {
            autostartAttempt = 0
            scheduleAutostart(AUTOSTART_FIRST_DELAY_MS)
        }
        workspaceWasRunning = up
    }

    private fun scheduleAutostart(delayMs: Long) {
        val id = panel.hubAutostartDesktop
        if (id.isBlank()) return
        main.postDelayed({
            if (!WorkspaceController.isRunning) return@postDelayed
            val desk = panel.hubDesktops.firstOrNull { it.id == id } ?: return@postDelayed
            if (!WorkspaceController.privilegedReady && autostartAttempt < AUTOSTART_MAX_ATTEMPTS) {
                autostartAttempt++
                Diagnostics.i("GlassOS/Hub", "autostart: helper ADB jeszcze nie gotowy, próba $autostartAttempt")
                scheduleAutostart(AUTOSTART_RETRY_DELAY_MS)
                return@postDelayed
            }
            Diagnostics.i("GlassOS/Hub", "autostart: pulpit ${desk.name}")
            applyDesktopLocal(desk)
        }, delayMs)
    }

    // ---- offline cache: the last hub state, so autostart works with no hub in reach ----

    private fun loadCachedState() {
        val cached = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
            .getString(CACHE_KEY_STATE, null) ?: return
        runCatching {
            val json = JSONObject(cached)
            panel.hubDesktops = parseDesktops(json.optJSONArray("desktops") ?: JSONArray())
            panel.hubComputers = parseComputers(json.optJSONArray("computers") ?: JSONArray())
            panel.hubAutostartDesktop = json.optJSONObject("settings")
                ?.optString("autostart_desktop").orEmpty()
        }.onFailure { Log.w(TAG, "cached hub state unreadable", it) }
    }

    private fun cacheState(body: String) {
        runCatching {
            // Strip the bulky, volatile parts; desktops + computers + settings are what
            // the phone needs when the hub is unreachable.
            val json = JSONObject(body)
            val slim = JSONObject()
                .put("desktops", json.optJSONArray("desktops") ?: JSONArray())
                .put("computers", json.optJSONArray("computers") ?: JSONArray())
                .put("settings", json.optJSONObject("settings") ?: JSONObject())
            context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(CACHE_KEY_STATE, slim.toString()).apply()
        }
    }

    /** Mirror the health lines onto the phone panel — same facts the hub checklist shows. */
    private fun refreshHealth() {
        val up = WorkspaceController.isRunning
        panel.workspaceOn = up
        panel.workspaceHint = when {
            up -> "działa w okularach"
            Diagnostics.workspaceError.isNotBlank() -> Diagnostics.workspaceError
            else -> GlassesDisplay.lastReason
        }
        panel.trackingHint = GlassesStatus.tracking
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
            AiGatewayClient.TAILSCALE_IP_URL,
            AiGatewayClient.LAN_URL,
        ).filter { it.startsWith("http") }
        var lastError: Exception? = null
        // Built once: the log buffer is drained here, so a candidate that fails must not
        // take this cycle's log lines down with it.
        val beat = heartbeat()
        for (root in candidates) {
            try {
                hello(root, beat)
                val body = get(root, "/api/state")
                val json = JSONObject(body)
                val desktops = parseDesktops(json.optJSONArray("desktops") ?: JSONArray())
                val computers = parseComputers(json.optJSONArray("computers") ?: JSONArray())
                val autostart = json.optJSONObject("settings")?.optString("autostart_desktop").orEmpty()
                val commands = parseCommands(json.optJSONArray("pending") ?: JSONArray())
                cacheState(body)
                main.post {
                    if (panel.aiGatewayUrl.trim().trimEnd('/') != root) {
                        panel.aiGatewayUrl = root
                    }
                    panel.hubDesktops = desktops
                    panel.hubComputers = computers
                    panel.hubAutostartDesktop = autostart
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
        val why = lastError?.message?.take(80).orEmpty()
        main.post {
            panel.hubConnected = false
            panel.hubStatus = "brak huba (${candidates.size} adresy) ${why}".trim()
        }
    }

    /** Hash of the app list we last uploaded, so a 2 s heartbeat isn't 400 apps every time. */
    private var sentAppsHash = 0

    private fun heartbeat(): JSONObject {
        val g = panel.glasses
        val payload = JSONObject()
            .put(
                "glasses",
                JSONObject()
                    .put("connected", g?.connected == true)
                    .put("usb", g?.usbConnected == true)
                    .put("display", g?.displayConnected == true)
                    .put("model", g?.modelName ?: "")
                    .put("mode", g?.displayLabel ?: "")
                    .put("layout", panel.layout.name)
                    .put("pinned", panel.viewModePinned)
                    .put("dof", panel.dofActive)
                    .put("tracking", panel.trackingLabel)
                    .put("brightness", GlassesDevice.brightness().takeIf { it >= 0 } ?: (g?.brightness ?: -1))
                    .put("workspace", WorkspaceController.isRunning),
            )
            .put("diag", diagnostics())
            .put("logs", Diagnostics.drain())
        val appsHash = appsHash()
        if (appsHash != sentAppsHash && panel.apps.isNotEmpty()) {
            val apps = JSONArray()
            panel.apps.take(400).forEach { app ->
                apps.put(
                    JSONObject()
                        .put("packageName", app.packageName)
                        .put("activityName", app.activityName)
                        .put("label", app.label),
                )
            }
            payload.put("apps", apps)
        }
        return payload
    }

    private fun appsHash(): Int =
        panel.apps.size * 31 + panel.apps.firstOrNull()?.packageName.hashCode()

    private fun hello(root: String, payload: JSONObject) {
        val answer = postTo(root, "/v1/phone/hello", payload)
        if (payload.has("apps")) sentAppsHash = appsHash()
        // The hub asks for a fresh list when it has none (e.g. after a hub restart).
        if (runCatching { JSONObject(answer).optBoolean("wantApps") }.getOrDefault(false)) {
            sentAppsHash = 0
        }
    }

    private fun diagnostics(): JSONObject {
        val json = runCatching {
            Diagnostics.snapshot(context, GlassesDisplay.find(context))
        }.getOrElse { JSONObject().put("diagError", it.message.orEmpty()) }
        return runCatching {
            json.put("displayReason", GlassesDisplay.lastReason)
                .put("hubUrl", panel.aiGatewayUrl)
        }.getOrDefault(json)
    }

    private fun execute(cmd: HubCommand) {
        // Every command from the browser lands in the remote log — otherwise "I clicked it
        // and nothing happened" is unanswerable from the PC side.
        Diagnostics.i("GlassOS/Hub", "komenda z huba: ${cmd.type}")
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
            // One computer onto one screen, without rebuilding the whole desktop.
            "remote" -> {
                val comp = panel.hubComputers.firstOrNull { it.id == cmd.hostId } ?: return
                launchComputer(comp, cmd.screenIdx ?: 0)
            }
            "open_url" -> {
                val url = cmd.url ?: return
                launchWeb(url, cmd.label ?: url, cmd.screenIdx ?: 0, monitor = false)
            }
            "recenter" -> WorkspaceController.alignVerticalToHead()
            "drawer" -> WorkspaceController.setDrawerOpen(true)
            "next_screen" -> WorkspaceController.focusNextScreen()
            "click" -> WorkspaceController.click()
            // Mouse driven from the browser: the hub sends deltas as thousandths of the
            // screen, so one gesture means the same thing on any monitor.
            "cursor" -> WorkspaceController.moveCursor(
                (cmd.dx ?: 0) / 1000f,
                (cmd.dy ?: 0) / 1000f,
            )
            "drag_start" -> WorkspaceController.longPress()
            "drag_end" -> WorkspaceController.endDrag()
            "right_click" -> WorkspaceController.requestRightClick()
            "zoom_in" -> WorkspaceController.zoomBy(1.18f)
            "zoom_out" -> WorkspaceController.zoomBy(0.85f)
            "reset_look" -> WorkspaceController.resetLook()
            "fit_screens" -> WorkspaceController.fitAllScreens()
            "unpin" -> WorkspaceController.setViewMode(
                WorkspaceRenderer.ViewMode.FREE,
                byUser = true,
            )
            "pin" -> WorkspaceController.setViewMode(
                WorkspaceRenderer.ViewMode.PINNED,
                byUser = true,
            )
            // Hardware + recovery, so the whole session can be driven from the browser
            // without picking the phone up.
            "brightness_up" -> stepBrightness(+1)
            "brightness_down" -> stepBrightness(-1)
            "brightness" -> cmd.level?.let { GlassesDevice.setBrightness(it) }
            "stereo_toggle" -> WorkspaceController.setStereo?.invoke(!panel.stereo3d)
            "taskbar_toggle" -> WorkspaceController.setTaskbarVisible?.invoke(!panel.taskbarVisible)
                .also { panel.taskbarVisible = !panel.taskbarVisible }
            "film_toggle" -> {
                val on = panel.filmPercent < 50f
                GlassesDevice.setFilm(on)
                panel.filmPercent = if (on) 100f else 0f
            }
            "display_1080p" -> GlassesDevice.setMode1080p60()
            "retry_tracking" -> WorkspaceController.retryHeadTracking?.invoke()
            "restart_workspace" -> WorkspaceController.restartGlasses?.invoke()
            "refresh_apps" -> sentAppsHash = 0
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

    private fun stepBrightness(delta: Int) {
        // Ask the SDK, not the phone-panel snapshot: the snapshot refreshes on its own
        // schedule, so two quick steps from the hub both read the same stale value and
        // the second one did nothing.
        val now = GlassesDevice.brightness().takeIf { it in 0..8 } ?: (panel.glasses?.brightness ?: 4)
        val next = (now + delta).coerceIn(0, 8)
        val rc = GlassesDevice.setBrightness(next)
        panel.brightness = next
        Diagnostics.i("GlassOS/Hub", "jasność $now -> $next (rc=$rc)")
    }

    private fun applyDesktopLocal(desk: HubDesktop) {
        applyLayoutName(desk.layout)
        WorkspaceController.clearScreenPanels()
        WorkspaceController.announceInView(desk.name, 3_000L)
        val hubRoot = panel.aiGatewayUrl.trim().trimEnd('/')
        desk.screens.forEachIndexed { screenIdx, apps ->
            // Web + remote tiles: each one becomes a window on the screen, opened
            // from an intent. A remote computer is a "monitor" (full slot, 1080p);
            // a page is a normal window the user can maximise. Launches are spaced
            // out the same way native apps are, so the helper is never asked to
            // start several activities in the same instant.
            var stagger = 0
            apps.filter { it.type != "app" && it.type.isNotBlank() }.forEach { tile ->
                val delay = 450L * (screenIdx * 4 + stagger + 1)
                when (tile.type) {
                    "remote" -> {
                        val comp = panel.hubComputers.firstOrNull { it.id == tile.hostId }
                        if (comp == null) {
                            Diagnostics.w("GlassOS/Hub", "kafel komputera bez wpisu na hubie (${tile.label})")
                        } else {
                            WorkspaceController.setScreenPanel(
                                screenIdx,
                                WorkspaceController.ScreenPanel(comp.name, comp.host),
                            )
                            main.postDelayed({ launchComputer(comp, screenIdx) }, delay)
                            stagger++
                        }
                    }
                    else -> {
                        val url = when (tile.type) {
                            "mail" -> "https://mail.google.com"
                            "browser" -> tile.url.ifBlank { "https://www.google.com" }
                            "ssh" -> "$hubRoot/view/ssh?id=${tile.hostId}"
                            else -> tile.url
                        }
                        if (url.isNotBlank()) {
                            val label = tile.label.ifBlank { tile.type }
                            WorkspaceController.setScreenPanel(
                                screenIdx,
                                WorkspaceController.ScreenPanel(label, url),
                            )
                            val monitor = tile.type == "ssh"
                            main.postDelayed({ launchWeb(url, label, screenIdx, monitor) }, delay)
                            stagger++
                        }
                    }
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
                }, 450L * (screenIdx * 4 + stagger + i + 1))
            }
        }
        panel.statusLine = "pulpit: ${desk.name}"
    }

    /** Open one registered computer as a full-slot monitor on [screenIdx]. */
    private fun launchComputer(comp: HubComputer, screenIdx: Int) {
        val hubRoot = panel.aiGatewayUrl.trim().trimEnd('/')
        val missing = RemoteLaunch.missingClient(context, comp)
        if (missing != null) {
            Diagnostics.w("GlassOS/Hub", "${comp.name}: $missing")
            WorkspaceController.announceInView(missing, 5_000L)
            return
        }
        val spec = RemoteLaunch.forComputer(context, comp, hubRoot)
        if (spec == null) {
            Diagnostics.w("GlassOS/Hub", "${comp.name}: nie wiem, jak otworzyć rodzaj '${comp.kind}'")
            return
        }
        if (!WorkspaceController.privilegedReady) {
            WorkspaceController.announceInView("Komputer ${comp.name}: potrzebny helper ADB", 4_000L)
        }
        Diagnostics.i("GlassOS/Hub", "komputer ${comp.name} (${comp.kind}) → ekran ${screenIdx + 1}")
        WorkspaceController.launchApp(
            spec.packageName, spec.activityName, spec.label, screenIdx, spec.intent, spec.monitor,
        )
    }

    /** Open a web page in the default browser on [screenIdx]. */
    private fun launchWeb(url: String, label: String, screenIdx: Int, monitor: Boolean) {
        val spec = RemoteLaunch.web(context, url, label, monitor)
        if (spec == null) {
            Diagnostics.w("GlassOS/Hub", "brak przeglądarki dla $url")
            return
        }
        WorkspaceController.launchApp(
            spec.packageName, spec.activityName, spec.label, screenIdx, spec.intent, spec.monitor,
        )
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

    private fun parseComputers(arr: JSONArray): List<HubComputer> {
        val out = ArrayList<HubComputer>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out += HubComputer(
                id = o.optString("id"),
                name = o.optString("name").ifBlank { o.optString("host") },
                host = o.optString("host"),
                user = o.optString("user"),
                port = o.optInt("port", 0),
                kind = o.optString("kind", "ssh").ifBlank { "ssh" },
                uuid = o.optString("uuid"),
                app = o.optString("app"),
                appId = o.optString("appId"),
                width = o.optInt("width", 0),
                height = o.optInt("height", 0),
                online = o.optBoolean("online", false),
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
                level = if (o.has("level")) o.optInt("level") else null,
                dx = if (o.has("dx")) o.optInt("dx") else null,
                dy = if (o.has("dy")) o.optInt("dy") else null,
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
        private const val CACHE_PREFS = "glassos_hub_cache"
        private const val CACHE_KEY_STATE = "state"
        private const val AUTOSTART_FIRST_DELAY_MS = 4_000L
        private const val AUTOSTART_RETRY_DELAY_MS = 5_000L
        private const val AUTOSTART_MAX_ATTEMPTS = 4
    }
}
