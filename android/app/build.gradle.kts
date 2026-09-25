import java.security.MessageDigest

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

    sourceSets["main"].assets.directories.add("build/generated/riftosAssets")
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
        "src/main/java/com/riftos/app/RiftDebugHub.kt",
        "src/main/java/com/riftos/app/RiftApkV2Signer.kt",
        "src/main/java/com/riftos/app/RiftBuildInstaller.kt",
        "src/main/java/com/riftos/app/RiftBuildLocalExecutor.kt",
        "src/main/java/com/riftos/app/RiftChatHandoff.kt",
        "src/main/java/com/riftos/app/RiftCliHost.kt",
        "src/main/java/com/riftos/app/RiftLocalCliPackage.kt",
        "src/main/java/com/riftos/app/RiftCliEventBus.kt",
        "src/main/java/com/riftos/app/RiftCodynexBridgeClient.kt",
        "src/main/java/com/riftos/app/RiftCrossBoundaryContractsV1.kt",
        "src/main/java/com/riftos/app/RiftDocumentationClaimsV1.kt",
        "src/main/java/com/riftos/app/RiftProofObligationsV1.kt",
        "src/main/java/com/riftos/app/RiftMemoryModelV1.kt",
        "src/main/java/com/riftos/app/RiftMemoryStoreV1.kt",
        "src/main/java/com/riftos/app/RiftSqliteMemoryStoreV1.kt",
        "src/main/java/com/riftos/app/RiftMemoryN2M1SelfTest.kt",
        "src/main/java/com/riftos/app/RiftMemoryReconciliationV1.kt",
        "src/main/java/com/riftos/app/RiftMemoryTemporalGraphV1.kt",
        "src/main/java/com/riftos/app/RiftMemoryN2M2SelfTest.kt",
        "src/main/java/com/riftos/app/RiftMemoryConsolidationV1.kt",
        "src/main/java/com/riftos/app/RiftMemoryBeliefDifferenceV1.kt",
        "src/main/java/com/riftos/app/RiftMemoryN2M3SelfTest.kt",
        "src/main/java/com/riftos/app/RiftMemoryProceduralFailureV1.kt",
        "src/main/java/com/riftos/app/RiftMemoryRetrievalContextV1.kt",
        "src/main/java/com/riftos/app/RiftMemoryN2M4SelfTest.kt",
        "src/main/java/com/riftos/app/RiftMemoryObserverValidatorLoopV1.kt",
        "src/main/java/com/riftos/app/RiftMemoryN2M5SelfTest.kt",
        "src/main/java/com/riftos/app/RiftStoreMemoryStoreV1.kt",
        "src/main/java/com/riftos/app/RiftMemoryN2M6SelfTest.kt",
        "src/main/java/com/riftos/app/RiftMemoryN2FinalSelfTest.kt",
        "src/main/java/com/riftos/app/RiftDiffEngineV2.kt",
        "src/main/java/com/riftos/app/RiftFileIdentityV2.kt",
        "src/main/java/com/riftos/app/RiftFrozenByteBpeV1.kt",
        "src/main/java/com/riftos/app/RiftB2BottomKDedupV1.kt",
        "src/main/java/com/riftos/app/RiftB2NearDedupIndexV1.kt",
        "src/main/java/com/riftos/app/RiftB2ThresholdQualificationV1.kt",
        "src/main/java/com/riftos/app/RiftB2ThresholdQualificationTask.kt",
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
        "src/main/java/com/riftos/app/RiftRepositoryConsistencyObserver.kt",
        "src/main/java/com/riftos/app/RiftSecretStore.kt",
        "src/main/java/com/riftos/app/RiftShellExecutor.kt",
        "src/main/java/com/riftos/app/RiftSourceIntelligenceV2.kt",
        "src/main/java/com/riftos/app/RiftToolHost.kt",
        "src/main/java/com/riftos/app/RiftToolSandbox.kt",
        "src/main/java/com/riftos/app/RiftTrainDataTaskRunner.kt",
        "src/main/java/com/riftos/app/RiftTrainDataV2Format.kt",
        "src/main/java/com/riftos/app/RiftTrainDataV2TaskRunner.kt",
        "src/main/java/com/riftos/app/RiftTrainDataV2AdversarialLab.kt",
        "src/main/java/com/riftos/app/RiftVolumePaths.kt",
        "src/main/java/com/riftos/app/RiftVortexBridgeClient.kt",
        "src/main/java/com/riftos/app/RiftVortexLocalAgent.kt",
        "src/main/java/com/riftos/app/RiftWorkspaceRecords.kt",
        "src/main/java/com/riftos/app/RiftWorkspaceWatcher.kt"
    )

    val requiredNative = listOf(
        "src/main/cpp/CMakeLists.txt",
        "src/main/cpp/mc0/codynex_mc0_host.cpp",
        "src/main/cpp/mc1/codynex_mc1a_host.cpp",
        "src/main/cpp/mc1/codynex_mc1b_host.cpp",
        "src/main/cpp/m2/codynex_m2_vm0_host.cpp",
        "src/main/cpp/m2/codynex_m2b_host.cpp",
        "src/main/cpp/m2/codynex_mc2a_host.cpp",
        "src/main/cpp/m2/codynex_l0_d3_host.cpp",
        "src/main/cpp/editor/editor_vm_bridge.cpp",
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

val verifyCodynexEditorPayload by tasks.registering {
    val expected = linkedMapOf(
        "src/main/java/com/codynex/editor/EditorModel.kt" to
            "d9dbb536e623800f53766f86ded4c31735d2b877fe515fabcf5b9d250e70eb8b",
        "src/main/java/com/codynex/editor/EditorPorts.kt" to
            "427008739cfaf470c78d99ab740263c969335be349d1909926380c31e124c85b",
        "src/main/java/com/codynex/editor/CodynexEditorController.kt" to
            "b68dfe842893871190d9f0585bf96294d62525d4f74cebee88a272204cafe15d",
        "src/main/java/com/codynex/editorapp/FileWorkspacePort.kt" to
            "49f2346ceb2d896203c8aca3305e98724a22d482aa9a0d74e6b9347b0f64bc04",
        "src/main/java/com/codynex/editorapp/BootstrapArtifacts.kt" to
            "d4cd556b6c351c0e81b7e4b0610fd9152fdc47b0ba0e9b01d1e023ce4dd0d9d9",
        "src/main/java/com/codynex/editorapp/Source0SelfHostToolchainPort.kt" to
            "10223ca98ad0f5b33a4a5925380f4ca87f5f237315df841790c6ae347771e243",
        "src/main/java/com/codynex/editorapp/Vm1Bridge.kt" to
            "b844c767e81366f3464988eab060f28ccc5c098cf98a877e71704cc3b2c446bb",
        "src/main/java/com/codynex/editorapp/MainActivity.kt" to
            "542e29806d6f5eef5b22763530e705ae11d67d87ef37387e4b6dcc74eb1c3a12",
        "src/main/cpp/editor/editor_vm_bridge.cpp" to
            "47039b185cc4c481846735946b1f0667564e729f5994b7badb0ab6ce69b978ba"
    )

    doLast {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        expected.forEach { (path, expectedSha) ->
            val source = file(path)
            if (!source.isFile) {
                throw GradleException("Codynex E0 editor payload source is missing: $path")
            }
            val actualSha = sha256(source)
            if (actualSha != expectedSha) {
                throw GradleException(
                    "Codynex E0 editor payload drift: $path expected $expectedSha got $actualSha"
                )
            }
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
    dependsOn(verifyCodynexEditorPayload)
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
