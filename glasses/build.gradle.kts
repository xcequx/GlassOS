plugins {
    // Kotlin support is built into AGP 9 — no separate Kotlin plugin needed.
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.uxspace.glasses"
    compileSdk = 36

    // Native IMU/SDK bridge. Off by default so a phone APK can build without NDK.
    // Enable with -Pglassos.native=true when the VITURE SDK / NDK are installed.
    val enableNative = (project.findProperty("glassos.native") as String?) == "true"
    if (enableNative) {
        ndkVersion = "30.0.14904198"
    }

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
