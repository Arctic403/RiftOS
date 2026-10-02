plugins {
    id("com.android.application")
}

android {
    namespace = "com.riftos.gameactivityruntimebundle"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.riftos.gameactivityruntimebundle"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    implementation("androidx.games:games-activity:4.4.1")
}
