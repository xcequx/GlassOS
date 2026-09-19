package com.uxspace.ai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.uxspace.glasses.LumaCamera
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Grabs JPEG frames from the Luma Pro UVC camera (Camera2 EXTERNAL) or the phone camera.
 *
 * Luma Pro's pass-through camera is a standard UVC device. After USB permission, many
 * phones expose it as [CameraCharacteristics.LENS_FACING_EXTERNAL]. If they don't, the
 * phone's own camera is still available so the AI pipeline can be tested today.
 */
class CameraCapture(context: Context) {

    data class Source(
        val cameraId: String,
        val label: String,
        val kind: Kind,
    )

    enum class Kind { LUMA, EXTERNAL, BACK, FRONT, OTHER }

    var onJpeg: ((ByteArray) -> Unit)? = null

    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val latest = AtomicReference<ByteArray?>(null)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewView: TextureView? = null
    private var previewSurface: Surface? = null
    @Volatile var activeSource: Source? = null
        private set
    @Volatile var running: Boolean = false
        private set
    private val grabRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            grabFromPreview()
            handler?.postDelayed(this, 400)
        }
    }

    fun sources(): List<Source> {
        val out = mutableListOf<Source>()
        val lumaUsb = LumaCamera.find(app) != null
        for (id in manager.cameraIdList) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val kind = when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> Kind.BACK
                CameraCharacteristics.LENS_FACING_FRONT -> Kind.FRONT
                CameraCharacteristics.LENS_FACING_EXTERNAL ->
                    if (lumaUsb) Kind.LUMA else Kind.EXTERNAL
                else -> Kind.OTHER
            }
            val label = when (kind) {
                Kind.LUMA -> "Luma Pro"
                Kind.EXTERNAL -> "Kamera USB"
                Kind.BACK -> "Telefon tył"
                Kind.FRONT -> "Telefon przód"
                Kind.OTHER -> "Kamera $id"
            }
            out += Source(id, label, kind)
        }
        return out.sortedBy { sourceRank(it.kind) }
    }

    fun preferred(): Source? {
        val all = sources()
        return all.firstOrNull { it.kind == Kind.LUMA }
            ?: all.firstOrNull { it.kind == Kind.EXTERNAL }
            ?: all.firstOrNull { it.kind == Kind.BACK }
            ?: all.firstOrNull()
    }

    fun latestJpeg(): ByteArray? = latest.get()

    @SuppressLint("MissingPermission")
    fun start(source: Source, preview: Surface?, view: TextureView? = null) {
        stop()
        val bg = HandlerThread("glassos-camera").also { it.start(); thread = it }
        val h = Handler(bg.looper).also { handler = it }
        previewSurface = preview
        previewView = view
        activeSource = source
        val size = chooseJpegSize(source.cameraId)
        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        imageReader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val bytes = yuvToJpeg(image) ?: return@setOnImageAvailableListener
                latest.set(bytes)
                onJpeg?.invoke(bytes)
            } finally {
                image.close()
            }
        }, h)
        reader = imageReader
        try {
            manager.openCamera(source.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    running = true
                    val usePreview = preview != null
                    val targets = buildList {
                        if (usePreview) add(preview!!) else add(imageReader.surface)
                    }
                    cam.createCaptureSession(
                        targets,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(sess: CameraCaptureSession) {
                                session = sess
                                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                if (usePreview) req.addTarget(preview!!) else req.addTarget(imageReader.surface)
                                req.set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                )
                                runCatching { sess.setRepeatingRequest(req.build(), null, h) }
                                    .onFailure { Log.e(TAG, "repeating request failed", it) }
                                if (usePreview) h.post(grabRunnable)
                            }

                            override fun onConfigureFailed(sess: CameraCaptureSession) {
                                Log.e(TAG, "capture session configure failed")
                                running = false
                            }
                        },
                        h,
                    )
                }

                override fun onDisconnected(cam: CameraDevice) {
                    running = false
                    cam.close()
                    if (device === cam) device = null
                }

                override fun onError(cam: CameraDevice, error: Int) {
                    Log.e(TAG, "camera error $error")
                    running = false
                    cam.close()
                    if (device === cam) device = null
                }
            }, h)
        } catch (t: Throwable) {
            Log.e(TAG, "openCamera failed", t)
            running = false
        }
    }

    fun stop() {
        running = false
        handler?.removeCallbacks(grabRunnable)
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        session = null
        device = null
        reader = null
        previewSurface = null
        previewView = null
        activeSource = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun grabFromPreview() {
        val view = previewView ?: return
        if (!view.isAvailable) return
        val bmp = runCatching { view.getBitmap(640, 360) }.getOrNull() ?: return
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        latest.set(bytes)
        onJpeg?.invoke(bytes)
    }

    private fun yuvToJpeg(image: Image, quality: Int = 75): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val nv21 = yuv420ToNv21(image) ?: return null
        val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        if (!yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)) return null
        return out.toByteArray()
    }

    private fun yuv420ToNv21(image: Image): ByteArray? {
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val ySize = y.buffer.remaining()
        val width = image.width
        val height = image.height
        val out = ByteArray(width * height * 3 / 2)
        y.buffer.get(out, 0, ySize.coerceAtMost(width * height))
        val chroma = width * height
        val vBuf = v.buffer
        val uBuf = u.buffer
        val vInc = v.pixelStride
        val uInc = u.pixelStride
        var i = chroma
        var vi = 0
        var ui = 0
        val pairs = (width * height) / 4
        var n = 0
        while (n < pairs && i + 1 < out.size) {
            out[i] = vBuf.get(vi.coerceAtMost(vBuf.limit() - 1))
            out[i + 1] = uBuf.get(ui.coerceAtMost(uBuf.limit() - 1))
            i += 2
            vi += vInc
            ui += uInc
            n++
        }
        return out
    }

    private fun chooseJpegSize(cameraId: String): Size {
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1280, 720)
        val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: return Size(1280, 720)
        val ranked = sizes.sortedBy { kotlin.math.abs(it.width * it.height - 1280 * 720) }
        return ranked.firstOrNull { it.width <= 1920 && it.height <= 1080 } ?: sizes.last()
    }

    private fun sourceRank(kind: Kind): Int = when (kind) {
        Kind.LUMA -> 0
        Kind.EXTERNAL -> 1
        Kind.BACK -> 2
        Kind.FRONT -> 3
        Kind.OTHER -> 4
    }

    companion object {
        private const val TAG = "GlassOS/Camera"
    }
}
