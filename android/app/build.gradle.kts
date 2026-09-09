plugins {
    id("com.android.application")
}

android {
    namespace = "com.riftos.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.riftos.app"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 2
        versionName = "0.5.2-rift-agent-v3.2-alpha"
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = false
            enableV4Signing = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }

    sourceSets["main"].assets.srcDir("build/generated/riftosAssets")
}

val syncRiftOsWebAssets by tasks.registering(Copy::class) {
    from(rootProject.projectDir.parentFile) {
        include("index.html")
        include("styles.css")
        include("src/**")
        include("apps/riftdev/index.html")
        include("apps/riftdev/style.css")
        include("apps/riftdev/riftdev-android-storage.js")
        include("apps/riftdev/riftdev-android.js")
        include("apps/riftdev/ide-v11.js")
        include("apps/riftdev/ai-handoff.js")
        include("apps/riftdev/local-test-android.js")
        include("apps/riftdev/riftos-overlay.js")
        exclude("src/riftbrowser-*")
        exclude("**/manifest.webmanifest")
        exclude("**/*sw.js")
        exclude("**/pwa-*")
    }
    into(layout.buildDirectory.dir("generated/riftosAssets/www"))

    doLast {
        val androidEditor = layout.buildDirectory.file("generated/riftosAssets/www/apps/riftdev/riftdev-android.js").get().asFile
        if (androidEditor.exists()) {
            val cleaned = androidEditor.readText()
                .replace("indexedDB.open(", "RiftDevAndroidDB.open(")
                .replace("window.indexedDB", "window.RiftDevAndroidDB")
                .replace("SafariSafe-v12-Folders-20260820", "AndroidNative-v13-Samsung-20260902")
                .replace("MobileWorkspaceDB_SafariSafe_v4", "RiftDevAndroidNativeDB_v1")
                .replace("Safari/WebKit", "Android/System WebView")
                .replace("Safari", "Android")
            androidEditor.writeText(cleaned)
        }
    }
}

tasks.named("preBuild").configure { dependsOn(syncRiftOsWebAssets) }

dependencies {
    implementation("androidx.webkit:webkit:1.16.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
}
