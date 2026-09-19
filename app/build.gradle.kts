plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.uxspace"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.uxspace"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.0.8"

        // Cloudflare Access service-token headers for the in-app self-updater to reach the
        // gated dist server (https://dist.darkclad.org/uxspace/). Injected at build time by
        // publish-uxspace.ps1 (reads the untracked creds file and passes
        // -PCF_ACCESS_CLIENT_ID=… -PCF_ACCESS_CLIENT_SECRET=…). Empty by default, so a plain
        // `gradlew assembleDebug` produces a LAN-only build whose updater sends no headers.
        val cfClientId = (project.findProperty("CF_ACCESS_CLIENT_ID") as String?) ?: ""
        val cfClientSecret = (project.findProperty("CF_ACCESS_CLIENT_SECRET") as String?) ?: ""
        buildConfigField("String", "CF_ACCESS_CLIENT_ID", "\"$cfClientId\"")
        buildConfigField("String", "CF_ACCESS_CLIENT_SECRET", "\"$cfClientSecret\"")
    }

    buildFeatures {
        viewBinding = false
        compose = true
        aidl = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // Head tracking + the native VITURE SDK bridge, behind its own library.
    implementation(project(":glasses"))
    // The 3D workspace — camera, screens, GL renderer.
    implementation(project(":spatial"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)

    // Embedded ADB client — UxSpace activates its own shell-uid helper over Wireless
    // Debugging, with no separate Shizuku app. See docs/PRIVILEGE.md.
    implementation(libs.libadb.android)
    implementation(libs.conscrypt.android)
    implementation(libs.sun.security.android)
}
