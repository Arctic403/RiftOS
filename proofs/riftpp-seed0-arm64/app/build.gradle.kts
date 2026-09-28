plugins {
    id("com.android.application")
}

android {
    namespace = "com.riftpp.nativeproof"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.riftpp.nativeproof"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-seed0-arm64-proof"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
}
