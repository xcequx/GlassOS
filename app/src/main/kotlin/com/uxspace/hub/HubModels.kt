package com.uxspace.hub

data class HubApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val type: String = "app",
    val url: String = "",
    val hostId: String = "",
)

data class HubDesktop(
    val id: String,
    val name: String,
    val layout: String,
    val screens: List<List<HubApp>>,
)

/**
 * A machine registered on the hub. [kind] picks the client on the phone:
 * `rdp` (Windows App), `moonlight` (Sunshine host), `vnc`, `ssh` (hub console page).
 * [host] is the Tailscale name or IP the *phone* connects to — the hub never relays video.
 */
data class HubComputer(
    val id: String,
    val name: String,
    val host: String,
    val user: String = "",
    val port: Int = 0,
    val kind: String = "ssh",
    /** Moonlight: paired PC UUID (optional — without it Moonlight shows its PC list). */
    val uuid: String = "",
    /** Moonlight: app to stream, default "Desktop". */
    val app: String = "",
    val appId: String = "",
    /** Requested remote resolution; 0 = slot native (1920×1080). */
    val width: Int = 0,
    val height: Int = 0,
    val online: Boolean = false,
)

data class HubCommand(
    val id: String,
    val type: String,
    val desktopId: String? = null,
    val layout: String? = null,
    val packageName: String? = null,
    val activityName: String? = null,
    val label: String? = null,
    val screenIdx: Int? = null,
    /** Numeric argument for hardware commands, e.g. brightness 0–8. */
    val level: Int? = null,
    /** Pointer delta in thousandths of the screen, for the hub's mouse control. */
    val dx: Int? = null,
    val dy: Int? = null,
    val url: String? = null,
    val hostId: String? = null,
)
