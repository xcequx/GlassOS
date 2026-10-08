package com.uxspace.glasses

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Host-side 3DoF for VITURE Gen2 glasses (Luma / Luma Pro / Pro 2) without libglasses.so.
 *
 * Wire format is the public Gen2 HID protocol: preamble 0x0010, little-endian MsgID,
 * fused pose events 0x7308. Start command is IMU 0x0301 with stream=pose, rate=120 Hz.
 */
class VitureHidImu(
    private val connection: UsbDeviceConnection,
    private val device: UsbDevice,
    private val onQuaternion: (w: Float, x: Float, y: Float, z: Float) -> Unit,
) {
    @Volatile private var running = false
    private var reader: Thread? = null
    private var iface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null

    fun start(): Boolean {
        val hid = findHid() ?: run {
            Log.e(TAG, "no HID interface on vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)}")
            return false
        }
        if (!connection.claimInterface(hid.iface, true)) {
            Log.e(TAG, "claimInterface failed")
            return false
        }
        iface = hid.iface
        epIn = hid.epIn
        epOut = hid.epOut
        val started = writeFrame(MSG_IMU_CTRL, byteArrayOf(STREAM_POSE, RATE_120))
        Log.i(TAG, "HID IMU start pose@120Hz writeOk=$started iface=${hid.iface.id}")
        running = true
        reader = Thread({ readLoop() }, "viture-hid-imu").also { it.start() }
        return true
    }

    fun stop() {
        running = false
        runCatching { writeFrame(MSG_IMU_CTRL, byteArrayOf(STREAM_OFF, 0)) }
        reader?.interrupt()
        reader = null
        iface?.let { runCatching { connection.releaseInterface(it) } }
        iface = null
    }

    private fun findHid(): Hid? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_HID) continue
            var inp: UsbEndpoint? = null
            var out: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inp = ep
                else out = ep
            }
            if (inp != null) return Hid(intf, inp, out)
        }
        return null
    }

    private fun writeFrame(msgId: Int, payload: ByteArray): Boolean {
        val out = epOut ?: return false
        val sum = payload.fold(0) { a, b -> a + (b.toInt() and 0xff) } and 0xffff
        val buf = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(PREAMBLE)
        buf.putShort(msgId.toShort())
        buf.putShort(payload.size.toShort())
        buf.putShort(sum.toShort())
        buf.put(payload)
        val bytes = buf.array()
        val n = connection.bulkTransfer(out, bytes, bytes.size, 200)
        return n == bytes.size
    }

    private fun readLoop() {
        val inp = epIn ?: return
        val packet = ByteArray(64.coerceAtLeast(inp.maxPacketSize))
        while (running) {
            val n = connection.bulkTransfer(inp, packet, packet.size, 80)
            if (n < 16) continue
            parse(packet, n)
        }
    }

    private fun parse(packet: ByteArray, n: Int) {
        val buf = ByteBuffer.wrap(packet, 0, n).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.short.toInt() and 0xffff != PREAMBLE.toInt() and 0xffff) return
        val msg = buf.short.toInt() and 0xffff
        val len = buf.short.toInt() and 0xffff
        buf.short // checksum
        if (msg == MSG_POSE_EVENT && len >= 24 && n >= 32) {
            buf.int
            buf.int
            val qw = buf.float
            val qx = buf.float
            val qy = buf.float
            val qz = buf.float
            val mag = qw * qw + qx * qx + qy * qy + qz * qz
            if (mag in 0.25f..4f) onQuaternion(qw, qx, qy, qz)
        }
    }

    private data class Hid(
        val iface: UsbInterface,
        val epIn: UsbEndpoint,
        val epOut: UsbEndpoint?,
    )

    private companion object {
        const val TAG = "UxSpace/HidImu"
        const val PREAMBLE: Short = 0x0010
        const val MSG_IMU_CTRL = 0x0301
        const val MSG_POSE_EVENT = 0x7308
        const val STREAM_OFF: Byte = 0
        const val STREAM_POSE: Byte = 1
        const val RATE_120: Byte = 2
    }
}
