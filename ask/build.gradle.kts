plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zaptv.ask"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zaptv.ask"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("zap") {
            storeFile = file("../app/zaptv.keystore")
            storePassword = "zaptv123"
            keyAlias = "zaptv"
            keyPassword = "zaptv123"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("zap") }
        debug { signingConfig = signingConfigs.getByName("zap") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.media3:media3-common:1.9.0")
}
