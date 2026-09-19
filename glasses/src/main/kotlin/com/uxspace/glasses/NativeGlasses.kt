package com.uxspace.glasses

/**
 * Kotlin side of the JNI bridge to the native VITURE SDK.
 *
 * Every method is implemented in `glasses_bridge.cpp` (real SDK) or `glasses_bridge_stub.cpp`.
 * Check [libraryLoaded] before calling; a missing `.so` is a soft failure, not a crash.
 *
 * Threading: drive the lifecycle from a single background thread. Pose can be read from any
 * thread — [getPose] is internally locked.
 */
object NativeGlasses {

    val libraryLoaded: Boolean = runCatching {
        System.loadLibrary("glasses_bridge")
        true
    }.getOrDefault(false)

    /** Version string of the bundled `libglasses.so`. Stub returns `"stub-no-sdk"`. */
    external fun getVersion(): String

    /** False when the app was built without the proprietary VITURE SDK. */
    external fun isSdkPresent(): Boolean

    // Device lifecycle -------------------------------------------------------

    /** Open the glasses. [fd] is a USB file descriptor; [pid] the USB product id. */
    external fun create(pid: Int, fd: Int): Boolean

    /** [DEVICE_TYPE_CARINA] for VITURE Carina, or -1 for older Gen1/2 hardware. */
    external fun getDeviceType(): Int

    external fun registerStateCallback(): Int
    external fun initialize(): Int
    external fun start(): Int
    external fun stop(): Int
    external fun shutdown(): Int
    external fun destroy()

    // Gen1/Gen2 head tracking (host-side IMU) --------------------------------

    external fun openImu(): Int
    external fun closeImu(): Int

    // Native-DOF devices (on-glasses tracking) -------------------------------

    /** True if [pid]'s product tracks head motion on the glasses themselves. */
    external fun isProductSupportNativeDof(pid: Int): Boolean
    external fun setupNativeDofDevice()
    external fun nativeRecenterDof(): Int

    // Carina (VIO tracking) --------------------------------------------------

    /** Call after [create] and before [initialize]. */
    external fun setDofTypeCarina(is6dof: Boolean): Int
    external fun startCarinaPollThread()
    external fun stopCarinaPollThread()
    external fun carinaStopForModeSwitch(): Boolean
    external fun resetOriginCarina(): Int
    external fun resetPoseCarina(): Int

    // Pose -------------------------------------------------------------------

    /**
     * The latest pose as 7 floats.
     * Gen1/2: `[roll, pitch, yaw, qw, qx, qy, qz]`; Carina: `[px, py, pz, qw, qx, qy, qz]`.
     */
    external fun getPose(): FloatArray

    /** True once, when a pose has arrived since the previous call. */
    external fun isPoseFresh(): Boolean

    /** Carina 6DOF only: `0` = stable, `1` = unstable. */
    external fun getPoseStatus(): Int

    // Cached device state ----------------------------------------------------

    external fun getBrightness(): Int
    external fun getVolume(): Int
    external fun getFilm(): Int
    external fun getDisplayMode(): Int

    external fun setBrightness(level: Int): Int
    external fun setVolume(level: Int): Int
    external fun setFilm(voltage: Float): Int
    external fun setDisplayMode(mode: Int): Int
    external fun switchDimension(stereo3d: Boolean): Int

    const val DEVICE_TYPE_CARINA: Int = 2

    const val DISPLAY_MODE_1080P_60 = 0x31
    const val DISPLAY_MODE_1080P_90 = 0x33
    const val DISPLAY_MODE_1080P_120 = 0x34
    const val DISPLAY_MODE_SBS_60 = 0x32
}
