package com.uxspace.glasses

/**
 * Plain-language state of the glasses link, for the phone panel and the hub's checklist.
 *
 * Head tracking has many ways to be "off" — no USB device, permission refused, SDK missing,
 * a tracker that connects but never emits a pose — and they need different fixes. The
 * tracking code writes what happened here; the app reads it and ships it to the hub.
 */
object GlassesStatus {

    /** One line about head tracking, e.g. "3DoF · Carina VIO" or "brak zgody na USB". */
    @Volatile
    var tracking: String = "nie uruchomiono"

    /** Last tracking failure, kept after recovery so a flaky link is still visible. */
    @Volatile
    var lastError: String = ""

    fun ok(text: String) {
        tracking = text
    }

    fun fail(text: String) {
        tracking = text
        lastError = text
    }
}
