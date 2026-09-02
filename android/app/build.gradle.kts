plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/riftosAssets"))
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
    }
    into(layout.buildDirectory.dir("generated/riftosAssets/www"))
}

tasks.named("preBuild").configure {
    dependsOn(syncRiftOsWebAssets)
}

dependencies {
    implementation("androidx.webkit:webkit:1.16.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
}
