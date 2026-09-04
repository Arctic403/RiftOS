plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.riftos.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.riftos.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        // RiftOS executes only immutable binaries bundled in the APK's native library dir.
        // Android 10+ blocks execve() from writable app storage.
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libllamaserver.so", "**/libcodex.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
