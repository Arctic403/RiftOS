plugins {
    id("com.android.application")
}

android {
    namespace = "com.riftos.app"
    compileSdk = 36

    val riftSourceSha = System.getenv("SOURCE_SHA")?.trim().orEmpty().ifBlank { "local" }
    val riftBuildRunId = System.getenv("GITHUB_RUN_ID")?.trim().orEmpty().ifBlank { "local" }
    val riftBuildRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.trim().orEmpty().ifBlank { "local" }

    defaultConfig {
        applicationId = "com.riftos.app"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 2
        versionName = "0.11.11-relay-client"
        buildConfigField("String", "RIFT_SOURCE_SHA", "\"$riftSourceSha\"")
        buildConfigField("String", "RIFT_BUILD_RUN_ID", "\"$riftBuildRunId\"")
        buildConfigField("String", "RIFT_BUILD_RUN_NUMBER", "\"$riftBuildRunNumber\"")
    }

    buildFeatures {
        buildConfig = true
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
        "src/main/java/com/riftos/app/MainActivity.kt",
        "src/main/java/com/riftos/app/RiftBrowserAndroidWebViewEngine.kt",
        "src/main/java/com/riftos/app/RiftBrowserAppHost.kt",
        "src/main/java/com/riftos/app/RiftBrowserEngine.kt",
        "src/main/java/com/riftos/app/RiftBrowserMcpAppBridge.kt",
        "src/main/java/com/riftos/app/RiftBrowserPreviewActivity.kt",
        "src/main/java/com/riftos/app/RiftBrowserRendererCrashGuard.kt",
        "src/main/java/com/riftos/app/RiftBrowserWindow.kt",
        "src/main/java/com/riftos/app/RiftChatHandoff.kt",
        "src/main/java/com/riftos/app/RiftExperimentalCli.kt",
        "src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt",
        "src/main/java/com/riftos/app/RiftIrCliV1.kt",
        "src/main/java/com/riftos/app/RiftIrV1.kt",
        "src/main/java/com/riftos/app/RiftLlmDevClient.kt",
        "src/main/java/com/riftos/app/RiftMcpActivity.kt",
        "src/main/java/com/riftos/app/RiftMcpRelayClient.kt",
        "src/main/java/com/riftos/app/RiftMcpRuntime.kt",
        "src/main/java/com/riftos/app/RiftMcpServer.kt",
        "src/main/java/com/riftos/app/RiftNativeDesktop.kt",
        "src/main/java/com/riftos/app/RiftNativeDevLab.kt",
        "src/main/java/com/riftos/app/RiftNativeGit.kt",
        "src/main/java/com/riftos/app/RiftNativeShell.kt",
        "src/main/java/com/riftos/app/RiftNativeShellServices.kt",
        "src/main/java/com/riftos/app/RiftNativeSystemApps.kt",
        "src/main/java/com/riftos/app/RiftNativeWorkspaceApps.kt",
        "src/main/java/com/riftos/app/RiftPlusPlusV0.kt",
        "src/main/java/com/riftos/app/RiftProjectExporter.kt",
        "src/main/java/com/riftos/app/RiftRelaySettings.kt",
        "src/main/java/com/riftos/app/RiftSecretStore.kt",
        "src/main/java/com/riftos/app/RiftShellExecutor.kt",
        "src/main/java/com/riftos/app/RiftSwarmCoordinatorV0.kt",
        "src/main/java/com/riftos/app/RiftTextEncoderTaskRunner.kt",
        "src/main/java/com/riftos/app/RiftToolHost.kt",
        "src/main/java/com/riftos/app/RiftToolSandbox.kt",
        "src/main/java/com/riftos/app/RiftTrainDataTaskRunner.kt",
        "src/main/java/com/riftos/app/RiftVolumePaths.kt",
        "src/main/java/com/riftos/app/RiftVortexBridgeClient.kt",
        "src/main/java/com/riftos/app/RiftVortexLocalAgent.kt",
        "src/main/java/com/riftos/app/RiftWorkspaceRecords.kt",
        "src/main/java/com/riftos/app/RiftWorkspaceWatcher.kt"
    )
    doLast {
        val missing = required.filter { !file(it).exists() }
        if (missing.isNotEmpty()) {
            throw GradleException("RiftOS Android source snapshot incomplete. Missing: ${missing.joinToString()}")
        }
    }
}

val syncRiftOsWebAssets by tasks.registering(Sync::class) {
    // Native RiftOS no longer ships the old HTML/DOM shell. Only trusted non-UI modules used by
    // the bounded headless Rift++ runtime are copied into the generated www asset namespace.
    from(rootProject.projectDir.parentFile) {
        include("src/riftpp-core.js")
        include("src/riftvm.js")
    }
    into(layout.buildDirectory.dir("generated/riftosAssets/www"))
}


val validateRiftBrowserWebViewOwnership by tasks.registering {
    val sourceRoot = file("src/main")
    val allowedOwners = setOf(
        "RiftBrowserAndroidWebViewEngine.kt",
        "RiftBrowserWindow.kt",
        "RiftBrowserMcpAppBridge.kt",
        "RiftBrowserAppHost.kt",
        "RiftBrowserPreviewActivity.kt",
        "RiftBrowserRendererCrashGuard.kt"
    )
    val forbiddenCode = listOf(
        Regex("""import\s+android\.webkit\."""),
        Regex("""import\s+androidx\.webkit\."""),
        Regex("""import\s+android\.webkit\.(?:WebView|WebViewClient)\b"""),
        Regex("""android\.webkit\.WebView\b"""),
        Regex("""\bWebView\s*\("""),
        Regex("""\bWebView\s*[?.:]"""),
        Regex("""\bWebViewClient\b"""),
        Regex("""\bWebViewCompat\b"""),
        Regex("""\bWebViewFeature\b"""),
        Regex("""<\s*(?:android\.webkit\.)?WebView\b""")
    )

    doLast {
        val violations = mutableListOf<String>()
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("kt", "java", "xml") }
            .forEach { source ->
                val text = source.readText()
                val ownsRendererCode = forbiddenCode.any { it.containsMatchIn(text) }
                if (!ownsRendererCode) return@forEach
                val allowed = source.name in allowedOwners &&
                    source.name.startsWith("RiftBrowser")
                if (!allowed) {
                    violations += source.relativeTo(projectDir).invariantSeparatorsPath
                }
            }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "RiftOS WebView ownership violation. Chromium/WebView code is allowed only in " +
                    "explicit RiftBrowser-owned sources. Violations: " +
                    violations.sorted().joinToString()
            )
        }
    }
}


tasks.named("preBuild").configure {
    dependsOn(verifyRiftOsAndroidSources)
    dependsOn(validateRiftBrowserWebViewOwnership)
    dependsOn(syncRiftOsWebAssets)
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.webkit:webkit:1.16.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.dokar3:quickjs-kt:1.0.14")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
