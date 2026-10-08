import java.util.Properties

plugins {
    // Kotlin support is built into AGP 9 — no separate Kotlin plugin needed.
    alias(libs.plugins.android.library)
}

val NDK_VERSION = "30.0.14904198"

/** Android SDK root, from local.properties or the usual environment variables. */
val androidSdkRoot: File? = sequence {
    val props = rootProject.file("local.properties")
    if (props.exists()) {
        val loaded = Properties().apply { props.inputStream().use { load(it) } }
        yield(loaded.getProperty("sdk.dir"))
    }
    yield(System.getenv("ANDROID_HOME"))
    yield(System.getenv("ANDROID_SDK_ROOT"))
}.filterNotNull().map { File(it) }.firstOrNull { it.isDirectory }

val ndkInstalled: Boolean = androidSdkRoot?.resolve("ndk/$NDK_VERSION")?.isDirectory == true

/** Where the official VITURE package is unpacked in this repo (gitignored binaries). */
val VENDOR_SDK_DIR: File = rootProject.file("sdk/VITURE_XR_Glasses_SDK_for_Android")

/**
 * Copy the vendored VITURE SDK into the module before anything compiles.
 *
 * Drop the official package into `sdk/` and the build picks it up: `.so` files into
 * jniLibs, headers into cpp/include. Nothing to do by hand, and no binaries in git.
 */
val syncVitureSdk = tasks.register<Copy>("syncVitureSdk") {
    // No onlyIf {}: a missing sdk/ directory simply copies nothing, and an onlyIf
    // closure here would capture the build script itself (configuration cache).
    from(VENDOR_SDK_DIR.resolve("android")) {
        include("*/*.so")
        into("jniLibs")
    }
    from(VENDOR_SDK_DIR.resolve("include")) {
        include("*.h")
        into("cpp/include")
    }
    into(layout.projectDirectory.dir("src/main"))
    // The SDK ships one copy per ABI; we only build arm64 phones.
    exclude("armeabi-v7a/**")
}

tasks.configureEach {
    if (name == "preBuild") dependsOn(syncVitureSdk)
}

android {
    namespace = "com.uxspace.glasses"
    compileSdk = 36

    // Native IMU/SDK bridge. It builds itself when both halves are in place: the
    // VITURE SDK vendored under glasses/src/main/ (syncSdk below copies it out of
    // sdk/ for you) and an NDK in the Android SDK. Force either way with
    // -Pglassos.native=true / =false.
    val sdkVendored = file("src/main/jniLibs/arm64-v8a/libglasses.so").exists() ||
        VENDOR_SDK_DIR.resolve("android/arm64-v8a/libglasses.so").exists()
    val enableNative = when (project.findProperty("glassos.native") as String?) {
        "true" -> true
        "false" -> false
        else -> sdkVendored && ndkInstalled
    }
    if (enableNative) {
        ndkVersion = NDK_VERSION
    }
    logger.lifecycle(
        "GlassOS glasses: native bridge " + (if (enableNative) "ON" else "OFF") +
            " (SDK=" + sdkVendored + ", NDK=" + ndkInstalled + ")",
    )

    defaultConfig {
        minSdk = 26

        if (enableNative) {
            ndk {
                abiFilters += "arm64-v8a"
            }
            externalNativeBuild {
                cmake {
                    cppFlags += "-std=c++17"
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    if (enableNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "4.1.2"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
