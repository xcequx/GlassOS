package com.uxspace.diag

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.view.Display
import com.uxspace.BuildConfig
import com.uxspace.glasses.GlassesStatus
import com.uxspace.glasses.GlassesUsb
import com.uxspace.glasses.NativeGlasses
import com.uxspace.privileged.PrivilegedService
import com.uxspace.spatial.WorkspaceController
import org.json.JSONArray
import org.json.JSONObject

/**
 * What GlassOS knows about its own health — shipped to the hub on every heartbeat.
 *
 * Without a USB cable there is no `adb logcat`, so "it connects but nothing works" used to be
 * a dead end. The phone now reports the whole chain (displays, USB, native SDK, workspace,
 * tracking, ADB helper) plus its own recent log lines; the hub renders that as a checklist
 * with the fix for each broken link.
 *
 * Everything here is best-effort: a failing probe records its error instead of throwing, so
 * diagnostics can never be the thing that breaks the app.
 */
object Diagnostics {

    private const val TAG = "GlassOS/Diag"
    private const val MAX_LINES = 200

    data class Line(val ts: Long, val level: String, val tag: String, val text: String)

    private val lines = ArrayDeque<Line>()

    /** Last failure that stopped the workspace from showing on the glasses, if any. */
    @Volatile
    var workspaceError: String = ""

    /** Which display GlassOS picked for the glasses, and how. */
    @Volatile
    var displayPick: String = ""

    fun i(tag: String, text: String) = add("I", tag, text).also { Log.i(tag, text) }

    fun w(tag: String, text: String) = add("W", tag, text).also { Log.w(tag, text) }

    fun e(tag: String, text: String, err: Throwable? = null) {
        add("E", tag, if (err == null) text else "$text: ${err.message}")
        Log.e(tag, text, err)
    }

    private fun add(level: String, tag: String, text: String) {
        synchronized(lines) {
            lines.addLast(Line(System.currentTimeMillis(), level, tag.take(40), text.take(400)))
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    /** Take the buffered lines for upload; the buffer is emptied so nothing is sent twice. */
    fun drain(max: Int = 60): JSONArray {
        val out = JSONArray()
        synchronized(lines) {
            var n = 0
            while (lines.isNotEmpty() && n < max) {
                val l = lines.removeFirst()
                out.put(
                    JSONObject()
                        .put("ts", l.ts / 1000.0)
                        .put("level", l.level)
                        .put("tag", l.tag)
                        .put("text", l.text),
                )
                n++
            }
        }
        return out
    }

    /**
     * Everything the hub's checklist needs. [glassesDisplay] is the display GlassOS decided
     * belongs to the glasses (null when it found none).
     */
    fun snapshot(context: Context, glassesDisplay: Display?): JSONObject {
        val json = JSONObject()
        runCatching {
            json.put("device", "${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
            json.put("appVersion", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            json.put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
        }
        runCatching { putDisplays(context, glassesDisplay, json) }
            .onFailure { json.put("displaysError", it.message.orEmpty()) }
        runCatching { putUsb(context, json) }
            .onFailure { json.put("usbError", it.message.orEmpty()) }
        runCatching { putNative(json) }
            .onFailure { json.put("sdkError", it.message.orEmpty()) }
        runCatching {
            json.put("workspace", WorkspaceController.isRunning)
            json.put("workspaceError", workspaceError)
            json.put("headTracking", WorkspaceController.headTrackingActive)
            json.put("trackingDetail", GlassesStatus.tracking)
            json.put("trackingError", GlassesStatus.lastError)
            json.put("layout", WorkspaceController.layout.name)
            json.put("privileged", PrivilegedService.state == PrivilegedService.State.READY)
            json.put("privilegedState", PrivilegedService.state.name)
        }
        return json
    }

    private fun putDisplays(context: Context, glasses: Display?, json: JSONObject) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val arr = JSONArray()
        dm.displays.forEach { d ->
            arr.put(
                JSONObject()
                    .put("id", d.displayId)
                    .put("name", d.name)
                    .put("size", "${d.mode.physicalWidth}x${d.mode.physicalHeight}")
                    .put("hz", Math.round(d.mode.refreshRate))
                    .put("state", stateName(d.state))
                    .put("presentation", d.flags and Display.FLAG_PRESENTATION != 0)
                    .put("secure", d.flags and Display.FLAG_SECURE != 0),
            )
        }
        json.put("displays", arr)
        json.put("displayCount", arr.length())
        json.put("glassesDisplay", glasses != null)
        json.put("displayPick", displayPick)
        json.put(
            "displayLabel",
            glasses?.let {
                "${it.name} · ${it.mode.physicalWidth}×${it.mode.physicalHeight} @ " +
                    "${Math.round(it.mode.refreshRate)} Hz"
            }.orEmpty(),
        )
    }

    private fun putUsb(context: Context, json: JSONObject) {
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val arr = JSONArray()
        var vitureePid = 0
        usb.deviceList.values.forEach { d ->
            val viture = GlassesUsb.isVitureDevice(d)
            if (viture) vitureePid = d.productId
            arr.put(
                JSONObject()
                    .put("name", d.productName.orEmpty())
                    .put("vid", "0x%04x".format(d.vendorId))
                    .put("pid", "0x%04x".format(d.productId))
                    .put("viture", viture)
                    .put("permission", usb.hasPermission(d)),
            )
        }
        json.put("usbDevices", arr)
        json.put("usbPid", vitureePid)
        json.put(
            "usbPermission",
            usb.deviceList.values.any { GlassesUsb.isVitureDevice(it) && usb.hasPermission(it) },
        )
    }

    private fun putNative(json: JSONObject) {
        val loaded = NativeGlasses.libraryLoaded
        json.put("sdkLoaded", loaded)
        val present = loaded && runCatching { NativeGlasses.isSdkPresent() }.getOrDefault(false)
        json.put("sdkPresent", present)
        json.put(
            "sdkDisplayMode",
            if (loaded) runCatching { "0x%02x".format(NativeGlasses.getDisplayMode()) }
                .getOrDefault("?") else "",
        )
        json.put(
            "sdkVersion",
            if (loaded) runCatching { NativeGlasses.getVersion() }.getOrDefault("?") else "",
        )
    }

    private fun stateName(state: Int): String = when (state) {
        Display.STATE_OFF -> "OFF"
        Display.STATE_ON -> "ON"
        Display.STATE_DOZE -> "DOZE"
        Display.STATE_DOZE_SUSPEND -> "DOZE_SUSPEND"
        Display.STATE_ON_SUSPEND -> "ON_SUSPEND"
        Display.STATE_VR -> "VR"
        else -> "UNKNOWN($state)"
    }
}
