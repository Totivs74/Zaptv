plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zaptv.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zaptv.app"
        minSdk = 23
        targetSdk = 34
        versionCode = 8
        versionName = "1.4.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // One project, two builds: "tv" is today's ZapTV (unchanged), "tablet" is the touch version.
    // Shared code lives in src/main; each build adds its own screens in src/tv or src/tablet.
    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
        }
        create("tablet") {
            dimension = "device"
            applicationIdSuffix = ".tablet"
            minSdk = 24   // WireGuard library (Turkey connection) needs Android 7+
            versionCode = 2
            versionName = "1.1-test"
        }
    }

    signingConfigs {
        create("zap") {
            storeFile = file("zaptv.keystore")
            storePassword = "zaptv123"
            keyAlias = "zaptv"
            keyPassword = "zaptv123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("zap")
        }
        debug {
            signingConfig = signingConfigs.getByName("zap")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Needed by the WireGuard library in the tablet build; no behaviour change for TV.
        isCoreLibraryDesugaringEnabled = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val media3 = "1.9.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    // FFmpeg audio fallback (AC-3, E-AC-3, DTS, MP2...). Loaded automatically by Media3.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:$media3+1")
    implementation("com.google.zxing:core:3.5.3")
    // Turkey connection (tablet build only): official WireGuard tunnel library (Apache 2.0)
    "tabletImplementation"("com.wireguard.android:tunnel:1.0.20260102")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
}
