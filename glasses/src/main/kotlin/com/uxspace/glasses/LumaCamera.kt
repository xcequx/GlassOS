package com.uxspace.glasses

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Pass-through UVC camera on VITURE glasses.
 *
 * Official pairing (VITURE Glasses SDK):
 *   Luma Pro / Cyber / Ultra  VID 0x0C45  PID 0x636B
 *   Beast                     VID 0x0C45  PID 0x6368
 *
 * This device is a **separate** USB function from the glasses control interface
 * (VID 0x35CA). On many phones it also enumerates as a Camera2 EXTERNAL camera
 * once USB permission is granted — that is the path GlassOS uses to preview
 * and grab JPEGs without the proprietary SDK.
 */
object LumaCamera {

    const val CAMERA_VID = 0x0C45
    const val PID_LUMA_FAMILY = 0x636B
    const val PID_BEAST = 0x6368

    private const val ACTION_USB_PERMISSION = "com.uxspace.CAMERA_USB_PERMISSION"

    fun find(context: Context): UsbDevice? {
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        return usb.deviceList.values.firstOrNull(::isLumaCamera)
    }

    fun isLumaCamera(device: UsbDevice): Boolean =
        device.vendorId == CAMERA_VID &&
            device.productId in setOf(PID_LUMA_FAMILY, PID_BEAST)

    fun hasPermission(context: Context, device: UsbDevice? = find(context)): Boolean {
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val cam = device ?: return false
        return usb.hasPermission(cam)
    }

    /**
     * Ask for USB access to the camera interface. Needed on some OEMs before
     * Camera2 lists the EXTERNAL camera. [onResult] is invoked on the main thread
     * if a receiver is registered; if permission is already held, it fires immediately.
     */
    fun requestPermission(context: Context, onResult: (granted: Boolean) -> Unit) {
        val device = find(context)
        if (device == null) {
            onResult(false)
            return
        }
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usb.hasPermission(device)) {
            onResult(true)
            return
        }
        val app = context.applicationContext
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                runCatching { app.unregisterReceiver(this) }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                onResult(granted)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pending = PendingIntent.getBroadcast(
            app,
            1,
            Intent(ACTION_USB_PERMISSION).setPackage(app.packageName),
            flags,
        )
        usb.requestPermission(device, pending)
    }

    fun describe(device: UsbDevice?): String {
        if (device == null) return "brak kamery UVC"
        val model = when (device.productId) {
            PID_BEAST -> "Beast UVC"
            else -> "Luma Pro UVC"
        }
        return "$model  ${device.productName ?: ""}  pid=0x${device.productId.toString(16)}"
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
