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
    sourceSets["main"].jniLibs.directories.add("build/generated/riftosJniLibs")
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

val riftBuildKotlinCompileClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val riftBuildAndroidSdkDirectory = androidComponents.sdkComponents.sdkDirectory

val verifyRiftOsAndroidSources by tasks.registering {
    val required = listOf(
        "src/main/java/com/riftos/app/MainActivity.kt",
        "src/main/java/com/riftos/app/RiftCoreApplication.kt",
        "src/main/java/com/riftos/app/RiftCoreRuntime.kt",
        "src/main/java/com/riftos/app/RiftCoreAppSessions.kt",
        "src/main/java/com/riftos/app/RiftCoreAppSurfaces.kt",
        "src/main/java/com/riftos/app/RiftCoreInputFocus.kt",
        "src/main/java/com/riftos/app/RiftCoreAppLifecycle.kt",
        "src/main/java/com/riftos/app/RiftCoreAppExecutor.kt",
        "src/main/java/com/riftos/app/RiftCoreShellCapabilityRequests.kt",
        "src/main/java/com/riftos/app/RiftCorePackageEvents.kt",
        "src/main/java/com/riftos/app/RiftCorePackageGrants.kt",
        "src/main/java/com/riftos/app/RiftBrowserAndroidWebViewEngine.kt",
        "src/main/java/com/riftos/app/RiftBrowserAppHost.kt",
        "src/main/java/com/riftos/app/RiftBrowserEngine.kt",
        "src/main/java/com/riftos/app/RiftBrowserMcpAppBridge.kt",
        "src/main/java/com/riftos/app/RiftBrowserPreviewActivity.kt",
        "src/main/java/com/riftos/app/RiftBrowserRendererCrashGuard.kt",
        "src/main/java/com/riftos/app/RiftBrowserWindow.kt",
        "src/main/java/com/riftos/app/RiftBoundedAsync.kt",
        "src/main/java/com/riftos/app/RiftDebugHub.kt",
        "src/main/java/com/riftos/app/RiftApkV2Verifier.kt",
        "src/main/java/com/riftos/app/RiftBuildInstaller.kt",
        "src/main/java/com/riftos/app/RiftAppDiagnosticBridge.kt",
        "src/main/java/com/riftos/app/RiftJvmDexService.kt",
        "src/main/java/com/riftos/app/RiftLocalBuildCapability.kt",
        "src/main/java/com/riftos/app/RiftBuildPlatformTools.kt",
        "src/main/java/com/riftos/app/RiftBuildManagedToolchains.kt",
        "src/main/java/com/riftos/app/RiftChatHandoff.kt",
        "src/main/java/com/riftos/app/RiftMcpEventBus.kt",
        "src/main/java/com/riftos/app/RiftDiffEngineV2.kt",
        "src/main/java/com/riftos/app/RiftFileIdentityV2.kt",
        "src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt",
        "src/main/java/com/riftos/app/RiftLlmDevClient.kt",
        "src/main/java/com/riftos/app/RiftLocalAgentBatch.kt",
        "src/main/java/com/riftos/app/RiftMcpActivity.kt",
        "src/main/java/com/riftos/app/RiftMcpOperationJournal.kt",
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
        "src/main/java/com/riftos/app/RiftAppAbi.kt",
        "src/main/java/com/riftos/app/RiftRappRiftppWs15Adapter.kt",
        "src/main/java/com/riftos/app/RiftRappRiftppGenericAdapter.kt",
        "src/main/java/com/riftos/app/RiftRappJsonAdapter.kt",
        "src/main/java/com/riftos/app/RiftRappQuickJsExecutor.kt",
        "src/main/java/com/riftos/app/RiftExternalRuntimeProviders.kt",
        "src/main/java/com/riftos/app/RiftRappCapabilityBroker.kt",
        "src/main/java/com/riftos/app/RiftRappShellCapabilityClient.kt",
        "src/main/java/com/riftos/app/RiftRappAbsoluteView.kt",
        "src/main/java/com/riftos/app/RiftRappHost.kt",
        "src/main/java/com/riftos/app/RiftRappManager.kt",
        "src/main/java/com/riftos/app/RiftNativeBufferCompilerService.kt",
        "src/main/java/com/riftos/app/RiftManagedJvmToolService.kt",
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
        "src/main/cpp/compiler/rift_native_buffer_compiler_host.cpp"
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

val syncRiftBuildKotlinToolchain by tasks.registering {
    val outputRoot = layout.buildDirectory.dir(
        "generated/riftosAssets/riftbuild/kotlin-toolchain"
    )
    inputs.files(riftBuildKotlinCompileClasspath)
    inputs.file(
        riftBuildAndroidSdkDirectory.map { sdk ->
            sdk.file("platforms/android-36/android.jar")
        }
    )
    outputs.dir(outputRoot)

    doLast {
        val root = outputRoot.get().asFile
        root.deleteRecursively()
        if (!root.mkdirs() && !root.isDirectory) {
            throw GradleException("Could not create RiftBuild Kotlin toolchain asset root")
        }

        val sdkDir = riftBuildAndroidSdkDirectory.get().asFile
        val androidJar = File(sdkDir, "platforms/android-36/android.jar")
        if (!androidJar.isFile) {
            throw GradleException("Android 36 android.jar is missing for RiftBuild Kotlin toolchain")
        }
        androidJar.copyTo(File(root, "android.jar"), overwrite = true)

        val stdlib = riftBuildKotlinCompileClasspath.resolve()
            .filter { file ->
                file.isFile &&
                    file.name.startsWith("kotlin-stdlib-") &&
                    file.name.endsWith(".jar") &&
                    !file.name.contains("sources")
            }
            .singleOrNull()
            ?: throw GradleException("Pinned Kotlin stdlib jar could not be resolved uniquely")
        stdlib.copyTo(File(root, "kotlin-stdlib.jar"), overwrite = true)
    }
}

val syncRiftBuildCompilerSeeds by tasks.registering {
    dependsOn(":rift-managed-kotlin-tool:assembleRelease")

    val outputRoot = layout.buildDirectory.dir(
        "generated/riftosAssets/riftbuild/compiler-seeds"
    )
    outputs.dir(outputRoot)

    doLast {
        val releaseDir = project(":rift-managed-kotlin-tool")
            .layout.buildDirectory.dir("outputs/apk/release")
            .get().asFile
        val candidates = releaseDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        val input = candidates.singleOrNull()
            ?: throw GradleException(
                "Expected exactly one managed Kotlin compiler payload APK, found " +
                    candidates.joinToString { it.name }
            )

        val root = outputRoot.get().asFile
        root.deleteRecursively()
        if (!root.mkdirs() && !root.isDirectory) {
            throw GradleException("Could not create RiftBuild compiler seed asset root")
        }

        val output = File(root, "kotlin-android-2.4.0.apk")
        input.copyTo(output, overwrite = true)

        val digest = MessageDigest.getInstance("SHA-256")
        output.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        File(root, "manifest.txt").writeText(
            "kotlin-android-2.4.0.apk=" + output.length() + ":" + sha + "\n"
        )
    }
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
    dependsOn(syncRiftBuildKotlinToolchain)
    dependsOn(syncRiftBuildCompilerSeeds)
}

dependencies {
    implementation("com.android.tools:r8:8.13.23")
    add(
        riftBuildKotlinCompileClasspath.name,
        "org.jetbrains.kotlin:kotlin-stdlib:2.4.10"
    )

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.webkit:webkit:1.16.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.dokar3:quickjs-kt:1.0.14")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
