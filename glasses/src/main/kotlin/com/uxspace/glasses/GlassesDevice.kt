package com.uxspace.glasses

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.view.Display
import kotlin.math.roundToInt

/**
 * Snapshot of the connected glasses for the phone dashboard.
 *
 * Hardware identity comes from USB; pose / brightness / film come from the VITURE SDK
 * when it is vendored. Without the SDK this still reports the DisplayPort screen.
 */
data class GlassesSnapshot(
    val usbConnected: Boolean,
    val displayConnected: Boolean,
    val modelName: String,
    val productName: String,
    val pid: Int,
    val hasCamera: Boolean,
    val cameraUsbConnected: Boolean,
    val cameraLabel: String,
    val tracking: TrackingKind,
    val sdkPresent: Boolean,
    val sdkVersion: String,
    val brightness: Int,
    val brightnessMax: Int,
    val volume: Int,
    val film: Int,
    val displayMode: Int,
    val stereo3d: Boolean,
    val widthPx: Int,
    val heightPx: Int,
    val refreshHz: Int,
) {
    val displayLabel: String
        get() = if (widthPx > 0) "${widthPx}×${heightPx} @ ${refreshHz} Hz" else "brak obrazu"

    val connected: Boolean get() = usbConnected || displayConnected
}

enum class TrackingKind { NONE, DOF3, DOF6 }

object GlassesDevice {

    const val VENDOR_ID = 0x35CA

    fun snapshot(context: Context, display: Display?): GlassesSnapshot {
        val usb = findUsb(context)
        val cameraUsb = LumaCamera.find(context)
        val sdk = NativeGlasses.libraryLoaded &&
            runCatching { NativeGlasses.isSdkPresent() }.getOrDefault(false)
        val version = if (NativeGlasses.libraryLoaded) {
            runCatching { NativeGlasses.getVersion() }.getOrDefault("")
        } else {
            ""
        }
        val brightness = runCatching { NativeGlasses.getBrightness() }.getOrDefault(-1)
        val volume = runCatching { NativeGlasses.getVolume() }.getOrDefault(-1)
        val film = runCatching { NativeGlasses.getFilm() }.getOrDefault(-1)
        val mode = runCatching { NativeGlasses.getDisplayMode() }.getOrDefault(-1)
        val type = runCatching { NativeGlasses.getDeviceType() }.getOrDefault(-1)
        val modeInfo = DisplayModes.from(mode, display)
        val model = identify(usb, cameraPresent = cameraUsb != null)
        val tracking = when {
            type == NativeGlasses.DEVICE_TYPE_CARINA -> TrackingKind.DOF6
            usb != null -> TrackingKind.DOF3
            else -> TrackingKind.NONE
        }
        return GlassesSnapshot(
            usbConnected = usb != null,
            displayConnected = display != null,
            modelName = model.model,
            productName = usb?.productName?.trim().orEmpty().ifEmpty { model.model },
            pid = usb?.productId ?: 0,
            hasCamera = model.hasCamera || cameraUsb != null,
            cameraUsbConnected = cameraUsb != null,
            cameraLabel = LumaCamera.describe(cameraUsb),
            tracking = tracking,
            sdkPresent = sdk,
            sdkVersion = version,
            brightness = brightness,
            brightnessMax = 8,
            volume = volume,
            film = film,
            displayMode = mode,
            stereo3d = modeInfo.stereo3d,
            widthPx = modeInfo.width,
            heightPx = modeInfo.height,
            refreshHz = modeInfo.refreshHz,
        )
    }

    fun setBrightness(level: Int): Int =
        runCatching { NativeGlasses.setBrightness(level.coerceIn(0, 8)) }.getOrDefault(-1)

    fun setFilm(on: Boolean): Int =
        runCatching { NativeGlasses.setFilm(if (on) 1f else 0f) }.getOrDefault(-1)

    fun setFilmLevel(percent: Float): Int =
        runCatching { NativeGlasses.setFilm((percent / 100f).coerceIn(0f, 1f)) }.getOrDefault(-1)

    fun switchDimension(stereo3d: Boolean): Int =
        runCatching { NativeGlasses.switchDimension(stereo3d) }.getOrDefault(-1)

    fun setRefresh120(): Int =
        runCatching { NativeGlasses.setDisplayMode(NativeGlasses.DISPLAY_MODE_1080P_120) }
            .getOrDefault(-1)

    private fun findUsb(context: Context): UsbDevice? {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        return manager.deviceList.values.firstOrNull { it.vendorId == VENDOR_ID }
    }

    private data class Model(val model: String, val hasCamera: Boolean)

    private fun identify(device: UsbDevice?, cameraPresent: Boolean): Model {
        if (device == null) {
            return if (cameraPresent) Model("Luma Pro", true) else Model("—", false)
        }
        val name = device.productName?.lowercase().orEmpty()
        val pid = device.productId
        return when {
            "ultra" in name || pid in setOf(0x1101, 0x1104) -> Model("Luma Ultra", true)
            "cyber" in name -> Model("Luma Cyber", true)
            "luma pro" in name || ("pro" in name && "luma" in name) -> Model("Luma Pro", true)
            "luma" in name || pid in setOf(0x1121, 0x1131, 0x1141, 0x1151) -> {
                if ("pro" in name || cameraPresent) Model("Luma Pro", true) else Model("Luma", false)
            }
            "beast" in name || pid in setOf(0x1201, 0x1211) -> Model("Beast", true)
            "pro 2" in name -> Model("Pro 2", false)
            "pro" in name -> Model("Pro", false)
            else -> Model(
                device.productName?.trim()?.ifEmpty { "VITURE" } ?: "VITURE",
                cameraPresent,
            )
        }
    }
}

data class DisplayModeInfo(
    val width: Int,
    val height: Int,
    val refreshHz: Int,
    val stereo3d: Boolean,
    val label: String,
)

object DisplayModes {
    fun from(mode: Int, display: Display?): DisplayModeInfo {
        val mapped = when (mode) {
            0x31 -> DisplayModeInfo(1920, 1080, 60, false, "1080p 60 Hz")
            0x33 -> DisplayModeInfo(1920, 1080, 90, false, "1080p 90 Hz")
            0x34 -> DisplayModeInfo(1920, 1080, 120, false, "1080p 120 Hz")
            0x32 -> DisplayModeInfo(3840, 1080, 60, true, "SBS 3D 60 Hz")
            0x35 -> DisplayModeInfo(3840, 1080, 90, true, "SBS 3D 90 Hz")
            0x41 -> DisplayModeInfo(1920, 1200, 60, false, "1200p 60 Hz")
            0x43 -> DisplayModeInfo(1920, 1200, 90, false, "1200p 90 Hz")
            0x44 -> DisplayModeInfo(1920, 1200, 120, false, "1200p 120 Hz")
            0x42 -> DisplayModeInfo(3840, 1200, 60, true, "SBS 3D 1200p")
            0x45 -> DisplayModeInfo(3840, 1200, 90, true, "SBS 3D 1200p 90")
            else -> null
        }
        if (mapped != null) return mapped
        val d = display
        if (d != null) {
            val m = d.mode
            return DisplayModeInfo(
                width = m.physicalWidth,
                height = m.physicalHeight,
                refreshHz = m.refreshRate.roundToInt(),
                stereo3d = m.physicalWidth >= 3840,
                label = "${m.physicalWidth}×${m.physicalHeight}",
            )
        }
        return DisplayModeInfo(0, 0, 0, false, "—")
    }
}
