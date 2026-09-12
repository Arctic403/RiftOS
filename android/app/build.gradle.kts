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
        versionName = "0.11.0-relay-client"
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

val verifyRiftOsAndroidSources by tasks.registering {
    val required = listOf(
        "src/main/java/com/riftos/app/RiftToolHost.kt",
        "src/main/java/com/riftos/app/RiftMcpServer.kt",
        "src/main/java/com/riftos/app/RiftMcpActivity.kt",
        "src/main/java/com/riftos/app/RiftMcpRelayClient.kt",
        "src/main/java/com/riftos/app/RiftRelaySettings.kt",
        "src/main/java/com/riftos/app/RiftBrowserEngine.kt",
        "src/main/java/com/riftos/app/AndroidWebViewBrowserEngine.kt",
        "src/main/java/com/riftos/app/RiftBrowserWindow.kt"
    )
    doLast {
        val missing = required.filter { !file(it).exists() }
        if (missing.isNotEmpty()) {
            throw GradleException("RiftOS Android source snapshot incomplete. Missing: ${missing.joinToString()}")
        }
    }
}

val syncRiftOsWebAssets by tasks.registering(Sync::class) {
    from(rootProject.projectDir.parentFile) {
        include("index.html")
        include("styles.css")
        include("src/**")
        include("workspace-live/**")
        exclude("src/riftbrowser-*")
        exclude("**/manifest.webmanifest")
        exclude("**/*sw.js")
        exclude("**/pwa-*")
    }
    into(layout.buildDirectory.dir("generated/riftosAssets/www"))

}

tasks.named("preBuild").configure {
    dependsOn(verifyRiftOsAndroidSources)
    dependsOn(syncRiftOsWebAssets)
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.webkit:webkit:1.16.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
