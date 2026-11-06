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
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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
}
