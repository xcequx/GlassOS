package com.uxspace.hub

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.uxspace.spatial.WorkspaceController

/**
 * Turns a hub "computer" (RDP / Moonlight / VNC / SSH) or a web address into the
 * concrete thing the workspace can launch onto a glasses screen: which package, which
 * activity, with what intent, and whether it should open as a full-slot "monitor".
 *
 * Nothing here talks to the network — the hub only hands over the bookmark; the phone
 * opens the client app and the client connects straight to the target machine over
 * Tailscale. That keeps the hub out of the video path.
 */
object RemoteLaunch {

    data class Spec(
        val packageName: String,
        val activityName: String,
        val label: String,
        val intent: WorkspaceController.LaunchIntent,
        /** Open straight into FULLSCREEN on a 1920×1080 display. */
        val monitor: Boolean,
    )

    /** Microsoft "Windows App" (new id) and the older "Remote Desktop" id. */
    private val RDP_PACKAGES = listOf("com.microsoft.rdc.androidx", "com.microsoft.rdc.android")

    /** Moonlight game-streaming client — the Sunshine counterpart. */
    private const val MOONLIGHT = "com.limelight"
    private const val MOONLIGHT_TRAMPOLINE = "com.limelight.ShortcutTrampoline"

    /** Browsers to try, in order, when the system has no default for https links. */
    private val BROWSERS = listOf(
        "com.android.chrome",
        "com.brave.browser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
    )

    /** What is missing on the phone for this computer, or null when it can be opened. */
    fun missingClient(context: Context, comp: HubComputer): String? = when (comp.kind.lowercase()) {
        "rdp" -> if (RDP_PACKAGES.any { installed(context.packageManager, it) }) null
        else "Zainstaluj „Windows App” (Microsoft Remote Desktop) na telefonie"
        "moonlight" -> if (installed(context.packageManager, MOONLIGHT)) null
        else "Zainstaluj Moonlight na telefonie"
        "vnc" -> if (resolveView(context.packageManager, "vnc://${comp.host}") != null) null
        else "Zainstaluj klienta VNC (np. AVNC albo bVNC) na telefonie"
        else -> null
    }

    fun forComputer(context: Context, comp: HubComputer, hubRoot: String): Spec? =
        when (comp.kind.lowercase()) {
            "rdp" -> rdp(context, comp)
            "moonlight" -> moonlight(context, comp)
            "vnc" -> vnc(context, comp)
            "ssh" -> web(context, "$hubRoot/view/ssh?id=${comp.id}", comp.name, monitor = true)
            else -> null
        }

    /**
     * Windows App understands the Remote Desktop URI scheme: `rdp://` followed by
     * `.rdp`-file attributes, names percent-encoded, `s:` strings and `i:` integers.
     * `screen mode id=2` asks for full screen; the desktop size matches the slot so
     * Windows draws at the glasses' native 1080p.
     */
    private fun rdp(context: Context, comp: HubComputer): Spec? {
        val pm = context.packageManager
        val pkg = RDP_PACKAGES.firstOrNull { installed(pm, it) } ?: return null
        val host = if (comp.port > 0 && comp.port != 3389) "${comp.host}:${comp.port}" else comp.host
        val w = comp.width.takeIf { it > 0 } ?: 1920
        val h = comp.height.takeIf { it > 0 } ?: 1080
        val uri = buildString {
            append("rdp://full%20address=s:").append(Uri.encode(host, ":"))
            if (comp.user.isNotBlank()) append("&username=s:").append(Uri.encode(comp.user, "@\\"))
            append("&screen%20mode%20id=i:2")
            append("&desktopwidth=i:").append(w)
            append("&desktopheight=i:").append(h)
            append("&session%20bpp=i:32")
        }
        return Spec(
            packageName = pkg,
            activityName = "",
            label = comp.name,
            intent = WorkspaceController.LaunchIntent(dataUri = uri),
            monitor = true,
        )
    }

    /**
     * Moonlight exposes a shortcut trampoline (what its home-screen shortcuts use):
     * given the paired PC's UUID it jumps straight into the stream of one app —
     * "Desktop" on a Sunshine host. Without a UUID we open Moonlight's PC list in
     * the monitor window and the user taps the machine once.
     */
    private fun moonlight(context: Context, comp: HubComputer): Spec? {
        val pm = context.packageManager
        if (!installed(pm, MOONLIGHT)) return null
        if (comp.uuid.isNotBlank()) {
            val extras = mutableListOf(
                "UUID=${comp.uuid}",
                "Name=${comp.name}",
                "AppName=${comp.app.ifBlank { "Desktop" }}",
            )
            comp.appId.takeIf { it.isNotBlank() }?.let { extras += "AppId=$it" }
            return Spec(
                packageName = MOONLIGHT,
                activityName = MOONLIGHT_TRAMPOLINE,
                label = comp.name,
                intent = WorkspaceController.LaunchIntent(
                    action = "android.intent.action.MAIN",
                    extras = extras,
                ),
                monitor = true,
            )
        }
        val launcher = launcherActivity(pm, MOONLIGHT) ?: return null
        return Spec(
            packageName = MOONLIGHT,
            activityName = launcher,
            label = comp.name,
            intent = WorkspaceController.LaunchIntent(action = "android.intent.action.MAIN"),
            monitor = true,
        )
    }

    /** Any installed viewer that registers the `vnc://` scheme (AVNC, bVNC, RealVNC…). */
    private fun vnc(context: Context, comp: HubComputer): Spec? {
        val port = comp.port.takeIf { it > 0 } ?: 5900
        val uri = buildString {
            append("vnc://")
            if (comp.user.isNotBlank()) append(Uri.encode(comp.user)).append('@')
            append(comp.host).append(':').append(port)
        }
        val (pkg, act) = resolveView(context.packageManager, uri) ?: return null
        return Spec(pkg, act, comp.name, WorkspaceController.LaunchIntent(dataUri = uri), monitor = true)
    }

    /** A web page in the default browser (hub SSH console, mail, any site). */
    fun web(context: Context, url: String, label: String, monitor: Boolean = false): Spec? {
        val pm = context.packageManager
        val resolved = resolveView(pm, url)
            ?: BROWSERS.firstOrNull { installed(pm, it) }?.let { pkg -> pkg to "" }
            ?: return null
        return Spec(
            packageName = resolved.first,
            activityName = resolved.second,
            label = label,
            intent = WorkspaceController.LaunchIntent(dataUri = url),
            monitor = monitor,
        )
    }

    private fun installed(pm: PackageManager, pkg: String): Boolean =
        runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess

    private fun launcherActivity(pm: PackageManager, pkg: String): String? =
        pm.getLaunchIntentForPackage(pkg)?.component?.className

    /**
     * Package + activity the system would open for a VIEW of [uri], or null when
     * nothing handles it (or only the chooser would — that cannot run on a bare
     * virtual display).
     */
    private fun resolveView(pm: PackageManager, uri: String): Pair<String, String>? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        val info = runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) }
            .getOrNull() ?: return null
        val ai = info.activityInfo ?: return null
        if (ai.packageName == "android" || ai.name.contains("ResolverActivity")) return null
        return ai.packageName to ai.name
    }
}
