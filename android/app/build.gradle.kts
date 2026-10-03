import java.security.MessageDigest
import java.util.zip.ZipFile

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
        resources {
            pickFirsts += "kotlin/internal/internal.kotlin_builtins"
        }
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
        "src/main/java/com/riftos/app/RiftAppDiagnosticBridge.kt",
        "src/main/java/com/riftos/app/RiftBuildKotlinCompiler.kt",
        "src/main/java/com/riftos/app/RiftBuildLocalExecutor.kt",
        "src/main/java/com/riftos/app/RiftBuildManagedToolchains.kt",
        "src/main/java/com/riftos/app/RiftBuildNativeToolchain.kt",
        "src/main/java/com/riftos/app/RiftBuildNativeApp.kt",
        "src/main/java/com/riftos/app/RiftChatHandoff.kt",
        "src/main/java/com/riftos/app/RiftCliHost.kt",
        "src/main/java/com/riftos/app/RiftCliEventBus.kt",
        "src/main/java/com/riftos/app/RiftCodynexBridgeClient.kt",
        "src/main/java/com/riftos/app/RiftCodynexEditorBridgeClient.kt",
        "src/main/java/com/riftos/app/RiftppEditorBridgeClient.kt",
        "src/main/java/com/riftos/app/RiftDiffEngineV2.kt",
        "src/main/java/com/riftos/app/RiftFileIdentityV2.kt",
        "src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt",
        "src/main/java/com/riftos/app/CodynexCompilerProvider.kt",
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
        "src/main/java/com/riftos/app/RiftppCompilerService.kt",
        "src/main/java/com/riftos/app/RiftppDynamicCompilerService.kt",
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
        "src/main/cpp/mc0/codynex_mc0_host.cpp",
        "src/main/cpp/mc1/codynex_mc1a_host.cpp",
        "src/main/cpp/mc1/codynex_mc1b_host.cpp",
        "src/main/cpp/m2/codynex_m2_vm0_host.cpp",
        "src/main/cpp/m2/codynex_m2b_host.cpp",
        "src/main/cpp/m2/codynex_mc2a_host.cpp",
        "src/main/cpp/riftpp/riftpp_app0_host.cpp",
        "src/main/cpp/riftpp/riftpp_compiler_host.cpp",
        "src/main/cpp/riftpp/riftpp_dynamic_compiler_host.cpp",
        "src/main/cpp/riftpp/riftpp_seed0_arm64_proof.cpp",
        "src/main/cpp/editor/editor_vm_bridge.cpp",
        "src/main/cpp/editor/riftpp_editor_bridge.cpp",
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

val validateCodynexCompilerTransition by tasks.registering {
    val previousCompiler = "codynex-c0-ref/0.11.0"
    val currentCompiler = "codynex-c0-ref/0.12.0"
    val requiredMarkers = linkedMapOf(
        "src/main/java/com/riftos/app/CodynexCompilerProvider.kt" to listOf(
            "COMPILER_VERSION_PREVIOUS",
            "COMPILER_VERSION_CURRENT",
            "SUPPORTED_COMPILER_VERSIONS",
            "METHOD_COMPILE_VM2",
            "METHOD_COMPILE_PROJECT_VM2",
            previousCompiler,
            currentCompiler
        ),
        "src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt" to listOf(
            "CODYNEX_C0_COMPILER_VERSION_PREVIOUS",
            "CODYNEX_C0_COMPILER_VERSION_CURRENT",
            "CODYNEX_C0_COMPILER_VERSIONS",
            "compileCodynexC0ProjectVM2",
            "CODYNEX_C0_PROJECT_VM2_ENTRY",
            previousCompiler,
            currentCompiler
        ),
        "src/main/java/com/riftos/app/RiftBuildLocalExecutor.kt" to listOf(
            previousCompiler,
            currentCompiler,
            "Codynex standalone app compiler identity drift"
        ),
        "src/main/java/com/riftos/app/RiftNativeShellServices.kt" to listOf(
            "codynex-c0-ref/0.11.0|0.12.0 transition"
        )
    )
    val forbiddenMarkers = linkedMapOf(
        "src/main/java/com/riftos/app/CodynexCompilerProvider.kt" to
            listOf("private const val COMPILER_VERSION ="),
        "src/main/java/com/riftos/app/RiftHeadlessJsRuntime.kt" to
            listOf("private const val CODYNEX_C0_COMPILER_VERSION =")
    )

    doLast {
        requiredMarkers.forEach { (path, markers) ->
            val source = file(path)
            if (!source.isFile) {
                throw GradleException(
                    "Codynex compiler transition source is missing: $path"
                )
            }
            val text = source.readText()
            markers.forEach { marker ->
                if (!text.contains(marker)) {
                    throw GradleException(
                        "Codynex compiler transition contract drifted in $path: missing $marker"
                    )
                }
            }
        }

        forbiddenMarkers.forEach { (path, markers) ->
            val text = file(path).readText()
            markers.forEach { marker ->
                if (text.contains(marker)) {
                    throw GradleException(
                        "Codynex compiler transition regressed to a single-version pin in $path: $marker"
                    )
                }
            }
        }
    }
}

val verifyCodynexEditorPayload by tasks.registering {
    val expected = linkedMapOf(
        "src/main/java/com/codynex/editor/EditorModel.kt" to
            "c1d1ac41bbceb3a046c7bfc1fe70a63ecc56ec5186bbe5440a31eb00d1f71d32",
        "src/main/java/com/codynex/editor/EditorPorts.kt" to
            "430810cfdbeca2f945548603790661720aab391e9e04b3367f374b16c1b0a5c6",
        "src/main/java/com/codynex/editor/CodynexEditorController.kt" to
            "d545e3802b300af446bbce56948b10a0ac7b5c00c04c118caa84a93f38e11b45",
        "src/main/java/com/codynex/editorapp/FileWorkspacePort.kt" to
            "d2d4fc035f8e024549f2c3232520cdd40f277966328df75f5c942134017395cc",
        "src/main/java/com/codynex/editorapp/BootstrapArtifacts.kt" to
            "78fc1b99806b7b2c4d28b583bbb0bfa96cb50d859877948ce6d0acd12e137a6a",
        "src/main/java/com/codynex/editorapp/Source0SelfHostToolchainPort.kt" to
            "7a1b550743731795ec5b4504a6820dc1515b7be999f7ed7a08ac3fdedde396a9",
        "src/main/java/com/codynex/editorapp/CodynexEditorBridgeService.kt" to
            "90f173bfd359ef34108b68b6cd79fbf950ae4a8cb6d92ea4350f28eaeece01d2",
        "src/main/java/com/codynex/editorapp/CodynexRuntimeBridge.kt" to
            "549217561d9cff3ffdb38f20685b15ee426373b3493a4991e3c2b53469a9ae4f",
        "src/main/java/com/codynex/editorapp/CodynexApkBuilder.kt" to
            "bb16b80adec43339f08aac19054f684d16b72700d07813043760feac78c45f26",
        "src/main/java/com/codynex/editorapp/CodynexApkV2Signer.kt" to
            "3b564713851ad4e393519aee07301760993866875742a5bb5a273bf3dedd5f76",
        "src/main/java/com/codynex/editorapp/MainActivity.kt" to
            "bfe518864d8f30e93028878d4ff18ffe46d828a44ba14dcbc2f95e3f262910f9",
        "src/main/java/com/codynex/apphost/CodynexAppActivity.kt" to
            "481d8b0306bbf02bc173a28c14b359f9867153611b7272435404a4730d786bea",
        "src/main/cpp/editor/editor_vm_bridge.cpp" to
            "f7c442d0cdf609feaeeb96f18608b70d43f6a389a2eafa5fe194a22a2e805701"
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

val verifyRiftppEditorPayload by tasks.registering {
    val expected = linkedMapOf(
        "src/main/java/com/riftpp/editor/MainActivity.kt" to
            "4ff321b5906b74f9977eca9ffb3c0a340df94633ab6278fb301897aba6b9a39e",
        "src/main/java/com/riftpp/editor/RiftppEditorBridgeService.kt" to
            "7da05481e03b6b88ff2060afd69c555ee8f37b3082649bafd52516e65b799bf6",
        "src/main/java/com/riftpp/editor/RiftppPipeline.kt" to
            "621b2c215ec4da4fe3494c6e8c6ad18a060d1b219d4293050b4c9358b01c28d4",
        "src/main/java/com/riftpp/editor/RiftppWorkspace.kt" to
            "a733b06894bcd045e5192f45c0a42072dec27e99f7cf743cfe4cedc4c57e3d19",
        "src/main/java/com/riftpp/editor/RiftppProject.kt" to
            "55baae5229b5aba070400619f86a8138f876ade6027e72e58c9bf6da8737f743",
        "src/main/java/com/riftpp/editor/RiftppNativeBridge.kt" to
            "d4150a6c675ba7df474a3bd52f696d55d851024816c27e98bc2a8788b3bea31d",
        "src/main/java/com/riftpp/editor/HexAssets.kt" to
            "1e71c62ef330ca81b39598c3d6c652b0b7f56b99c9bd97ff133f834d7d229c90",
        "src/main/java/com/riftpp/editor/RiftppUi.kt" to
            "da115257bac2cc32fccf82f506fb8fbf47a4bc40dcfa1066266c66d6b2c9e003",
        "src/main/java/com/riftpp/editor/RiftppApkBuilder.kt" to
            "c9c6d0571ae845648d64cb3fe903661347c1a8dbfc1a22ee5f3eaee786dc189b",
        "src/main/java/com/riftpp/editor/RiftppNativeElfPreflight.kt" to
            "76024430742904f29ecf328552bd307717cfcd717596cfc3e08287d982753060",
        "src/main/java/com/riftpp/editor/RiftppRelocatableElfPreflight.kt" to
            "42ffde20630cfc1e0c60c0bacab07548622a719c5293eeeabc37aeea1a84f899",
        "src/main/java/com/riftpp/editor/RiftppApkV2Signer.kt" to
            "ad403db6f4635816e32fbf02370ddea4e52facd9b3be0782f56e71ae8646ea9a",
        "src/main/java/com/riftpp/apphost/RiftppAppActivity.kt" to
            "48fe3ec0405eb90176dc11473315ac00e48a875b1df278679bfc3b32c4a7f556",
        "src/main/cpp/editor/riftpp_editor_bridge.cpp" to
            "ff1bd4279c56422cfe5fe355c4ada18ba0eae6babb8afa79fd7abc753b51e778"
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
                throw GradleException(
                    "Rift++ App v2 editor payload source is missing: $path"
                )
            }
            val actualSha = sha256(source)
            if (actualSha != expectedSha) {
                throw GradleException(
                    "Rift++ App v2 editor payload drift: " +
                        "$path expected $expectedSha got $actualSha"
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

val syncRiftBuildRiftppAdapterRuntime by tasks.registering {
    dependsOn(":riftpp-adapter-runtime-bundle:assembleDebug")

    val bundleApk = rootProject.file(
        "riftpp-adapter-runtime-bundle/build/outputs/apk/debug/riftpp-adapter-runtime-bundle-debug.apk"
    )
    val outputRoot = layout.buildDirectory.dir(
        "generated/riftosAssets/riftbuild/managed-runtimes/riftpp-adapter-v1"
    )
    inputs.file(bundleApk)
    outputs.dir(outputRoot)

    doLast {
        if (!bundleApk.isFile) {
            throw GradleException("Rift++ Android adapter bundle APK is missing")
        }

        val dexPattern = Regex("^classes(?:[2-9]|[1-9][0-9]+)?\\.dex$")
        val root = outputRoot.get().asFile
        root.deleteRecursively()
        root.mkdirs()

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        var totalBytes = 0L
        val manifestLines = mutableListOf(
            "schema=riftbuild-managed-runtime/1",
            "runtime=riftpp-android-adapter/1",
            "activity=com.riftpp.android.RiftppActivity"
        )

        ZipFile(bundleApk).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { !it.isDirectory && dexPattern.matches(it.name) }
                .toList()
                .sortedBy { entry ->
                    if (entry.name == "classes.dex") 1
                    else entry.name.removePrefix("classes").removeSuffix(".dex").toIntOrNull()
                        ?: Int.MAX_VALUE
                }

            if (entries.none { it.name == "classes.dex" }) {
                throw GradleException("Rift++ Android adapter bundle has no classes.dex")
            }
            if (entries.size > 8) {
                throw GradleException("Rift++ Android adapter dex count exceeds limit")
            }

            entries.forEach { entry ->
                val output = File(root, entry.name)
                zip.getInputStream(entry).use { input ->
                    output.outputStream().buffered().use { sink ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            totalBytes += count
                            if (totalBytes > 16L * 1024L * 1024L) {
                                throw GradleException(
                                    "Rift++ Android adapter DEX bytes exceed limit"
                                )
                            }
                            sink.write(buffer, 0, count)
                        }
                    }
                }

                val header = ByteArray(8)
                output.inputStream().use { input ->
                    var read = 0
                    while (read < header.size) {
                        val count = input.read(header, read, header.size - read)
                        if (count < 0) break
                        if (count > 0) read += count
                    }
                    if (read != header.size ||
                        header[0] != 'd'.code.toByte() ||
                        header[1] != 'e'.code.toByte() ||
                        header[2] != 'x'.code.toByte() ||
                        header[3] != '\n'.code.toByte() ||
                        header[7] != 0.toByte()
                    ) {
                        throw GradleException(
                            "Rift++ Android adapter DEX magic is invalid: ${entry.name}"
                        )
                    }
                }

                manifestLines +=
                    "${entry.name}=${output.length()}:${sha256(output)}"
            }
        }

        File(root, "manifest.txt").writeText(manifestLines.joinToString("\n") + "\n")
    }
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
    dependsOn(validateCodynexCompilerTransition)
    dependsOn(verifyCodynexEditorPayload)
    dependsOn(verifyRiftppEditorPayload)
    dependsOn(validateRiftBrowserWebViewOwnership)
    dependsOn(syncRiftOsWebAssets)
    dependsOn(syncRiftBuildKotlinToolchain)
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
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
