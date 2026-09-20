plugins {
    id("com.android.application")
}

android {
    namespace = "com.riftos.app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

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

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
        "src/main/java/com/riftos/app/RiftBoundedAsync.kt",
        "src/main/java/com/riftos/app/RiftApkV2Signer.kt",
        "src/main/java/com/riftos/app/RiftBuildInstaller.kt",
        "src/main/java/com/riftos/app/RiftBuildLocalExecutor.kt",
        "src/main/java/com/riftos/app/RiftChatHandoff.kt",
        "src/main/java/com/riftos/app/RiftCliHost.kt",
        "src/main/java/com/riftos/app/RiftCodynexBridgeClient.kt",
        "src/main/java/com/riftos/app/RiftDiffEngineV2.kt",
        "src/main/java/com/riftos/app/RiftFileIdentityV2.kt",
        "src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt",
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
        "src/main/java/com/riftos/app/RiftPatchManifestV1.kt",
        "src/main/java/com/riftos/app/RiftPatchSessions.kt",
        "src/main/java/com/riftos/app/RiftProjectExporter.kt",
        "src/main/java/com/riftos/app/RiftRelaySettings.kt",
        "src/main/java/com/riftos/app/RiftSecretStore.kt",
        "src/main/java/com/riftos/app/RiftShellExecutor.kt",
        "src/main/java/com/riftos/app/RiftSourceIntelligenceV2.kt",
        "src/main/java/com/riftos/app/RiftToolHost.kt",
        "src/main/java/com/riftos/app/RiftToolSandbox.kt",
        "src/main/java/com/riftos/app/RiftTrainDataTaskRunner.kt",
        "src/main/java/com/riftos/app/RiftVolumePaths.kt",
        "src/main/java/com/riftos/app/RiftVortexBridgeClient.kt",
        "src/main/java/com/riftos/app/RiftVortexLocalAgent.kt",
        "src/main/java/com/riftos/app/RiftWorkspaceRecords.kt",
        "src/main/java/com/riftos/app/RiftWorkspaceWatcher.kt"
    )

    val requiredNative = listOf(
        "src/main/cpp/CMakeLists.txt",
        "src/main/cpp/riftcli/rift_cli_core.cpp",
        "src/main/cpp/riftcli/rift_cli_core.h",
        "src/main/cpp/riftcli/rift_cli_jni.cpp"
    )

    doLast {
        val duplicates = required.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        if (duplicates.isNotEmpty()) {
            throw GradleException(
                "RiftOS Android source snapshot contains duplicate entries: ${duplicates.joinToString()}"
            )
        }

        val sourceDir = file("src/main/java/com/riftos/app")
        val actual = sourceDir.listFiles()
            ?.filter { it.isFile && it.extension == "kt" }
            ?.map { it.relativeTo(projectDir).invariantSeparatorsPath }
            ?.sorted()
            ?: emptyList()
        val declared = required.sorted()

        if (declared != actual) {
            val missingDeclarations = actual.filterNot(declared::contains)
            val staleDeclarations = declared.filterNot(actual::contains)
            throw GradleException(
                "RiftOS Android source snapshot is not exact. " +
                    "Missing declarations: ${missingDeclarations.joinToString().ifBlank { "none" }}; " +
                    "stale declarations: ${staleDeclarations.joinToString().ifBlank { "none" }}"
            )
        }

        val nativeRoot = file("src/main/cpp")
        val actualNative = nativeRoot.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(projectDir).invariantSeparatorsPath }
            .sorted()
            .toList()
        val declaredNative = requiredNative.sorted()

        if (declaredNative != actualNative) {
            val missingDeclarations = actualNative.filterNot(declaredNative::contains)
            val staleDeclarations = declaredNative.filterNot(actualNative::contains)
            throw GradleException(
                "RiftOS native C++ source snapshot is not exact. " +
                    "Missing declarations: ${missingDeclarations.joinToString().ifBlank { "none" }}; " +
                    "stale declarations: ${staleDeclarations.joinToString().ifBlank { "none" }}"
            )
        }
    }
}

val syncRiftOsWebAssets by tasks.registering(Sync::class) {
    from(rootProject.projectDir.parentFile) {
        include("src/riftpp-core.js")
        include("src/riftvm.js")
        include("src/semnexis-bootstrap.js")
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
    val webKitDependency = Regex(
        """(?m)^\s*import\s+(?:android|androidx)\.webkit\.|(?:android|androidx)\.webkit\."""
    )
    val xmlWebView = Regex("""<\s*(?:android\.webkit\.)?WebView\b""")
    val blockComment = Regex("""(?s)/\*.*?\*/""")

    doLast {
        val violations = mutableListOf<String>()
        val actualOwners = linkedSetOf<String>()
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("kt", "java", "xml") }
            .forEach { source ->
                val text = source.readText()
                val codeWithoutComments = if (source.extension.lowercase() in setOf("kt", "java")) {
                    blockComment.replace(text, "")
                        .lineSequence()
                        .filterNot { it.trimStart().startsWith("//") }
                        .joinToString("\n")
                } else text
                val ownsRendererCode = when (source.extension.lowercase()) {
                    "kt", "java" -> webKitDependency.containsMatchIn(codeWithoutComments)
                    "xml" -> xmlWebView.containsMatchIn(text)
                    else -> false
                }
                if (!ownsRendererCode) return@forEach

                actualOwners += source.name
                val allowed = source.name in allowedOwners && source.name.startsWith("RiftBrowser")
                if (!allowed) {
                    violations += source.relativeTo(projectDir).invariantSeparatorsPath
                }
            }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "RiftOS WebView ownership violation. Actual WebKit dependencies/WebView XML are allowed only in " +
                    "explicit RiftBrowser-owned sources. Violations: " +
                    violations.sorted().joinToString()
            )
        }
        if (actualOwners != allowedOwners) {
            val missingOwners = (allowedOwners - actualOwners).sorted()
            val unexpectedOwners = (actualOwners - allowedOwners).sorted()
            throw GradleException(
                "RiftBrowser WebKit owner set drifted. " +
                    "Missing owners: ${missingOwners.joinToString().ifBlank { "none" }}; " +
                    "unexpected owners: ${unexpectedOwners.joinToString().ifBlank { "none" }}"
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
