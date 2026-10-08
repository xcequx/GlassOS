package com.uxspace.glasses

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display

/**
 * Finds the external display the VITURE glasses present themselves as.
 *
 * The glasses are a presentation-category display; UxSpace shows a `Presentation` on it so the
 * glasses show the workspace while the phone keeps its own screen. UxSpace's own virtual
 * screens (the off-screen buffers that host launched apps) are *also* presentation displays,
 * so they are filtered out by name — picking one of those was the bug that broke this before.
 */
object GlassesDisplay {

    private const val TAG = "UxSpace/Display"

    /**
     * Prefix every UxSpace-owned virtual display name shares. The per-slot UI
     * Presentations are named `uxspace-desktop` / `uxspace-desktop-screenN`, and the
     * per-window bare trusted displays are `uxspace-app-N`. We must filter out *all*
     * of them — picking one of our own as "the glasses" was the bug that put the
     * workspace Presentation on top of our own app display, recursing. The prefix
     * has shifted twice now (was `uxspace-screen`, then `uxspace-desktop`); using
     * the broader `uxspace-` removes the need for future updates if more display
     * categories are added.
     */
    private const val UXSPACE_SCREEN_PREFIX = "uxspace-"

    /**
     * Why the last [find] returned what it did — plain language, shipped to the hub so a
     * missing glasses screen says *which* of the phone's displays were seen instead.
     */
    @Volatile
    var lastReason: String = "jeszcze nie sprawdzano"
        private set

    private var lastFingerprint: String = ""

    /** The glasses' display, or `null` when they are not connected. */
    fun find(context: Context): Display? {
        val displayManager =
            context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        val all = displayManager.displays.toList()
        // find() runs on every hub heartbeat, so only log when the picture actually changes.
        val fingerprint = all.joinToString("|") { "${it.displayId}:${it.name}:${it.state}" }
        if (fingerprint != lastFingerprint) {
            lastFingerprint = fingerprint
            Log.i(TAG, "displays visible to UxSpace:")
            all.forEach {
                Log.i(
                    TAG,
                    "  id=${it.displayId} '${it.name}' " +
                        "${it.mode.physicalWidth}x${it.mode.physicalHeight} state=${it.state} " +
                        "flags=0x${it.flags.toString(16)}",
                )
            }
        }

        val ours = { d: Display -> d.name.startsWith(UXSPACE_SCREEN_PREFIX) }
        val glasses = displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { !ours(it) && it.state != Display.STATE_OFF }
        if (glasses != null) {
            val reason = "ekran prezentacyjny '${glasses.name}' (id=${glasses.displayId})"
            if (reason != lastReason) Log.i(TAG, "glasses display: $reason")
            lastReason = reason
            return glasses
        }

        // No presentation-category display. On Samsung this is what DeX / screen mirroring
        // looks like: the external panel exists but the system owns it. Presenting on it
        // still works often enough to be worth the attempt — and if it doesn't, the reason
        // recorded here is the answer the user needs.
        val external = all.firstOrNull {
            it.displayId != Display.DEFAULT_DISPLAY &&
                !ours(it) &&
                it.state != Display.STATE_OFF
        }
        val reason = when {
            external != null ->
                "awaryjnie: ekran '${external.name}' (id=${external.displayId}) bez flagi " +
                    "PRESENTATION — prawdopodobnie DeX / dublowanie ekranu"
            all.none { it.displayId != Display.DEFAULT_DISPLAY && !ours(it) } ->
                "telefon widzi tylko własny ekran — brak obrazu z USB-C (DisplayPort)"
            else ->
                "zewnętrzny ekran jest wyłączony (state=OFF)"
        }
        if (reason != lastReason) {
            Log.i(
                TAG,
                "glasses display: ${external?.let { "fallback id=${it.displayId}" } ?: "none"} — $reason",
            )
        }
        lastReason = reason
        return external
    }
}
