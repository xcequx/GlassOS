// Stub JNI bridge used when the proprietary VITURE SDK is not vendored.
// Same Java_com_uxspace_glasses_NativeGlasses_* surface as glasses_bridge.cpp.
#include <jni.h>
#include <android/log.h>
#include <cstring>

#define LOG_TAG "GlassOS/NativeStub"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static float g_pose[7] = {0, 0, 0, 1, 0, 0, 0};
static int g_brightness = -1;
static int g_volume = -1;
static int g_film = -1;
static int g_display_mode = -1;

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_uxspace_glasses_NativeGlasses_getVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF("stub-no-sdk");
}

JNIEXPORT jboolean JNICALL
Java_com_uxspace_glasses_NativeGlasses_isSdkPresent(JNIEnv*, jobject) {
    return JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_uxspace_glasses_NativeGlasses_create(JNIEnv*, jobject, jint, jint) {
    LOGI("stub create()");
    return JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_getDeviceType(JNIEnv*, jobject) {
    return -1;
}

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_registerStateCallback(JNIEnv*, jobject) {
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_initialize(JNIEnv*, jobject) { return -2; }

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_start(JNIEnv*, jobject) { return -2; }

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_stop(JNIEnv*, jobject) { return 0; }

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_shutdown(JNIEnv*, jobject) { return 0; }

JNIEXPORT void JNICALL
Java_com_uxspace_glasses_NativeGlasses_destroy(JNIEnv*, jobject) {}

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_openImu(JNIEnv*, jobject) { return -4; }

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_closeImu(JNIEnv*, jobject) { return 0; }

JNIEXPORT jboolean JNICALL
Java_com_uxspace_glasses_NativeGlasses_isProductSupportNativeDof(JNIEnv*, jobject, jint) {
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_uxspace_glasses_NativeGlasses_setupNativeDofDevice(JNIEnv*, jobject) {}

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_nativeRecenterDof(JNIEnv*, jobject) { return -4; }

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_setDofTypeCarina(JNIEnv*, jobject, jboolean) { return -4; }

JNIEXPORT void JNICALL
Java_com_uxspace_glasses_NativeGlasses_startCarinaPollThread(JNIEnv*, jobject) {}

JNIEXPORT void JNICALL
Java_com_uxspace_glasses_NativeGlasses_stopCarinaPollThread(JNIEnv*, jobject) {}

JNIEXPORT jboolean JNICALL
Java_com_uxspace_glasses_NativeGlasses_carinaStopForModeSwitch(JNIEnv*, jobject) {
    return JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_resetOriginCarina(JNIEnv*, jobject) { return -4; }

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_resetPoseCarina(JNIEnv*, jobject) { return -4; }

JNIEXPORT jfloatArray JNICALL
Java_com_uxspace_glasses_NativeGlasses_getPose(JNIEnv* env, jobject) {
    jfloatArray arr = env->NewFloatArray(7);
    env->SetFloatArrayRegion(arr, 0, 7, g_pose);
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_uxspace_glasses_NativeGlasses_isPoseFresh(JNIEnv*, jobject) {
    return JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_getPoseStatus(JNIEnv*, jobject) {
    return 1;
}

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_getBrightness(JNIEnv*, jobject) {
    return g_brightness;
}

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_getVolume(JNIEnv*, jobject) {
    return g_volume;
}

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_getFilm(JNIEnv*, jobject) {
    return g_film;
}

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_getDisplayMode(JNIEnv*, jobject) {
    return g_display_mode;
}

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_setBrightness(JNIEnv*, jobject, jint level) {
    g_brightness = (int)level;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_setVolume(JNIEnv*, jobject, jint level) {
    g_volume = (int)level;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_setFilm(JNIEnv*, jobject, jfloat voltage) {
    g_film = voltage > 0.5f ? 1 : 0;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_setDisplayMode(JNIEnv*, jobject, jint mode) {
    g_display_mode = (int)mode;
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_uxspace_glasses_NativeGlasses_switchDimension(JNIEnv*, jobject, jboolean stereo3d) {
    g_display_mode = stereo3d ? 0x32 : 0x31;
    return 0;
}

}  // extern "C"
