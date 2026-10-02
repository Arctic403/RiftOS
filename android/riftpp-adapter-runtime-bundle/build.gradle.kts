plugins {
    id("com.android.application")
}

android {
    namespace = "com.riftpp.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.riftpp.android.adapterbundle"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        buildConfig = false
    }
}
