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
        versionCode = 1
        versionName = "0.1.0-android-alpha"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    sourceSets["main"].assets.srcDir("build/generated/riftosAssets")
}

val syncRiftOsWebAssets by tasks.registering(Copy::class) {
    from(rootProject.projectDir.parentFile) {
        include("index.html")
        include("styles.css")
        include("src/**")
        include("apps/**")
        include("engines/**")
        exclude("**/manifest.webmanifest")
        exclude("**/*sw.js")
        exclude("**/pwa-ios.js")
        exclude("**/pwa-ios.css")
        exclude("**/pwa-icon-*.png")
    }
    into(layout.buildDirectory.dir("generated/riftosAssets/www"))

    doLast {
        val riftDevIndex = layout.buildDirectory
            .file("generated/riftosAssets/www/apps/riftdev/index.html")
            .get().asFile
        if (riftDevIndex.exists()) {
            val webOnlyTokens = listOf(
                "mobile-web-app-capable",
                "apple-mobile-web-app",
                "rel=\"manifest\"",
                "apple-touch-icon",
                "pwa-ios.css",
                "pwa-ios.js"
            )
            val cleaned = riftDevIndex.readLines()
                .filterNot { line -> webOnlyTokens.any { token -> line.contains(token) } }
                .joinToString("\n")
                .replace("SafariSafe-v12-Folders-20260820", "AndroidNative-v12-Folders-20260902")
            riftDevIndex.writeText(cleaned + "\n")
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(syncRiftOsWebAssets)
}

dependencies {
    implementation("androidx.webkit:webkit:1.16.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
}
