package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Workspace-bounded native RiftBuild controller.
 *
 * Native Compile V1 delegates structured compiler argv execution to RiftBuildNativeToolchain.
 * Project/source text is never interpreted as a shell command. This controller validates Android
 * projects, records bounded plans/runs, packages prepared artifacts, and routes bounded APK operations.
 */
class RiftBuildLocalExecutor(context: Context) {
    data class CommandResult(val output: String, val value: JSONObject)
    private data class ProjectRef(val display: String, val file: File)
    private data class RiftppV0Image(
        val key: String,
        val abi: String,
        val elfClass: Int,
        val machine: Int,
        val sourcePath: String,
        val sourceSha256: String,
        val rawSha256: String,
        val canonicalValueSha256: String,
        val bytes: ByteArray
    )
    private data class ManifestAttr(
        val namespace: Int,
        val name: Int,
        val rawValue: Int,
        val dataType: Int,
        val data: Int
    )
    private data class Vm1Run(
        val status: Int,
        val result: Int,
        val output: ByteArray,
        val steps: Int
    )
    private data class App0Requirements(
        val instructionCount: Int,
        val requiresSourceBytes: Boolean,
        val requiresOutputBytes: Boolean,
        val requiresScratchBytes: Boolean
    )

    companion object {
        private const val MAX_PROJECT_FILES = 20_000
        private const val MAX_PROJECT_BYTES = 512L * 1024L * 1024L
        private const val MAX_PACKAGE_FILES = 5_000
        private const val MAX_PACKAGE_BYTES = 256L * 1024L * 1024L
        private const val MAX_TEXT_BYTES = 1024L * 1024L
        private const val MAX_RUNS = 100
        private const val RIFTPP_V0_SCHEMA = "riftpp-direct-elf-shared-v0-bytes/1"
        private const val RIFTPP_V0_BRIDGE = "compiler/native_backend/evidence/DIRECT-ELF-SHARED-V0-BYTES.json"
        private const val RIFTPP_V0_WRITER = "compiler/native_backend/elf_shared_v0.riftpp"
        private const val RIFTPP_V0_APK_PROJECT = "apk-proof"
        private const val RIFTPP_V0_LIBRARY = "libriftpp_nativeproof.so"
        private const val RIFTPP_V0_MANIFEST_SOURCE = "apk-proof/app/src/main/AndroidManifest.xml"
        private const val RIFTPP_V0_MANIFEST_SOURCE_SHA = "eb0e8b7f3020499b50b135d1ef93c60af89f997c7c1984ec3d17d32c1595a6c1"
        private const val RIFTPP_V0_APP_GRADLE = "apk-proof/app/build.gradle.kts"
        private const val RIFTPP_V0_APP_GRADLE_SHA = "1d1739a07896c4d7f1e521fa154a0285c5c4eefe87eab718830fee37194c0765"
        private const val RIFTPP_V0_BINARY_MANIFEST_BYTES = 1440
        private const val RIFTPP_V0_BINARY_MANIFEST_SHA = "ac035bb5bf89f55a3f34bae8eea980108324d2f36333f1e708f8a0b82af8e7c2"
        private const val RIFTPP_APP0_COMPILER_HEX = "compiler/tig0/compiler_seed.hex"
        private const val RIFTPP_APP0_COMPILER_HEX_BYTES = 9576
        private const val RIFTPP_APP0_COMPILER_HEX_SHA256 = "c756b92c1c0a6dc11060095e0bcb7978087d73d8290a312cb0176908ceadab9b"
        private const val RIFTPP_APP0_COMPILER_BYTES = 4788
        private const val RIFTPP_APP0_META = "riftapp.json"
        private const val RIFTPP_APP0_APK_PROJECT = "apk-proof"
        private const val RIFTPP_APP0_LIBRARY_NAME = "riftpp_app0_host"
        private const val RIFTPP_APP0_LIBRARY_FILE = "libriftpp_app0_host.so"
        private const val RIFTPP_APP0_ARM64_HOST_APK_ENTRY = "lib/arm64-v8a/libriftpp_app0_host.so"
        private const val RIFTPP_APP0_ARM32_HOST_APK_ENTRY = "lib/armeabi-v7a/libriftpp_app0_host.so"
        private const val RIFTPP_APP0_MAX_HOST_BYTES = 4L * 1024L * 1024L
        private const val RIFTPP_APP0_MAX_SOURCE_BYTES = 64 * 1024
        private const val RIFTPP_APP0_MAX_PROGRAM_BYTES = 64 * 1024
        private const val RIFTPP_APP0_STEP_BUDGET = 2_000_000
        private const val RIFTPP_SEED0_ARM64_PROOF_PROJECT = "proofs/riftpp-seed0-arm64"
        private const val RIFTPP_SEED0_ARM64_LIBRARY_NAME = "riftpp_seed0_arm64_proof"
        private const val RIFTPP_SEED0_ARM64_LIBRARY_FILE = "libriftpp_seed0_arm64_proof.so"
        private const val RIFTPP_SEED0_ARM64_HOST_APK_ENTRY = "lib/arm64-v8a/libriftpp_seed0_arm64_proof.so"
        private const val RIFTPP_SEED0_ARM64_VERSION_NAME = "0.1.0-seed0-arm64-proof"
        private const val RIFTPP_SEED0_ARM64_COMPILER_HEX = "compiler/compiler.arm64.hex"
        private const val RIFTPP_SEED0_ARM64_COMPILER_HEX_TEXT_BYTES = 553
        private const val RIFTPP_SEED0_ARM64_COMPILER_HEX_TEXT_SHA256 = "53e8e1e49f6ed4abe7d596ff13c53bb281b7f1bb8e2a2fb018d0933722868f3c"
        private const val RIFTPP_SEED0_ARM64_COMPILER_BYTES = 276
        private const val RIFTPP_SEED0_ARM64_COMPILER_RAW_SHA256 = "b1f33b940d2ac199f5e38c1c621cd8b27ed15dd3a60fcb85daad7b7154b2ee0c"
        private const val RIFTPP_SEED0_ARM64_MAX_HOST_BYTES = 4L * 1024L * 1024L
        private const val MC0_SEED_HEX = "native/mc0/arm32/mc0_seed.hex"
        private const val MC0_SEED_BYTES = 172
        private const val MC0_SEED_SHA256 = "3276dcbf29704b1ba7d9d331e7891ceff10d85b16bb7688c62273aeaa3ca311e"
        private const val MC0_APK_PROJECT = "native/mc0/apk-proof"
        private const val MC0_PACKAGE = "com.codynex.mc0proof"
        private const val MC0_LIBRARY_NAME = "codynex_mc0_host"
        private const val MC0_LIBRARY_FILE = "libcodynex_mc0_host.so"
        private const val MC0_HOST_APK_ENTRY = "lib/armeabi-v7a/libcodynex_mc0_host.so"
        private const val MC0_VERSION_NAME = "0.1.0-mc0-proof"
        private const val MC0_MAX_HOST_BYTES = 4L * 1024L * 1024L
        private const val MC1A_SEED_HEX = "native/mc1/arm32/mc1a_seed.hex"
        private const val MC1A_SEED_BYTES = 236
        private const val MC1A_SEED_SHA256 = "2ef7054e533bfafaefb0fcc14b9cd41cd05aceeec58eeeb335fc6aef4e88ba1a"
        private const val MC1A_APK_PROJECT = "native/mc1/apk-proof"
        private const val MC1A_PACKAGE = "com.codynex.mc1aproof"
        private const val MC1A_LIBRARY_NAME = "codynex_mc1a_host"
        private const val MC1A_LIBRARY_FILE = "libcodynex_mc1a_host.so"
        private const val MC1A_HOST_APK_ENTRY = "lib/armeabi-v7a/libcodynex_mc1a_host.so"
        private const val MC1A_VERSION_NAME = "0.1.0-mc1a-proof"
        private const val MC1A_MAX_HOST_BYTES = 4L * 1024L * 1024L
        private const val MC1B_SEED_HEX = "native/mc1/arm32/mc1b_seed.hex"
        private const val MC1B_SEED_BYTES = 552
        private const val MC1B_SEED_SHA256 = "4f4a7305900547d949831fc4cfc6c6c0f747edd7ab525adfb8a1488a6ca304be"
        private const val MC1B_APK_PROJECT = "native/mc1/apk-proof-b"
        private const val MC1B_PACKAGE = "com.codynex.mc1bproof"
        private const val MC1B_LIBRARY_NAME = "codynex_mc1b_host"
        private const val MC1B_LIBRARY_FILE = "libcodynex_mc1b_host.so"
        private const val MC1B_HOST_APK_ENTRY = "lib/armeabi-v7a/libcodynex_mc1b_host.so"
        private const val MC1B_VERSION_NAME = "0.1.0-mc1b-proof"
        private const val MC1B_MAX_HOST_BYTES = 4L * 1024L * 1024L
        private const val M2_VM0_SEED_HEX = "native/m2/vm0/arm32/vm0_seed.hex"
        private const val M2_VM0_SEED_BYTES = 332
        private const val M2_VM0_SEED_SHA256 = "0577161c8cad09541a998ba44cacd823ce0b0c3a6a5b607960b855483af1dba6"
        private const val M2_VM0_APK_PROJECT = "native/m2/vm0/apk-proof"
        private const val M2_VM0_PACKAGE = "com.codynex.m2vm0proof"
        private const val M2_VM0_LIBRARY_NAME = "codynex_m2_vm0_host"
        private const val M2_VM0_LIBRARY_FILE = "libcodynex_m2_vm0_host.so"
        private const val M2_VM0_HOST_APK_ENTRY = "lib/armeabi-v7a/libcodynex_m2_vm0_host.so"
        private const val M2_VM0_VERSION_NAME = "0.1.0-m2-vm0-proof"
        private const val M2_VM0_MAX_HOST_BYTES = 4L * 1024L * 1024L
        private const val M2_B_VM_HEX = "native/m2/vm1/arm32/vm1_seed.hex"
        private const val M2_B_VM_BYTES = 812
        private const val M2_B_VM_SHA256 = "7d7b33d2796ab2ddbca1519e00f254c2e6c8417af3ee9317ab45929a593b7df5"
        private const val M2_B_COMPILER_HEX = "native/m2/compiler-vm1/mc1b_compiler.hex"
        private const val M2_B_COMPILER_BYTES = 704
        private const val M2_B_COMPILER_SHA256 = "4a3bd4867de5cf76604e5810f2ae92a2af029f694891017bf0e073833510f575"
        private const val M2_B_APK_PROJECT = "native/m2/compiler-vm1/apk-proof"
        private const val M2_B_PACKAGE = "com.codynex.m2bproof"
        private const val M2_B_LIBRARY_NAME = "codynex_m2b_host"
        private const val M2_B_LIBRARY_FILE = "libcodynex_m2b_host.so"
        private const val M2_B_HOST_APK_ENTRY = "lib/armeabi-v7a/libcodynex_m2b_host.so"
        private const val M2_B_VERSION_NAME = "0.1.0-m2b-proof"
        private const val M2_B_MAX_HOST_BYTES = 4L * 1024L * 1024L
        private const val MC2_A_VM_HEX = "native/m2/vm1/arm32/vm1_seed.hex"
        private const val MC2_A_VM_BYTES = 812
        private const val MC2_A_VM_SHA256 = "7d7b33d2796ab2ddbca1519e00f254c2e6c8417af3ee9317ab45929a593b7df5"
        private const val MC2_A_COMPILER_HEX = "native/mc2/source0/selfhost_compiler.hex"
        private const val MC2_A_COMPILER_BYTES = 292
        private const val MC2_A_COMPILER_SHA256 = "b00cc99ef0cf122d47cff54123e1e1ec19f83a44dfe949f5358428e45f47fb2e"
        private const val MC2_A_SOURCE = "native/mc2/source0/selfhost_compiler.cx0"
        private const val MC2_A_SOURCE_BYTES = 584
        private const val MC2_A_SOURCE_SHA256 = "a30e68e38600e25fc394c184b03c3e24f2775ffc2572c19a22426b3a0714581c"
        private const val MC2_A_APK_PROJECT = "native/mc2/source0/apk-proof"
        private const val MC2_A_PACKAGE = "com.codynex.mc2aproof"
        private const val MC2_A_LIBRARY_NAME = "codynex_mc2a_host"
        private const val MC2_A_LIBRARY_FILE = "libcodynex_mc2a_host.so"
        private const val MC2_A_HOST_APK_ENTRY = "lib/armeabi-v7a/libcodynex_mc2a_host.so"
        private const val MC2_A_VERSION_NAME = "0.1.0-mc2a-proof"
        private const val MC2_A_MAX_HOST_BYTES = 4L * 1024L * 1024L
        private const val EDITOR_PROJECT = "external/editor"
        private const val EDITOR_PACKAGE = "com.codynex.editor"
        private const val EDITOR_ACTIVITY = "com.codynex.editorapp.MainActivity"
        private const val EDITOR_LIBRARY_NAME = "codynex_editor_vm"
        private const val EDITOR_LIBRARY_FILE = "libcodynex_editor_vm.so"
        private const val EDITOR_HOST_APK_ENTRY = "lib/armeabi-v7a/libcodynex_editor_vm.so"
        private const val EDITOR_VERSION_NAME = "0.1.0-e0"
        private const val EDITOR_MAX_HOST_BYTES = 4L * 1024L * 1024L
        private const val EDITOR_MAX_DEX_BYTES = 32L * 1024L * 1024L
        private const val EDITOR_MAX_TOTAL_DEX_BYTES = 96L * 1024L * 1024L
        private const val EDITOR_VM_HEX = "native/m2/vm1/arm32/vm1_seed.hex"
        private const val EDITOR_VM_HEX_BYTES = 1624
        private const val EDITOR_VM_HEX_SHA256 = "1f013e2592741895f511d1724ecd69ee156e24f771c289d848e1bab265d3655e"
        private const val EDITOR_COMPILER_HEX = "native/mc2/source0/selfhost_compiler.hex"
        private const val EDITOR_COMPILER_HEX_BYTES = 584
        private const val EDITOR_COMPILER_HEX_SHA256 = "a30e68e38600e25fc394c184b03c3e24f2775ffc2572c19a22426b3a0714581c"
        private const val EDITOR_SOURCE0 = "native/mc2/source0/selfhost_compiler.cx0"
        private const val EDITOR_SOURCE0_BYTES = 584
        private const val EDITOR_SOURCE0_SHA256 = "a30e68e38600e25fc394c184b03c3e24f2775ffc2572c19a22426b3a0714581c"
        private val EDITOR_SOURCE_SHA256 = linkedMapOf(
            "external/editor/core/src/main/kotlin/com/codynex/editor/EditorModel.kt" to
                "c1d1ac41bbceb3a046c7bfc1fe70a63ecc56ec5186bbe5440a31eb00d1f71d32",
            "external/editor/core/src/main/kotlin/com/codynex/editor/EditorPorts.kt" to
                "430810cfdbeca2f945548603790661720aab391e9e04b3367f374b16c1b0a5c6",
            "external/editor/core/src/main/kotlin/com/codynex/editor/CodynexEditorController.kt" to
                "d545e3802b300af446bbce56948b10a0ac7b5c00c04c118caa84a93f38e11b45",
            "external/editor/app/src/main/java/com/codynex/editorapp/FileWorkspacePort.kt" to
                "b89c30344a2b7a0aa48c5956030cdfeaae363bf02d3bd705051e9e88bb34f592",
            "external/editor/app/src/main/java/com/codynex/editorapp/BootstrapArtifacts.kt" to
                "149013f3f83534199d22fd377ab02e84875b76c15bffe54aca9aa452dc3d6051",
            "external/editor/app/src/main/java/com/codynex/editorapp/Source0SelfHostToolchainPort.kt" to
                "2f2ff877393bb0cbb9e5a5c9d13cf28d082eaa36d560a45ff5f6587c605ea6af",
            "external/editor/app/src/main/java/com/codynex/editorapp/Vm1Bridge.kt" to
                "b844c767e81366f3464988eab060f28ccc5c098cf98a877e71704cc3b2c446bb",
            "external/editor/app/src/main/java/com/codynex/editorapp/CodynexApkBuilder.kt" to
                "8e231086c097ecc0bb8dbc60534509eaafa0c7cc6556def65f4fa12f7dc01f1c",
            "external/editor/app/src/main/java/com/codynex/editorapp/CodynexApkV2Signer.kt" to
                "3b564713851ad4e393519aee07301760993866875742a5bb5a273bf3dedd5f76",
            "external/editor/app/src/main/java/com/codynex/apphost/CodynexAppActivity.kt" to
                "ab27d72241098fa6b09d2c26c48a7e1b129d95a500c386a13b96836b54209f28",
            "external/editor/app/src/main/java/com/codynex/editorapp/MainActivity.kt" to
                "0537523cd021aa7ba615adfd7bf70721f847a93139f9698d8591c740aa95eed5",
            "external/editor/app/src/main/cpp/editor_vm_bridge.cpp" to
                "b609b300e6f27d90c9d9b4217d7cc92f4d97ca17a2b52815c5c358f0dbb9e620",
            "external/editor/app/build.gradle.kts" to
                "e529a5182ab3b1ae42aacb621b8eb4d99f4881ef43d1e251f6f9ad559401c0a3",
            "external/editor/app/src/main/AndroidManifest.xml" to
                "5eab82c9663ec4e3b6db0e1c9831422ac73fe7460ede385c36e747d90f3bad9f",
            "external/editor/settings.gradle.kts" to
                "9a258efd9b28084a1655568c96de7fb3f35a6e58bd90785e5d9f1028b567d6ce",
            "external/editor/build.gradle.kts" to
                "230ecfeab072a48248b0012ffe5d2159772e9b2162d9f7c7229674f646478bc8",
            "external/editor/core/build.gradle.kts" to
                "ac7cfb56bb6a67678000e7cd78fbff58524cc867351bafdaf3277b23521d0d30"
        )
        private const val RIFTPP_EDITOR_PROJECT = "standalone/editor/android"
        private const val RIFTPP_EDITOR_PACKAGE = "com.riftpp.editor"
        private const val RIFTPP_EDITOR_ACTIVITY = "com.riftpp.editor.MainActivity"
        private const val RIFTPP_EDITOR_BRIDGE_SERVICE =
            "com.riftpp.editor.RiftppEditorBridgeService"
        private const val RIFTPP_EDITOR_LIBRARY_NAME = "riftpp_editor_bridge"
        private const val RIFTPP_EDITOR_LIBRARY_FILE = "libriftpp_editor_bridge.so"
        private const val RIFTPP_EDITOR_HOST_APK_ENTRY =
            "lib/armeabi-v7a/libriftpp_editor_bridge.so"
        private const val RIFTPP_EDITOR_VERSION_NAME = "0.4.0-devbridge"
        private const val RIFTPP_EDITOR_MAX_HOST_BYTES = 4L * 1024L * 1024L
        private const val RIFTPP_EDITOR_COMPILER =
            "s3/frozen/compiler.arm32.native.hex"
        private const val RIFTPP_EDITOR_COMPILER_BYTES = 33057
        private const val RIFTPP_EDITOR_COMPILER_SHA256 =
            "950e4ad52cb57b73c1348282903529488619373921c1bd37b73f6ddfa93b103a"
        private const val RIFTPP_EDITOR_APP2_FRONTEND =
            "standalone/frontend/frontend.app2.arm32.r4.hex"
        private const val RIFTPP_EDITOR_APP2_FRONTEND_BYTES = 13277
        private const val RIFTPP_EDITOR_APP2_FRONTEND_SHA256 =
            "d6b50a4cd6b17c9316e4347392994597e0d931dc1f9abaca5073b6910bd9a45c"
        private const val RIFTPP_EDITOR_PROJECT_FRONTEND =
            "standalone/frontend/frontend.project1.arm32.r4.hex"
        private const val RIFTPP_EDITOR_PROJECT_FRONTEND_BYTES = 17323
        private const val RIFTPP_EDITOR_PROJECT_FRONTEND_SHA256 =
            "cb99920a844a453930366552f5b3804d140fadf93ab34132c19de4b4a8920dde"
        private const val RIFTPP_EDITOR_RUNTIME =
            "standalone/runtime/runtime.app2.arm32.r4.hex"
        private const val RIFTPP_EDITOR_RUNTIME_BYTES = 4131
        private const val RIFTPP_EDITOR_RUNTIME_SHA256 =
            "11ee093a1082a90fed184a454056e08958e10a422a3f81ce1fc0ca89b3c680c0"
        private const val RIFTPP_EDITOR_SAMPLE_MAIN =
            "standalone/app/examples/notepad/src/main.riftpp"
        private const val RIFTPP_EDITOR_SAMPLE_MAIN_BYTES = 28
        private const val RIFTPP_EDITOR_SAMPLE_MAIN_SHA256 =
            "780cc411ee9092f369ab59bab7f8a8924861a65aba05df357282cc4f8ffce36b"
        private const val RIFTPP_EDITOR_SAMPLE_UI =
            "standalone/app/examples/notepad/src/ui.riftpp"
        private const val RIFTPP_EDITOR_SAMPLE_UI_BYTES = 144
        private const val RIFTPP_EDITOR_SAMPLE_UI_SHA256 =
            "174afe28b39eead9ed98b66a05123dedbda5f7e9c9f479e50546ee1988f8eece"
        private const val RIFTPP_EDITOR_SAMPLE_MANIFEST =
            "standalone/app/examples/notepad/app.rift.json"
        private const val RIFTPP_EDITOR_SAMPLE_MANIFEST_BYTES = 232
        private const val RIFTPP_EDITOR_SAMPLE_MANIFEST_SHA256 =
            "3a52b8c48b8691cf3d9707ccb312b2733083f97040bf6b19d95e87dae229d8cf"
        private val RIFTPP_EDITOR_SOURCE_SHA256 = linkedMapOf(
            "standalone/editor/android/app/src/main/java/com/riftpp/editor/MainActivity.kt" to
                "4ff321b5906b74f9977eca9ffb3c0a340df94633ab6278fb301897aba6b9a39e",
            "standalone/editor/android/app/src/main/java/com/riftpp/editor/RiftppEditorBridgeService.kt" to
                "fa00e2c4e48aecdf8878773c957c686c6eead46276807310a3d4b5ac309de98f",
            "standalone/editor/android/app/src/main/java/com/riftpp/editor/RiftppPipeline.kt" to
                "621b2c215ec4da4fe3494c6e8c6ad18a060d1b219d4293050b4c9358b01c28d4",
            "standalone/editor/android/app/src/main/java/com/riftpp/editor/RiftppWorkspace.kt" to
                "a733b06894bcd045e5192f45c0a42072dec27e99f7cf743cfe4cedc4c57e3d19",
            "standalone/editor/android/app/src/main/java/com/riftpp/editor/RiftppProject.kt" to
                "55baae5229b5aba070400619f86a8138f876ade6027e72e58c9bf6da8737f743",
            "standalone/editor/android/app/src/main/java/com/riftpp/editor/RiftppNativeBridge.kt" to
                "d4150a6c675ba7df474a3bd52f696d55d851024816c27e98bc2a8788b3bea31d",
            "standalone/editor/android/app/src/main/java/com/riftpp/editor/HexAssets.kt" to
                "1e71c62ef330ca81b39598c3d6c652b0b7f56b99c9bd97ff133f834d7d229c90",
            "standalone/editor/android/app/src/main/java/com/riftpp/editor/RiftppUi.kt" to
                "da115257bac2cc32fccf82f506fb8fbf47a4bc40dcfa1066266c66d6b2c9e003",
            "standalone/editor/android/app/src/main/java/com/riftpp/editor/RiftppApkBuilder.kt" to
                "c9c6d0571ae845648d64cb3fe903661347c1a8dbfc1a22ee5f3eaee786dc189b",
            "standalone/editor/android/app/src/main/java/com/riftpp/editor/RiftppNativeElfPreflight.kt" to
                "34c72be5d28ea32a95dd7174107c93317552c86cf19ea6c7f71859a8cae9dab8",
            "standalone/editor/android/app/src/main/java/com/riftpp/editor/RiftppApkV2Signer.kt" to
                "ad403db6f4635816e32fbf02370ddea4e52facd9b3be0782f56e71ae8646ea9a",
            "standalone/editor/android/app/src/main/java/com/riftpp/apphost/RiftppAppActivity.kt" to
                "48fe3ec0405eb90176dc11473315ac00e48a875b1df278679bfc3b32c4a7f556",
            "standalone/editor/android/app/src/main/cpp/riftpp_editor_bridge.cpp" to
                "ff1bd4279c56422cfe5fe355c4ada18ba0eae6babb8afa79fd7abc753b51e778",
            "standalone/editor/android/app/src/main/AndroidManifest.xml" to
                "5148a5bd658b2aa716456999c6143c7c34041db2f8da46c6c512b6673af87a72",
            "standalone/editor/android/app/build.gradle.kts" to
                "d71353c7fbe60c3e4f77bdb1166c251d2bfd8afdac4db9ce365506eba5b4ce78"
        )

        private const val CODYNEX_APPHOST_PROJECT = "external/apphost"
        private const val CODYNEX_APP_PACKAGE = "com.codynex.notepad"
        private const val CODYNEX_APP_ACTIVITY =
            "com.codynex.apphost.CodynexAppActivity"
        private const val CODYNEX_APP_VERSION_NAME = "0.1.0-live-proof"
        private const val CODYNEX_APP_LIBRARY_FILE =
            "libcodynex_editor_vm.so"
        private const val CODYNEX_APP_PROGRAM_ASSET = "program.vm1"
        private const val CODYNEX_APP_MAX_SOURCE_BYTES = 256 * 1024
        private const val CODYNEX_APP_MAX_PROGRAM_BYTES = 64 * 1024
        private val CODYNEX_APPHOST_SOURCE_SHA256 = linkedMapOf(
            "external/apphost/settings.gradle.kts" to
                "4e6be639ed8868ad14ad06457de70420df60d9ca484cb454a74d17bc11278a31",
            "external/apphost/build.gradle.kts" to
                "f635b381665f7dd6c45df396cae81ad4812cc403d904f10f2909fd174575fec3",
            "external/apphost/app/build.gradle.kts" to
                "9c7fe53cce40f0ac93fd17164f5b1d6b1c91b0c649d4187e732cc5590a775de4",
            "external/apphost/app/src/main/AndroidManifest.xml" to
                "e092bd010b9f52dcfe1fffca2ca08098689816d7a88d5a1362d97646cc9350ea",
            "external/apphost/app/src/main/java/com/codynex/apphost/CodynexAppActivity.kt" to
                "ab27d72241098fa6b09d2c26c48a7e1b129d95a500c386a13b96836b54209f28"
        )
        private const val XML_NO_INDEX = -1
        private const val XML_STRING_POOL_TYPE = 0x0001
        private const val XML_TYPE = 0x0003
        private const val XML_START_NAMESPACE_TYPE = 0x0100
        private const val XML_END_NAMESPACE_TYPE = 0x0101
        private const val XML_START_ELEMENT_TYPE = 0x0102
        private const val XML_END_ELEMENT_TYPE = 0x0103
        private const val XML_RESOURCE_MAP_TYPE = 0x0180
        private const val XML_UTF8_FLAG = 0x00000100
        private const val XML_VALUE_STRING = 0x03
        private const val XML_VALUE_INT_DEC = 0x10
        private const val XML_VALUE_INT_BOOLEAN = 0x12
        private val RIFTPP_V0_MANIFEST_STRINGS = listOf(
            "name", "hasCode", "exported", "value", "minSdkVersion", "versionCode", "versionName", "targetSdkVersion",
            "android", "http://schemas.android.com/apk/res/android", "manifest", "package", "com.riftpp.nativeproof", "1",
            "0.1.0-native-proof", "uses-sdk", "26", "36", "application", "false", "activity",
            "android.app.NativeActivity", "true", "meta-data", "android.app.lib_name", "riftpp_nativeproof",
            "intent-filter", "action", "android.intent.action.MAIN", "category", "android.intent.category.LAUNCHER"
        )
        private val RIFTPP_V0_MANIFEST_RESOURCE_IDS = intArrayOf(
            0x01010003, 0x0101000c, 0x01010010, 0x01010024,
            0x0101020c, 0x0101021b, 0x0101021c, 0x01010270
        )
        private val MC0_MANIFEST_STRINGS = listOf(
            "name", "hasCode", "exported", "value", "minSdkVersion", "versionCode", "versionName", "targetSdkVersion",
            "android", "http://schemas.android.com/apk/res/android", "manifest", "package", MC0_PACKAGE, "1",
            MC0_VERSION_NAME, "uses-sdk", "26", "36", "application", "false", "activity",
            "android.app.NativeActivity", "true", "meta-data", "android.app.lib_name", MC0_LIBRARY_NAME,
            "intent-filter", "action", "android.intent.action.MAIN", "category", "android.intent.category.LAUNCHER"
        )
        private val MC1A_MANIFEST_STRINGS = listOf(
            "name", "hasCode", "exported", "value", "minSdkVersion", "versionCode", "versionName", "targetSdkVersion",
            "android", "http://schemas.android.com/apk/res/android", "manifest", "package", MC1A_PACKAGE, "1",
            MC1A_VERSION_NAME, "uses-sdk", "26", "36", "application", "false", "activity",
            "android.app.NativeActivity", "true", "meta-data", "android.app.lib_name", MC1A_LIBRARY_NAME,
            "intent-filter", "action", "android.intent.action.MAIN", "category", "android.intent.category.LAUNCHER"
        )
        private val MC1B_MANIFEST_STRINGS = listOf(
            "name", "hasCode", "exported", "value", "minSdkVersion", "versionCode", "versionName", "targetSdkVersion",
            "android", "http://schemas.android.com/apk/res/android", "manifest", "package", MC1B_PACKAGE, "1",
            MC1B_VERSION_NAME, "uses-sdk", "26", "36", "application", "false", "activity",
            "android.app.NativeActivity", "true", "meta-data", "android.app.lib_name", MC1B_LIBRARY_NAME,
            "intent-filter", "action", "android.intent.action.MAIN", "category", "android.intent.category.LAUNCHER"
        )
        private val M2_VM0_MANIFEST_STRINGS = listOf(
            "name", "hasCode", "exported", "value", "minSdkVersion", "versionCode", "versionName", "targetSdkVersion",
            "android", "http://schemas.android.com/apk/res/android", "manifest", "package", M2_VM0_PACKAGE, "1",
            M2_VM0_VERSION_NAME, "uses-sdk", "26", "36", "application", "false", "activity",
            "android.app.NativeActivity", "true", "meta-data", "android.app.lib_name", M2_VM0_LIBRARY_NAME,
            "intent-filter", "action", "android.intent.action.MAIN", "category", "android.intent.category.LAUNCHER"
        )
        private val M2_B_MANIFEST_STRINGS = listOf(
            "name", "hasCode", "exported", "value", "minSdkVersion", "versionCode", "versionName", "targetSdkVersion",
            "android", "http://schemas.android.com/apk/res/android", "manifest", "package", M2_B_PACKAGE, "1",
            M2_B_VERSION_NAME, "uses-sdk", "26", "36", "application", "false", "activity",
            "android.app.NativeActivity", "true", "meta-data", "android.app.lib_name", M2_B_LIBRARY_NAME,
            "intent-filter", "action", "android.intent.action.MAIN", "category", "android.intent.category.LAUNCHER"
        )
        private val MC2_A_MANIFEST_STRINGS = listOf(
            "name", "hasCode", "exported", "value", "minSdkVersion", "versionCode", "versionName", "targetSdkVersion",
            "android", "http://schemas.android.com/apk/res/android", "manifest", "package", MC2_A_PACKAGE, "1",
            MC2_A_VERSION_NAME, "uses-sdk", "26", "36", "application", "false", "activity",
            "android.app.NativeActivity", "true", "meta-data", "android.app.lib_name", MC2_A_LIBRARY_NAME,
            "intent-filter", "action", "android.intent.action.MAIN", "category", "android.intent.category.LAUNCHER"
        )
        private val EDITOR_MANIFEST_STRINGS = listOf(
            "name", "hasCode", "exported", "value", "minSdkVersion", "versionCode", "versionName", "targetSdkVersion",
            "android", "http://schemas.android.com/apk/res/android", "manifest", "package", EDITOR_PACKAGE, "1",
            EDITOR_VERSION_NAME, "uses-sdk", "26", "36", "application", "true", "activity",
            EDITOR_ACTIVITY, "intent-filter", "action", "android.intent.action.MAIN",
            "category", "android.intent.category.LAUNCHER", "queries", "com.riftos.app",
            CODYNEX_APP_PACKAGE, CODYNEX_APP_VERSION_NAME, CODYNEX_APP_ACTIVITY,
            RIFTPP_EDITOR_PACKAGE, RIFTPP_EDITOR_VERSION_NAME, RIFTPP_EDITOR_ACTIVITY,
            "2", "service", RIFTPP_EDITOR_BRIDGE_SERVICE
        )
        private val TARGETS = setOf("arm32", "arm64", "universal")
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        private val SAFE_SEGMENT = Regex("^[A-Za-z0-9._+-]{1,120}$")
        private val DEX_ENTRY = Regex("^classes(?:[2-9]|[1-9][0-9]+)?\\.dex$")
    }

    private val appContext = context.applicationContext
    private val riftRoot = File(appContext.filesDir, "riftfs").apply { mkdirs() }.canonicalFile
    private val workspaceRoot = File(riftRoot, "workspace").apply { mkdirs() }.canonicalFile
    private val runRoot = File(riftRoot, "system/riftbuild/v1/runs").apply { mkdirs() }.canonicalFile
    private val artifactRoot = File(riftRoot, "documents/builds").apply { mkdirs() }.canonicalFile
    private val nativeToolchain = RiftBuildNativeToolchain(appContext, riftRoot, workspaceRoot)
    private val nativeApp = RiftBuildNativeApp(workspaceRoot)
    private val apkSigner = RiftApkV2Signer(appContext)
    private val installer = RiftBuildInstaller(appContext)
    private val codynexRuntime = RiftHeadlessJsRuntime(appContext)

    fun executeShell(args: MutableList<String>, cwd: String): CommandResult {
        val sub = args.removeFirstOrNull()?.lowercase() ?: "doctor"
        val value = when (sub) {
            "help" -> JSONObject()
                .put("schema", "riftbuild-native-help-v1")
                .put("usage", "riftbuild doctor [project] | validate <project> | plan <project> [arm32|arm64|universal] | toolchain-status | toolchain-install-bundled | compile-native <project> [arm32|arm64|universal] | prepare-native-app <project> | prepare-riftpp-v0 <riftpp-root> [target] | prepare-riftpp-seed0-arm64 <riftpp-root> | prepare-riftpp-app0 <riftpp-root> <app-dir> | prepare-riftpp-editor <riftpp-root> | prepare-codynex-mc0 <codynex-root> | prepare-codynex-mc1a <codynex-root> | prepare-codynex-mc1b <codynex-root> | prepare-codynex-m2-vm0 <codynex-root> | prepare-codynex-m2b <codynex-root> | prepare-codynex-mc2a <codynex-root> | prepare-codynex-editor <codynex-root> | prepare-codynex-app <codynex-root> <source-path> | pack <project> [target] | sign <unsigned-apk> | verify <signed-apk> | install-proof <signed-apk> | install-status | launch-proof | runs [limit] | artifacts [project]")
            "doctor" -> doctor(args.firstOrNull(), cwd)
            "validate" -> validate(args.firstOrNull() ?: error("usage: riftbuild validate <project>"), cwd)
            "plan" -> plan(
                args.firstOrNull() ?: error("usage: riftbuild plan <project> [arm32|arm64|universal]"),
                args.getOrNull(1) ?: "universal",
                cwd
            )
            "toolchain-status" -> nativeToolchain.status()
            "toolchain-install-bundled" -> nativeToolchain.installBundled()
            "compile-native" -> compileNative(
                args.firstOrNull() ?: error("usage: riftbuild compile-native <project> [arm32|arm64|universal]"),
                args.getOrNull(1) ?: "universal",
                cwd
            )
            "prepare-native-app" -> prepareNativeApp(
                args.firstOrNull() ?: error("usage: riftbuild prepare-native-app <project>"),
                cwd
            )
            "prepare-riftpp-v0" -> prepareRiftppV0(
                args.firstOrNull() ?: error("usage: riftbuild prepare-riftpp-v0 <riftpp-root> [arm32|arm64|universal]"),
                args.getOrNull(1) ?: "universal",
                cwd
            )
            "prepare-riftpp-seed0-arm64" -> prepareRiftppSeed0Arm64Proof(
                args.firstOrNull() ?: error("usage: riftbuild prepare-riftpp-seed0-arm64 <riftpp-root>"),
                cwd
            )
            "prepare-riftpp-app0" -> prepareRiftppApp0(
                args.firstOrNull() ?: error("usage: riftbuild prepare-riftpp-app0 <riftpp-root> <app-dir>"),
                args.getOrNull(1) ?: error("usage: riftbuild prepare-riftpp-app0 <riftpp-root> <app-dir>"),
                cwd
            )
            "prepare-riftpp-editor" -> prepareRiftppEditor(
                args.firstOrNull() ?: error(
                    "usage: riftbuild prepare-riftpp-editor <riftpp-root>"
                ),
                cwd
            )
            "prepare-codynex-mc0" -> prepareCodynexMc0(
                args.firstOrNull() ?: error("usage: riftbuild prepare-codynex-mc0 <codynex-root>"),
                cwd
            )
            "prepare-codynex-mc1a" -> prepareCodynexMc1a(
                args.firstOrNull() ?: error("usage: riftbuild prepare-codynex-mc1a <codynex-root>"),
                cwd
            )
            "prepare-codynex-mc1b" -> prepareCodynexMc1b(
                args.firstOrNull() ?: error("usage: riftbuild prepare-codynex-mc1b <codynex-root>"),
                cwd
            )
            "prepare-codynex-m2-vm0" -> prepareCodynexM2Vm0(
                args.firstOrNull() ?: error("usage: riftbuild prepare-codynex-m2-vm0 <codynex-root>"),
                cwd
            )
            "prepare-codynex-m2b" -> prepareCodynexM2B(
                args.firstOrNull() ?: error("usage: riftbuild prepare-codynex-m2b <codynex-root>"),
                cwd
            )
            "prepare-codynex-mc2a" -> prepareCodynexMc2A(
                args.firstOrNull() ?: error("usage: riftbuild prepare-codynex-mc2a <codynex-root>"),
                cwd
            )
            "prepare-codynex-editor" -> prepareCodynexEditor(
                args.firstOrNull() ?: error("usage: riftbuild prepare-codynex-editor <codynex-root>"),
                cwd
            )
            "prepare-codynex-app" -> prepareCodynexApp(
                args.firstOrNull() ?: error(
                    "usage: riftbuild prepare-codynex-app <codynex-root> <source-path>"
                ),
                args.getOrNull(1) ?: error(
                    "usage: riftbuild prepare-codynex-app <codynex-root> <source-path>"
                ),
                cwd
            )
            "pack" -> pack(
                args.firstOrNull() ?: error("usage: riftbuild pack <project> [arm32|arm64|universal]"),
                args.getOrNull(1) ?: "universal",
                cwd
            )
            "sign" -> signArtifact(args.firstOrNull() ?: error("usage: riftbuild sign <unsigned-apk>"))
            "verify" -> verifyArtifact(args.firstOrNull() ?: error("usage: riftbuild verify <signed-apk>"))
            "install-proof" -> installProof(args.firstOrNull() ?: error("usage: riftbuild install-proof <signed-apk>"))
            "install-status" -> installer.status()
            "launch-proof" -> installer.launchProof()
            "runs" -> JSONObject().put("schema", "riftbuild-runs-v1").put("runs", runs(args.firstOrNull()?.toIntOrNull() ?: 20))
            "artifacts" -> JSONObject().put("schema", "riftbuild-artifacts-v1").put("artifacts", artifacts(args.firstOrNull(), cwd))
            else -> error("unknown riftbuild command: " + sub)
        }
        return CommandResult(value.toString(2), value)
    }

    fun doctor(project: String? = null, cwd: String = "/D:/Workspace"): JSONObject {
        val projectValue = project?.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { validate(raw, cwd) }.getOrElse {
                JSONObject().put("project", raw).put("sourceReady", false).put("error", it.message ?: it.javaClass.simpleName)
            }
        }
        val packReady = projectValue?.optBoolean("sourceReady", false) == true &&
            projectValue.optBoolean("preparedPackageReady", false)
        val toolchain = nativeToolchain.status()
        val compileReady = toolchain.optBoolean("ready", false)
        val blockers = JSONArray()
        toolchain.optJSONArray("blockers")?.let { values ->
            for (i in 0 until values.length()) blockers.put("native-compile: " + values.getString(i))
        }
        blockers.put("package-install: Android may still require Allow from this source + user confirmation")
        return JSONObject()
            .put("schema", "riftbuild-native-doctor-v1")
            .put("available", true)
            .put("nativeExecutor", true)
            .put("structuredCompilerProcessExecution", true)
            .put("rawShellExecution", false)
            .put("downloadedToolchainsAllowed", true)
            .put("workspaceOnly", true)
            .put("sourceValidationReady", true)
            .put("preparedArtifactPackagerReady", true)
            .put("packReady", packReady)
            .put("compileReady", compileReady)
            .put("toolchain", toolchain)
            .put("signingReady", true)
            .put("verificationReady", true)
            .put("installOwnerReady", true)
            .put("installReady", appContext.packageManager.canRequestPackageInstalls())
            .put("ready", compileReady && (projectValue == null || packReady))
            .put("project", projectValue ?: JSONObject.NULL)
            .put("artifactRoot", "/D:/Builds")
            .put("blockers", blockers)
    }

    fun compileNative(project: String, target: String = "universal", cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)
        require(ref.file.isDirectory) { "Build project is not a directory: " + ref.display }
        return nativeToolchain.compile(ref.file, normalizeTarget(target))
            .put("project", ref.display)
    }

    fun prepareNativeApp(project: String, cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)
        require(ref.file.isDirectory) { "Build project is not a directory: " + ref.display }
        return nativeApp.prepare(ref.file).put("project", ref.display)
    }

    fun validate(project: String, cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)
        require(ref.file.isDirectory) { "Build project is not a directory: " + ref.display }

        var files = 0
        var bytes = 0L
        ref.file.walkTopDown().forEach { file ->
            RiftDeadline.check("RiftBuild project scan")
            require(confinedTo(ref.file, file)) { "Build project escaped project root" }
            if (!file.isFile) return@forEach
            files += 1
            require(files <= MAX_PROJECT_FILES) { "Build project exceeds file-count limit" }
            bytes += file.length()
            require(bytes <= MAX_PROJECT_BYTES) { "Build project exceeds byte limit" }
        }

        val checks = JSONArray()
        fun check(id: String, ok: Boolean, detail: String) {
            checks.put(JSONObject().put("id", id).put("ok", ok).put("detail", detail))
        }

        val settings = firstExisting(ref.file, "settings.gradle.kts", "settings.gradle")
        val rootGradle = firstExisting(ref.file, "build.gradle.kts", "build.gradle")
        val appGradle = firstExisting(ref.file, "app/build.gradle.kts", "app/build.gradle")
        val manifest = File(ref.file, "app/src/main/AndroidManifest.xml")

        check("settings", settings != null, settings?.name ?: "missing settings.gradle(.kts)")
        check("root-gradle", rootGradle != null, rootGradle?.name ?: "missing build.gradle(.kts)")
        check("app-gradle", appGradle != null, appGradle?.relativeTo(ref.file)?.invariantSeparatorsPath ?: "missing app/build.gradle(.kts)")
        check("manifest", manifest.isFile, if (manifest.isFile) "app/src/main/AndroidManifest.xml" else "missing AndroidManifest.xml")

        var nativeActivity = false
        var nativeLibraryName = ""
        var activityName = ""
        var requiresDex = false
        if (manifest.isFile) {
            val text = readTextBounded(manifest)
            activityName = Regex("""<activity\b[^>]*android:name\s*=\s*["']([^"']+)["']""")
                .find(text)?.groupValues?.getOrNull(1).orEmpty()
            nativeActivity =
                activityName == "android.app.NativeActivity" ||
                    text.contains("android.app.NativeActivity")
            requiresDex = activityName.isNotBlank() && !nativeActivity
            nativeLibraryName = Regex("""android\.app\.lib_name[\s\S]*?android:value\s*=\s*["']([^"']+)["']""")
                .find(text)?.groupValues?.getOrNull(1).orEmpty()
            check(
                "activity",
                activityName.isNotBlank(),
                if (activityName.isBlank()) "launch activity missing" else activityName
            )
            if (nativeActivity) {
                check(
                    "native-library-name",
                    nativeLibraryName.isNotBlank(),
                    if (nativeLibraryName.isBlank()) "android.app.lib_name missing" else nativeLibraryName
                )
            }
        }

        val arm64Source = File(ref.file, "app/src/main/cpp/generated/arm64-v8a/rift_ir_entry.S")
        val arm32Source = File(ref.file, "app/src/main/cpp/generated/armeabi-v7a/rift_ir_entry.S")
        val riftNativeProof = arm64Source.isFile || arm32Source.isFile
        if (riftNativeProof) {
            check("rift-arm64-source", arm64Source.isFile, if (arm64Source.isFile) sha256(arm64Source) else "missing arm64 source")
            check("rift-arm32-source", arm32Source.isFile, if (arm32Source.isFile) sha256(arm32Source) else "missing arm32 source")
            val gradleText = appGradle?.let(::readTextBounded).orEmpty()
            val dualAbi = gradleText.contains("armeabi-v7a") && gradleText.contains("arm64-v8a")
            check("dual-abi-declaration", dualAbi, if (dualAbi) "arm32 + arm64 declared" else "dual ABI filters missing")
        }

        val sourceReady = (0 until checks.length()).all { checks.getJSONObject(it).optBoolean("ok") }
        val prepared = inspectPrepared(ref, "universal", requiresDex)
        return JSONObject()
            .put("schema", "riftbuild-native-validation-v1")
            .put("project", ref.display)
            .put("projectName", ref.file.name)
            .put("projectSha256", treeSha256(ref.file))
            .put("files", files)
            .put("bytes", bytes)
            .put("sourceReady", sourceReady)
            .put("androidGradleProject", settings != null && rootGradle != null && appGradle != null && manifest.isFile)
            .put("nativeActivity", nativeActivity)
            .put("nativeLibraryName", nativeLibraryName)
            .put("activityName", activityName)
            .put("requiresDex", requiresDex)
            .put("riftNativeProof", riftNativeProof)
            .put("checks", checks)
            .put("preparedPackageReady", prepared.optBoolean("ready"))
            .put("prepared", prepared)
    }

    fun plan(project: String, target: String = "universal", cwd: String = "/D:/Workspace"): JSONObject {
        val normalizedTarget = normalizeTarget(target)
        val validation = validate(project, cwd)
        val ref = resolveProject(project, cwd)
        val requiresDex = validation.optBoolean("requiresDex", false)
        val prepared = inspectPrepared(ref, normalizedTarget, requiresDex)
        val sourceReady = validation.optBoolean("sourceReady")
        val packReady = sourceReady && prepared.optBoolean("ready")
        val nativeLibraryName = validation.optString("nativeLibraryName")
        val prepareHint = when {
            ref.display.endsWith("/" + CODYNEX_APPHOST_PROJECT) ->
                "run prepare-codynex-app from the Codynex project root"
            ref.display.endsWith("/" + EDITOR_PROJECT) ->
                "run prepare-codynex-editor from the Codynex project root"
            nativeLibraryName == MC0_LIBRARY_NAME ->
                "run prepare-codynex-mc0 from the Codynex project root"
            nativeLibraryName == "riftpp_nativeproof" ->
                "run prepare-riftpp-v0 for the current Rift++ V0 proof"
            else -> "materialize a bounded prepared Android package"
        }
        return JSONObject()
            .put("format", "riftbuild-native-plan-v1")
            .put("project", ref.display)
            .put("projectSha256", validation.optString("projectSha256"))
            .put("target", normalizedTarget)
            .put("sourceReady", sourceReady)
            .put("packReady", packReady)
            .put("fullBuildReady", false)
            .put("prepared", prepared)
            .put("artifactRoot", artifactProjectDisplay(ref))
            .put("stages", JSONArray()
                .put(stage("source-validation", if (sourceReady) "ready" else "blocked", if (sourceReady) null else "source validation failed"))
                .put(stage(
                    "prepared-native-proof",
                    if (hasPreparedNative(prepared, normalizedTarget)) "prepared" else "blocked",
                    if (hasPreparedNative(prepared, normalizedTarget)) null else prepareHint
                ))
                .put(stage("apk-package", if (packReady) "ready" else "blocked", if (packReady) null else "prepared binary Android artifacts incomplete"))
                .put(stage("apk-signing", "ready-v2", null))
                .put(stage("artifact-verification", "ready-v2", null))
                .put(stage("package-install", "user-confirmed", "restricted to RiftBuild proof-package allowlist; Android user confirmation may be required")))
    }


    fun prepare(args: JSONObject, cwd: String = "/D:/Workspace"): JSONObject {
        require(!args.has("command") && !args.has("shell") && !args.has("exec")) { "RiftBuild does not accept raw commands" }
        val kind = args.optString("kind", "riftpp-v0")
        val project = args.optString("project").ifBlank { args.optString("projectPath") }
        require(project.isNotBlank()) { "build.prepare requires a project root" }
        return when (kind) {
            "riftpp-v0" -> prepareRiftppV0(project, args.optString("target", "universal"), cwd)
            "riftpp-app0" -> prepareRiftppApp0(
                project,
                args.optString("appDir").ifBlank { error("build.prepare kind riftpp-app0 requires appDir") },
                cwd
            )
            "codynex-mc0" -> prepareCodynexMc0(project, cwd)
            "codynex-editor" -> prepareCodynexEditor(project, cwd)
            "codynex-app" -> prepareCodynexApp(
                project,
                args.optString("source").ifBlank {
                    error("build.prepare kind codynex-app requires source")
                },
                cwd
            )
            else -> error(
                "build.prepare kind must be riftpp-v0, riftpp-app0, codynex-mc0, codynex-editor, or codynex-app"
            )
        }
    }

    @Synchronized
    fun prepareRiftppV0(project: String, target: String = "universal", cwd: String = "/D:/Workspace"): JSONObject {
        val normalizedTarget = normalizeTarget(target)
        val ref = resolveProject(project, cwd)

        verifyProjectSource(ref, RIFTPP_V0_MANIFEST_SOURCE, RIFTPP_V0_MANIFEST_SOURCE_SHA)
        verifyProjectSource(ref, RIFTPP_V0_APP_GRADLE, RIFTPP_V0_APP_GRADLE_SHA)
        val binaryManifest = buildRiftppV0BinaryManifest()

        val bridgeFile = projectFile(ref, RIFTPP_V0_BRIDGE)
        require(bridgeFile.isFile) { "Rift++ direct-ELF V0 bridge artifact is missing" }
        val bridge = JSONObject(readTextBounded(bridgeFile))
        require(bridge.optString("schema") == RIFTPP_V0_SCHEMA) { "Unsupported Rift++ direct-ELF V0 bridge schema" }

        val writer = bridge.optJSONObject("writer") ?: error("Rift++ V0 bridge writer identity is missing")
        require(writer.optString("path") == RIFTPP_V0_WRITER) { "Rift++ V0 bridge writer path drift" }
        verifyProjectSource(ref, writer.optString("path"), writer.optString("sha256"))

        val exports = bridge.optJSONObject("exports") ?: error("Rift++ V0 bridge exports are missing")
        val selected = when (normalizedTarget) {
            "arm64" -> listOf(readRiftppV0Image(ref, exports, "aarch64", "arm64-v8a", 2, 183))
            "arm32" -> listOf(readRiftppV0Image(ref, exports, "armv7", "armeabi-v7a", 1, 40))
            else -> listOf(
                readRiftppV0Image(ref, exports, "aarch64", "arm64-v8a", 2, 183),
                readRiftppV0Image(ref, exports, "armv7", "armeabi-v7a", 1, 40)
            )
        }

        val apkProject = projectFile(ref, RIFTPP_V0_APK_PROJECT)
        require(apkProject.isDirectory) { "Rift++ apk-proof project is missing" }
        val buildRoot = File(apkProject, "build/riftbuild").canonicalFile
        require(confinedTo(apkProject, buildRoot)) { "RiftBuild V0 build root escaped apk-proof" }
        val preparedRoot = File(buildRoot, "prepared").canonicalFile
        val libRoot = File(preparedRoot, "lib").canonicalFile
        require(confinedTo(buildRoot, preparedRoot)) { "RiftBuild prepared root escaped build/riftbuild" }
        require(confinedTo(buildRoot, libRoot)) { "RiftBuild prepared library root escaped build/riftbuild" }
        require(preparedRoot.mkdirs() || preparedRoot.isDirectory) { "Could not create prepared package root" }

        val manifestOutput = File(preparedRoot, "AndroidManifest.xml").canonicalFile
        require(confinedTo(preparedRoot, manifestOutput)) { "RiftBuild binary manifest escaped prepared root" }
        atomicWrite(manifestOutput, binaryManifest)
        require(manifestOutput.length() == RIFTPP_V0_BINARY_MANIFEST_BYTES.toLong()) { "Materialized binary manifest byte count drift" }
        require(sha256(manifestOutput) == RIFTPP_V0_BINARY_MANIFEST_SHA) { "Materialized binary manifest SHA-256 drift" }
        require(isBinaryAndroidManifest(manifestOutput)) { "Materialized AndroidManifest.xml failed binary XML validation" }

        if (libRoot.exists()) require(deleteTreeBounded(libRoot, MAX_PROJECT_FILES)) { "Could not clear stale prepared native libraries" }
        require(libRoot.mkdirs() || libRoot.isDirectory) { "Could not create prepared native library root" }

        val outputs = JSONArray()
        for (image in selected) {
            val abiRoot = File(libRoot, image.abi).canonicalFile
            require(confinedTo(libRoot, abiRoot)) { "RiftBuild ABI output escaped prepared/lib" }
            require(abiRoot.mkdirs() || abiRoot.isDirectory) { "Could not create ABI output directory" }
            val output = File(abiRoot, RIFTPP_V0_LIBRARY).canonicalFile
            require(confinedTo(abiRoot, output)) { "RiftBuild ELF output escaped ABI directory" }
            atomicWrite(output, image.bytes)
            require(output.length() == image.bytes.size.toLong()) { "Materialized ELF byte count drift" }
            val materializedSha = sha256(output)
            require(materializedSha == image.rawSha256) { "Materialized ELF SHA-256 drift" }

            outputs.put(JSONObject()
                .put("key", image.key)
                .put("abi", image.abi)
                .put("path", projectDisplay(ref, output))
                .put("bytes", image.bytes.size)
                .put("rawSha256", materializedSha)
                .put("canonicalValueSha256", image.canonicalValueSha256)
                .put("elfClass", image.elfClass)
                .put("machine", image.machine))
        }

        val runId = runId()
        val result = JSONObject()
            .put("format", "riftbuild-riftpp-v0-materialization-v1")
            .put("runId", runId)
            .put("state", "prepared-native")
            .put("project", ref.display)
            .put("androidProject", ref.display + "/" + RIFTPP_V0_APK_PROJECT)
            .put("target", normalizedTarget)
            .put("bridge", ref.display + "/" + RIFTPP_V0_BRIDGE)
            .put("bridgeSha256", sha256(bridgeFile))
            .put("libraryName", RIFTPP_V0_LIBRARY)
            .put("outputs", outputs)
            .put("manifest", JSONObject()
                .put("path", projectDisplay(ref, manifestOutput))
                .put("bytes", manifestOutput.length())
                .put("sha256", sha256(manifestOutput))
                .put("sourceSha256", RIFTPP_V0_MANIFEST_SOURCE_SHA)
                .put("gradleSha256", RIFTPP_V0_APP_GRADLE_SHA))
            .put("manifestReady", true)
            .put("signed", false)
            .put("installableClaimed", false)
            .put("createdAt", System.currentTimeMillis())

        atomicWrite(
            File(buildRoot, "riftpp-v0-materialization.json"),
            result.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(result)
        return result
    }

    @Synchronized
    fun prepareRiftppSeed0Arm64Proof(
        project: String,
        cwd: String = "/D:/Workspace"
    ): JSONObject {
        val compilerRef = resolveProject(project, cwd)
        val compilerFile = projectFile(
            compilerRef,
            RIFTPP_SEED0_ARM64_COMPILER_HEX
        )
        require(compilerFile.isFile) {
            "Rift++ seed0 ARM64 compiler hex is missing"
        }
        require(
            compilerFile.length() ==
                RIFTPP_SEED0_ARM64_COMPILER_HEX_TEXT_BYTES.toLong()
        ) {
            "Rift++ seed0 ARM64 compiler hex text byte count drift"
        }
        require(
            sha256(compilerFile) ==
                RIFTPP_SEED0_ARM64_COMPILER_HEX_TEXT_SHA256
        ) {
            "Rift++ seed0 ARM64 compiler hex text SHA-256 drift"
        }

        val compilerText = readTextBounded(compilerFile)
        require(compilerText.endsWith("\n")) {
            "Rift++ seed0 ARM64 compiler hex must end with one newline"
        }
        val compilerBody = compilerText.dropLast(1)
        require(
            compilerBody.length ==
                RIFTPP_SEED0_ARM64_COMPILER_BYTES * 2 &&
                compilerBody.all { it in '0'..'9' || it in 'a'..'f' }
        ) {
            "Rift++ seed0 ARM64 compiler hex must remain canonical lowercase hex"
        }
        val compilerBytes = decodeHex(compilerBody)
        require(
            compilerBytes.size ==
                RIFTPP_SEED0_ARM64_COMPILER_BYTES
        ) {
            "Rift++ seed0 ARM64 compiler decoded byte count drift"
        }
        require(
            sha256(compilerBytes) ==
                RIFTPP_SEED0_ARM64_COMPILER_RAW_SHA256
        ) {
            "Rift++ seed0 ARM64 compiler raw SHA-256 drift"
        }

        val proofHost = readOwnApkEntry(
            RIFTPP_SEED0_ARM64_HOST_APK_ENTRY,
            RIFTPP_SEED0_ARM64_MAX_HOST_BYTES
        )
        verifyElfImage(proofHost, 2, 183)

        val proofRef = resolveProject(
            RIFTPP_SEED0_ARM64_PROOF_PROJECT,
            cwd
        )
        val validation = validate(proofRef.display, "/D:/Workspace")
        require(validation.optBoolean("sourceReady")) {
            "Rift++ seed0 ARM64 proof project source validation failed"
        }
        require(
            validation.optString("nativeLibraryName") ==
                RIFTPP_SEED0_ARM64_LIBRARY_NAME
        ) {
            "Rift++ seed0 ARM64 proof NativeActivity library declaration drift"
        }

        val sourceManifest = File(
            proofRef.file,
            "app/src/main/AndroidManifest.xml"
        ).canonicalFile
        require(
            confinedTo(proofRef.file, sourceManifest) &&
                sourceManifest.isFile
        ) {
            "Rift++ seed0 ARM64 proof source manifest is missing"
        }
        val sourceManifestText = readTextBounded(sourceManifest)
        require(
            sourceManifestText.contains(
                "package=\"" +
                    RiftBuildInstaller.TARGET_PACKAGE +
                    "\""
            )
        ) {
            "Rift++ seed0 ARM64 proof package drift"
        }
        require(
            sourceManifestText.contains(
                "android:value=\"" +
                    RIFTPP_SEED0_ARM64_LIBRARY_NAME +
                    "\""
            )
        ) {
            "Rift++ seed0 ARM64 proof library drift"
        }

        val binaryManifest = buildNativeActivityBinaryManifest(
            RiftBuildInstaller.TARGET_PACKAGE,
            1,
            RIFTPP_SEED0_ARM64_VERSION_NAME,
            RIFTPP_SEED0_ARM64_LIBRARY_NAME
        )

        val buildRoot = File(
            proofRef.file,
            "build/riftbuild"
        ).canonicalFile
        require(confinedTo(proofRef.file, buildRoot)) {
            "Rift++ seed0 ARM64 proof build root escaped project"
        }
        val preparedRoot = File(
            buildRoot,
            "prepared"
        ).canonicalFile
        require(confinedTo(buildRoot, preparedRoot)) {
            "Rift++ seed0 ARM64 prepared root escaped build/riftbuild"
        }
        if (preparedRoot.exists()) {
            require(
                deleteTreeBounded(
                    preparedRoot,
                    MAX_PROJECT_FILES
                )
            ) {
                "Could not clear stale Rift++ seed0 ARM64 prepared package"
            }
        }

        val arm64LibRoot = File(
            preparedRoot,
            "lib/arm64-v8a"
        ).canonicalFile
        val assetRoot = File(
            preparedRoot,
            "assets"
        ).canonicalFile
        require(
            confinedTo(preparedRoot, arm64LibRoot) &&
                confinedTo(preparedRoot, assetRoot)
        ) {
            "Rift++ seed0 ARM64 prepared path escaped package root"
        }
        require(
            arm64LibRoot.mkdirs() ||
                arm64LibRoot.isDirectory
        ) {
            "Could not create Rift++ seed0 ARM64 library directory"
        }
        require(
            assetRoot.mkdirs() ||
                assetRoot.isDirectory
        ) {
            "Could not create Rift++ seed0 ARM64 asset directory"
        }

        val manifestOutput = File(
            preparedRoot,
            "AndroidManifest.xml"
        ).canonicalFile
        val hostOutput = File(
            arm64LibRoot,
            RIFTPP_SEED0_ARM64_LIBRARY_FILE
        ).canonicalFile
        val compilerOutput = File(
            assetRoot,
            "compiler.bin"
        ).canonicalFile

        atomicWrite(manifestOutput, binaryManifest)
        atomicWrite(hostOutput, proofHost)
        atomicWrite(compilerOutput, compilerBytes)

        require(isBinaryAndroidManifest(manifestOutput)) {
            "Rift++ seed0 ARM64 proof binary manifest failed validation"
        }
        require(
            sha256(hostOutput) ==
                sha256(proofHost)
        ) {
            "Rift++ seed0 ARM64 proof host materialization hash mismatch"
        }
        require(
            sha256(compilerOutput) ==
                RIFTPP_SEED0_ARM64_COMPILER_RAW_SHA256
        ) {
            "Rift++ seed0 ARM64 compiler asset hash mismatch"
        }

        require(buildRoot.mkdirs() || buildRoot.isDirectory) {
            "Could not create Rift++ seed0 ARM64 proof evidence root"
        }
        val runId = runId()
        val result = JSONObject()
            .put(
                "format",
                "riftbuild-riftpp-seed0-arm64-materialization-v1"
            )
            .put("runId", runId)
            .put("state", "prepared-native")
            .put("project", compilerRef.display)
            .put("proofProject", proofRef.display)
            .put("target", "arm64")
            .put(
                "package",
                RiftBuildInstaller.TARGET_PACKAGE
            )
            .put(
                "libraryName",
                RIFTPP_SEED0_ARM64_LIBRARY_NAME
            )
            .put(
                "host",
                JSONObject()
                    .put("abi", "arm64-v8a")
                    .put(
                        "source",
                        "self-apk:" +
                            RIFTPP_SEED0_ARM64_HOST_APK_ENTRY
                    )
                    .put("bytes", proofHost.size)
                    .put("sha256", sha256(proofHost))
                    .put("elfClass", 2)
                    .put("machine", 183)
            )
            .put(
                "compiler",
                JSONObject()
                    .put(
                        "sourcePath",
                        compilerRef.display +
                            "/" +
                            RIFTPP_SEED0_ARM64_COMPILER_HEX
                    )
                    .put("bytes", compilerBytes.size)
                    .put(
                        "rawSha256",
                        sha256(compilerBytes)
                    )
                    .put(
                        "asset",
                        "assets/compiler.bin"
                    )
            )
            .put(
                "proofContract",
                JSONObject()
                    .put("positiveVectors", 5)
                    .put(
                        "generatedPayloadExecutions",
                        5
                    )
                    .put("rejections", 17)
                    .put(
                        "crossHostExpectedBundles",
                        true
                    )
                    .put("hostParsesRiftpp", false)
                    .put(
                        "hostEmitsInstructions",
                        false
                    )
            )
            .put("manifestReady", true)
            .put("signed", false)
            .put("installableClaimed", false)
            .put(
                "createdAt",
                System.currentTimeMillis()
            )

        atomicWrite(
            File(
                buildRoot,
                "riftpp-seed0-arm64-materialization.json"
            ),
            result
                .toString(2)
                .toByteArray(Charsets.UTF_8)
        )
        writeRun(result)
        return result
    }

    @Synchronized
    fun prepareRiftppApp0(
        project: String,
        appDir: String,
        cwd: String = "/D:/Workspace"
    ): JSONObject {
        val ref = resolveProject(project, cwd)
        require(appDir.isNotBlank() && !appDir.startsWith("/") && !appDir.contains("..")) {
            "Rift++ App0 appDir must be a confined project-relative path"
        }

        val appRoot = projectFile(ref, appDir)
        require(appRoot.isDirectory) { "Rift++ App0 directory is missing" }

        val metaFile = File(appRoot, RIFTPP_APP0_META).canonicalFile
        require(confinedTo(appRoot, metaFile) && metaFile.isFile) {
            "Rift++ App0 metadata is missing"
        }
        val meta = JSONObject(readTextBounded(metaFile))
        require(meta.optString("schema") == "riftpp-app/0") {
            "Unsupported Rift++ App0 metadata schema"
        }

        val appName = meta.optString("name")
        val packageName = meta.optString("package")
        val versionCode = meta.optInt("versionCode", 0)
        val versionName = meta.optString("versionName")
        val entry = meta.optString("entry")
        val presentation = meta.optString("presentation")
        val target = meta.optString("target")

        require(appName.isNotBlank() && appName.length <= 80) {
            "Rift++ App0 name must be 1..80 characters"
        }
        require(Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(packageName)) {
            "Rift++ App0 package name is invalid"
        }
        require(packageName == RiftBuildInstaller.RIFTPP_APP0_TARGET_PACKAGE) {
            "Rift++ App0 v0 is restricted to the fixed hello proof package"
        }
        require(versionCode in 1..1_000_000) { "Rift++ App0 versionCode is invalid" }
        require(versionName.isNotBlank() && versionName.length <= 80) {
            "Rift++ App0 versionName is invalid"
        }
        require(entry == "program.tig0") {
            "Rift++ App0 currently requires entry=program.tig0"
        }
        require(presentation == "text") {
            "Rift++ App0 currently supports presentation=text only"
        }
        require(target == "universal") {
            "Rift++ App0 U0 requires target=universal"
        }

        val sourceFile = File(appRoot, entry).canonicalFile
        require(confinedTo(appRoot, sourceFile) && sourceFile.isFile) {
            "Rift++ App0 TIG0 entry source is missing"
        }
        require(sourceFile.length() in 1L..RIFTPP_APP0_MAX_SOURCE_BYTES.toLong()) {
            "Rift++ App0 source exceeds bounded source size"
        }
        val sourceText = readTextBounded(sourceFile)
        val sourceBytes = sourceText.toByteArray(Charsets.UTF_8)
        require(sourceBytes.size <= RIFTPP_APP0_MAX_SOURCE_BYTES) {
            "Rift++ App0 UTF-8 source exceeds bounded source size"
        }

        val compilerFile = projectFile(ref, RIFTPP_APP0_COMPILER_HEX)
        require(compilerFile.isFile) { "Rift++ App0 TIG0 compiler seed is missing" }
        require(compilerFile.length() == RIFTPP_APP0_COMPILER_HEX_BYTES.toLong()) {
            "Rift++ App0 compiler seed text byte count drift"
        }
        require(sha256(compilerFile) == RIFTPP_APP0_COMPILER_HEX_SHA256) {
            "Rift++ App0 compiler seed text SHA-256 drift"
        }
        val compilerHex = readTextBounded(compilerFile)
        require(compilerHex.length == RIFTPP_APP0_COMPILER_HEX_BYTES &&
            compilerHex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Rift++ App0 compiler seed must remain canonical lowercase hex"
        }
        val compiler = decodeHex(compilerHex)
        require(compiler.size == RIFTPP_APP0_COMPILER_BYTES) {
            "Rift++ App0 compiler byte count drift"
        }

        val compileRun = runVm1Bounded(
            compiler,
            sourceBytes,
            RIFTPP_APP0_MAX_PROGRAM_BYTES,
            RIFTPP_APP0_STEP_BUDGET
        )
        require(compileRun.status == 0) {
            "Rift++ App0 TIG0 compiler VM1 status " + compileRun.status
        }
        require(compileRun.result in 1..RIFTPP_APP0_MAX_PROGRAM_BYTES) {
            "Rift++ App0 compiler returned invalid program byte count " + compileRun.result
        }
        val program = compileRun.output.copyOf(compileRun.result)
        require(program.size % 4 == 0) {
            "Rift++ App0 compiler emitted malformed VM1 byte count"
        }

        val requirements = analyzeApp0Vm1(program)
        require(!requirements.requiresSourceBytes) {
            "Rift++ App0 program requires io.source.bytes, which App0 host v0 does not yet implement"
        }
        require(!requirements.requiresScratchBytes) {
            "Rift++ App0 program requires scratch bytes, which App0 host v0 does not yet implement"
        }
        require(requirements.requiresOutputBytes) {
            "Rift++ App0 text presentation requires compiled output-buffer behavior"
        }

        val slices = JSONArray()
            .put(JSONObject()
                .put("id", "core.vm1.arm64")
                .put("reason", "canonical/default universal APK backend"))
            .put(JSONObject()
                .put("id", "core.vm1.arm32")
                .put("reason", "universal APK compatibility backend"))
            .put(JSONObject()
                .put("id", "io.output.bytes")
                .put("reason", "compiled VM1 writes/observes output buffer"))
            .put(JSONObject()
                .put("id", "android.nativeactivity")
                .put("reason", "Android package entry contract"))
            .put(JSONObject()
                .put("id", "android.display.text")
                .put("reason", "presentation=text package contract"))

        val runtimePlan = JSONObject()
            .put("schema", "riftpp-runtime-plan/0")
            .put("app", appName)
            .put("package", packageName)
            .put("target", "universal")
            .put("sourcePath", projectDisplay(ref, sourceFile))
            .put("sourceBytes", sourceBytes.size)
            .put("sourceSha256", sha256(sourceBytes))
            .put("compilerPath", ref.display + "/" + RIFTPP_APP0_COMPILER_HEX)
            .put("compilerSeedSha256", sha256(compilerFile))
            .put("compilerBytes", compiler.size)
            .put("compileSteps", compileRun.steps)
            .put("programBytes", program.size)
            .put("programSha256", sha256(program))
            .put("instructionCount", requirements.instructionCount)
            .put("requirements", JSONObject()
                .put("sourceBytes", requirements.requiresSourceBytes)
                .put("outputBytes", requirements.requiresOutputBytes)
                .put("scratchBytes", requirements.requiresScratchBytes))
            .put("slices", slices)

        val arm64Host = readOwnApkEntry(
            RIFTPP_APP0_ARM64_HOST_APK_ENTRY,
            RIFTPP_APP0_MAX_HOST_BYTES
        )
        val arm32Host = readOwnApkEntry(
            RIFTPP_APP0_ARM32_HOST_APK_ENTRY,
            RIFTPP_APP0_MAX_HOST_BYTES
        )
        verifyElfImage(arm64Host, 2, 183)
        verifyElfImage(arm32Host, 1, 40)

        val apkProject = File(appRoot, RIFTPP_APP0_APK_PROJECT).canonicalFile
        require(confinedTo(appRoot, apkProject) && apkProject.isDirectory) {
            "Rift++ App0 apk-proof project is missing"
        }
        val apkDisplay = projectDisplay(ref, apkProject)
        val sourceValidation = validate(apkDisplay, "/D:/Workspace")
        require(sourceValidation.optBoolean("sourceReady")) {
            "Rift++ App0 apk-proof source validation failed"
        }
        require(sourceValidation.optString("nativeLibraryName") == RIFTPP_APP0_LIBRARY_NAME) {
            "Rift++ App0 NativeActivity library declaration drift"
        }

        val sourceManifest = File(
            apkProject,
            "app/src/main/AndroidManifest.xml"
        ).canonicalFile
        require(confinedTo(apkProject, sourceManifest) && sourceManifest.isFile) {
            "Rift++ App0 source manifest is missing"
        }
        val sourceManifestText = readTextBounded(sourceManifest)
        require(sourceManifestText.contains("package=\"" + packageName + "\"")) {
            "Rift++ App0 source manifest package drift"
        }
        require(sourceManifestText.contains(
            "android:value=\"" + RIFTPP_APP0_LIBRARY_NAME + "\""
        )) {
            "Rift++ App0 source manifest library drift"
        }

        val binaryManifest = buildNativeActivityBinaryManifest(
            packageName,
            versionCode,
            versionName,
            RIFTPP_APP0_LIBRARY_NAME
        )

        val buildRoot = File(apkProject, "build/riftbuild").canonicalFile
        require(confinedTo(apkProject, buildRoot)) {
            "Rift++ App0 build root escaped apk-proof"
        }
        val preparedRoot = File(buildRoot, "prepared").canonicalFile
        require(confinedTo(buildRoot, preparedRoot)) {
            "Rift++ App0 prepared root escaped build/riftbuild"
        }
        if (preparedRoot.exists()) {
            require(deleteTreeBounded(preparedRoot, MAX_PROJECT_FILES)) {
                "Could not clear stale Rift++ App0 prepared package"
            }
        }

        val arm64LibRoot = File(preparedRoot, "lib/arm64-v8a").canonicalFile
        val arm32LibRoot = File(preparedRoot, "lib/armeabi-v7a").canonicalFile
        val assetRoot = File(preparedRoot, "assets").canonicalFile
        require(
            confinedTo(preparedRoot, arm64LibRoot) &&
                confinedTo(preparedRoot, arm32LibRoot) &&
                confinedTo(preparedRoot, assetRoot)
        ) {
            "Rift++ App0 U0 prepared path escaped package root"
        }
        require(arm64LibRoot.mkdirs() || arm64LibRoot.isDirectory) {
            "Could not create Rift++ App0 ARM64 library directory"
        }
        require(arm32LibRoot.mkdirs() || arm32LibRoot.isDirectory) {
            "Could not create Rift++ App0 ARM32 library directory"
        }
        require(assetRoot.mkdirs() || assetRoot.isDirectory) {
            "Could not create Rift++ App0 asset directory"
        }

        val manifestOutput = File(preparedRoot, "AndroidManifest.xml").canonicalFile
        val arm64HostOutput = File(arm64LibRoot, RIFTPP_APP0_LIBRARY_FILE).canonicalFile
        val arm32HostOutput = File(arm32LibRoot, RIFTPP_APP0_LIBRARY_FILE).canonicalFile
        val programOutput = File(assetRoot, "program.bin").canonicalFile

        atomicWrite(manifestOutput, binaryManifest)
        atomicWrite(arm64HostOutput, arm64Host)
        atomicWrite(arm32HostOutput, arm32Host)
        atomicWrite(programOutput, program)

        require(isBinaryAndroidManifest(manifestOutput)) {
            "Rift++ App0 binary AndroidManifest.xml failed validation"
        }
        require(sha256(arm64HostOutput) == sha256(arm64Host)) {
            "Rift++ App0 ARM64 host materialization hash mismatch"
        }
        require(sha256(arm32HostOutput) == sha256(arm32Host)) {
            "Rift++ App0 ARM32 host materialization hash mismatch"
        }
        require(sha256(programOutput) == sha256(program)) {
            "Rift++ App0 program materialization hash mismatch"
        }

        require(buildRoot.mkdirs() || buildRoot.isDirectory) {
            "Could not create Rift++ App0 build evidence root"
        }
        val runtimePlanFile = File(buildRoot, "riftpp-app0-runtime-plan.json").canonicalFile
        atomicWrite(runtimePlanFile, runtimePlan.toString(2).toByteArray(Charsets.UTF_8))

        val runId = runId()
        val result = JSONObject()
            .put("format", "riftbuild-riftpp-app0-materialization-v1")
            .put("runId", runId)
            .put("state", "prepared-native")
            .put("project", ref.display)
            .put("appRoot", projectDisplay(ref, appRoot))
            .put("androidProject", apkDisplay)
            .put("target", "universal")
            .put("package", packageName)
            .put("libraryName", RIFTPP_APP0_LIBRARY_NAME)
            .put("hosts", JSONArray()
                .put(JSONObject()
                    .put("abi", "arm64-v8a")
                    .put("role", "canonical-default")
                    .put("source", "self-apk:" + RIFTPP_APP0_ARM64_HOST_APK_ENTRY)
                    .put("bytes", arm64Host.size)
                    .put("sha256", sha256(arm64Host)))
                .put(JSONObject()
                    .put("abi", "armeabi-v7a")
                    .put("role", "compatibility")
                    .put("source", "self-apk:" + RIFTPP_APP0_ARM32_HOST_APK_ENTRY)
                    .put("bytes", arm32Host.size)
                    .put("sha256", sha256(arm32Host))))
            .put("compilerSeedSha256", sha256(compilerFile))
            .put("compilerBytes", compiler.size)
            .put("compileSteps", compileRun.steps)
            .put("programBytes", program.size)
            .put("programSha256", sha256(program))
            .put("runtimePlan", projectDisplay(ref, runtimePlanFile))
            .put("runtimePlanSha256", sha256(runtimePlanFile))
            .put("slices", slices)
            .put("antiContamination", JSONObject()
                .put("hostParsesTig0", false)
                .put("plannerParsesTig0", false)
                .put("compilerAuthority", RIFTPP_APP0_COMPILER_HEX)
                .put("requirementAuthority", "compiled program.bin")
                .put("runtimeAuthority", "one riftpp_app0_host.cpp source compiled for arm64-v8a + armeabi-v7a")
                .put("applicationVmSeedAsset", false))
            .put("manifest", JSONObject()
                .put("path", projectDisplay(ref, manifestOutput))
                .put("bytes", manifestOutput.length())
                .put("sha256", sha256(manifestOutput)))
            .put("manifestReady", true)
            .put("signed", false)
            .put("installableClaimed", false)
            .put("createdAt", System.currentTimeMillis())

        atomicWrite(
            File(buildRoot, "riftpp-app0-materialization.json"),
            result.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(result)
        return result
    }

    @Synchronized
    fun prepareCodynexMc0(project: String, cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)
        val seedFile = projectFile(ref, MC0_SEED_HEX)
        require(seedFile.isFile) { "Codynex MC0 seed is missing" }
        val seed = decodeHex(readTextBounded(seedFile).trim())
        require(seed.size == MC0_SEED_BYTES) {
            "Codynex MC0 seed byte count drift: " + seed.size
        }
        require(sha256(seed) == MC0_SEED_SHA256) {
            "Codynex MC0 seed SHA-256 drift"
        }

        val apkProject = projectFile(ref, MC0_APK_PROJECT)
        require(apkProject.isDirectory) { "Codynex MC0 apk-proof project is missing" }
        val apkDisplay = projectDisplay(ref, apkProject)
        val sourceValidation = validate(apkDisplay, "/D:/Workspace")
        require(sourceValidation.optBoolean("sourceReady")) {
            "Codynex MC0 apk-proof source validation failed"
        }
        require(sourceValidation.optString("nativeLibraryName") == MC0_LIBRARY_NAME) {
            "Codynex MC0 NativeActivity library declaration drift"
        }

        val sourceManifest = projectFile(
            ref,
            MC0_APK_PROJECT + "/app/src/main/AndroidManifest.xml"
        )
        val sourceManifestText = readTextBounded(sourceManifest)
        require(sourceManifestText.contains("package=\"" + MC0_PACKAGE + "\"")) {
            "Codynex MC0 package declaration drift"
        }
        require(sourceManifestText.contains("android:value=\"" + MC0_LIBRARY_NAME + "\"")) {
            "Codynex MC0 library declaration drift"
        }

        val host = readOwnApkEntry(MC0_HOST_APK_ENTRY, MC0_MAX_HOST_BYTES)
        verifyElfImage(host, 1, 40)

        val buildRoot = File(apkProject, "build/riftbuild").canonicalFile
        require(confinedTo(apkProject, buildRoot)) {
            "Codynex MC0 build root escaped apk-proof"
        }
        val preparedRoot = File(buildRoot, "prepared").canonicalFile
        require(confinedTo(buildRoot, preparedRoot)) {
            "Codynex MC0 prepared root escaped build/riftbuild"
        }
        if (preparedRoot.exists()) {
            require(deleteTreeBounded(preparedRoot, MAX_PROJECT_FILES)) {
                "Could not clear stale Codynex MC0 prepared package"
            }
        }

        val libRoot = File(preparedRoot, "lib/armeabi-v7a").canonicalFile
        val assetRoot = File(preparedRoot, "assets").canonicalFile
        require(confinedTo(preparedRoot, libRoot)) {
            "Codynex MC0 library root escaped prepared package"
        }
        require(confinedTo(preparedRoot, assetRoot)) {
            "Codynex MC0 asset root escaped prepared package"
        }
        require(libRoot.mkdirs() || libRoot.isDirectory) {
            "Could not create Codynex MC0 library directory"
        }
        require(assetRoot.mkdirs() || assetRoot.isDirectory) {
            "Could not create Codynex MC0 asset directory"
        }

        val manifestBytes = buildMc0BinaryManifest()
        val manifestOutput = File(preparedRoot, "AndroidManifest.xml").canonicalFile
        val hostOutput = File(libRoot, MC0_LIBRARY_FILE).canonicalFile
        val seedOutput = File(assetRoot, "mc0_seed.bin").canonicalFile

        atomicWrite(manifestOutput, manifestBytes)
        atomicWrite(hostOutput, host)
        atomicWrite(seedOutput, seed)

        require(isBinaryAndroidManifest(manifestOutput)) {
            "Codynex MC0 binary AndroidManifest.xml failed validation"
        }
        require(sha256(hostOutput) == sha256(host)) {
            "Codynex MC0 host materialization hash mismatch"
        }
        require(sha256(seedOutput) == MC0_SEED_SHA256) {
            "Codynex MC0 seed materialization hash mismatch"
        }

        val runId = runId()
        val result = JSONObject()
            .put("format", "riftbuild-codynex-mc0-materialization-v1")
            .put("runId", runId)
            .put("state", "prepared-native")
            .put("project", ref.display)
            .put("androidProject", apkDisplay)
            .put("target", "arm32")
            .put("package", MC0_PACKAGE)
            .put("libraryName", MC0_LIBRARY_NAME)
            .put("libraryFile", MC0_LIBRARY_FILE)
            .put("hostSource", "self-apk:" + MC0_HOST_APK_ENTRY)
            .put("hostBytes", host.size)
            .put("hostSha256", sha256(host))
            .put("seedSource", projectDisplay(ref, seedFile))
            .put("seedBytes", seed.size)
            .put("seedSha256", sha256(seed))
            .put(
                "manifest",
                JSONObject()
                    .put("path", projectDisplay(ref, manifestOutput))
                    .put("bytes", manifestOutput.length())
                    .put("sha256", sha256(manifestOutput))
            )
            .put("antiContamination", JSONObject()
                .put("hostParsesSource", false)
                .put("hostEmitsInstructions", false)
                .put("compilerAuthority", "assets/mc0_seed.bin"))
            .put("manifestReady", true)
            .put("signed", false)
            .put("installableClaimed", false)
            .put("createdAt", System.currentTimeMillis())

        atomicWrite(
            File(buildRoot, "codynex-mc0-materialization.json"),
            result.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(result)
        return result
    }

fun prepareCodynexMc1a(project: String, cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)
        val seedFile = projectFile(ref, MC1A_SEED_HEX)
        require(seedFile.isFile) { "Codynex MC1-A seed is missing" }
        val seed = decodeHex(readTextBounded(seedFile).trim())
        require(seed.size == MC1A_SEED_BYTES) {
            "Codynex MC1-A seed byte count drift: " + seed.size
        }
        require(sha256(seed) == MC1A_SEED_SHA256) {
            "Codynex MC1-A seed SHA-256 drift"
        }

        val apkProject = projectFile(ref, MC1A_APK_PROJECT)
        require(apkProject.isDirectory) { "Codynex MC1-A apk-proof project is missing" }
        val apkDisplay = projectDisplay(ref, apkProject)
        val sourceValidation = validate(apkDisplay, "/D:/Workspace")
        require(sourceValidation.optBoolean("sourceReady")) {
            "Codynex MC1-A apk-proof source validation failed"
        }
        require(sourceValidation.optString("nativeLibraryName") == MC1A_LIBRARY_NAME) {
            "Codynex MC1-A NativeActivity library declaration drift"
        }

        val sourceManifest = projectFile(
            ref,
            MC1A_APK_PROJECT + "/app/src/main/AndroidManifest.xml"
        )
        val sourceManifestText = readTextBounded(sourceManifest)
        require(sourceManifestText.contains("package=\"" + MC1A_PACKAGE + "\"")) {
            "Codynex MC1-A package declaration drift"
        }
        require(sourceManifestText.contains("android:value=\"" + MC1A_LIBRARY_NAME + "\"")) {
            "Codynex MC1-A library declaration drift"
        }

        val host = readOwnApkEntry(MC1A_HOST_APK_ENTRY, MC1A_MAX_HOST_BYTES)
        verifyElfImage(host, 1, 40)

        val buildRoot = File(apkProject, "build/riftbuild").canonicalFile
        require(confinedTo(apkProject, buildRoot)) {
            "Codynex MC1-A build root escaped apk-proof"
        }
        val preparedRoot = File(buildRoot, "prepared").canonicalFile
        require(confinedTo(buildRoot, preparedRoot)) {
            "Codynex MC1-A prepared root escaped build/riftbuild"
        }
        if (preparedRoot.exists()) {
            require(deleteTreeBounded(preparedRoot, MAX_PROJECT_FILES)) {
                "Could not clear stale Codynex MC1-A prepared package"
            }
        }

        val libRoot = File(preparedRoot, "lib/armeabi-v7a").canonicalFile
        val assetRoot = File(preparedRoot, "assets").canonicalFile
        require(confinedTo(preparedRoot, libRoot)) {
            "Codynex MC1-A library root escaped prepared package"
        }
        require(confinedTo(preparedRoot, assetRoot)) {
            "Codynex MC1-A asset root escaped prepared package"
        }
        require(libRoot.mkdirs() || libRoot.isDirectory) {
            "Could not create Codynex MC1-A library directory"
        }
        require(assetRoot.mkdirs() || assetRoot.isDirectory) {
            "Could not create Codynex MC1-A asset directory"
        }

        val manifestBytes = buildMc1aBinaryManifest()
        val manifestOutput = File(preparedRoot, "AndroidManifest.xml").canonicalFile
        val hostOutput = File(libRoot, MC1A_LIBRARY_FILE).canonicalFile
        val seedOutput = File(assetRoot, "mc1a_seed.bin").canonicalFile

        atomicWrite(manifestOutput, manifestBytes)
        atomicWrite(hostOutput, host)
        atomicWrite(seedOutput, seed)

        require(isBinaryAndroidManifest(manifestOutput)) {
            "Codynex MC1-A binary AndroidManifest.xml failed validation"
        }
        require(sha256(hostOutput) == sha256(host)) {
            "Codynex MC1-A host materialization hash mismatch"
        }
        require(sha256(seedOutput) == MC1A_SEED_SHA256) {
            "Codynex MC1-A seed materialization hash mismatch"
        }

        val runId = runId()
        val result = JSONObject()
            .put("format", "riftbuild-codynex-mc1a-materialization-v1")
            .put("runId", runId)
            .put("state", "prepared-native")
            .put("project", ref.display)
            .put("androidProject", apkDisplay)
            .put("target", "arm32")
            .put("package", MC1A_PACKAGE)
            .put("libraryName", MC1A_LIBRARY_NAME)
            .put("libraryFile", MC1A_LIBRARY_FILE)
            .put("hostSource", "self-apk:" + MC1A_HOST_APK_ENTRY)
            .put("hostBytes", host.size)
            .put("hostSha256", sha256(host))
            .put("seedSource", projectDisplay(ref, seedFile))
            .put("seedBytes", seed.size)
            .put("seedSha256", sha256(seed))
            .put(
                "manifest",
                JSONObject()
                    .put("path", projectDisplay(ref, manifestOutput))
                    .put("bytes", manifestOutput.length())
                    .put("sha256", sha256(manifestOutput))
            )
            .put("antiContamination", JSONObject()
                .put("hostParsesSource", false)
                .put("hostEmitsInstructions", false)
                .put("compilerAuthority", "assets/mc1a_seed.bin"))
            .put("manifestReady", true)
            .put("signed", false)
            .put("installableClaimed", false)
            .put("createdAt", System.currentTimeMillis())

        atomicWrite(
            File(buildRoot, "codynex-mc1a-materialization.json"),
            result.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(result)
        return result
    }

fun prepareCodynexMc1b(project: String, cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)
        val seedFile = projectFile(ref, MC1B_SEED_HEX)
        require(seedFile.isFile) { "Codynex MC1-B seed is missing" }
        val seed = decodeHex(readTextBounded(seedFile).trim())
        require(seed.size == MC1B_SEED_BYTES) {
            "Codynex MC1-B seed byte count drift: " + seed.size
        }
        require(sha256(seed) == MC1B_SEED_SHA256) {
            "Codynex MC1-B seed SHA-256 drift"
        }

        val apkProject = projectFile(ref, MC1B_APK_PROJECT)
        require(apkProject.isDirectory) { "Codynex MC1-B apk-proof project is missing" }
        val apkDisplay = projectDisplay(ref, apkProject)
        val sourceValidation = validate(apkDisplay, "/D:/Workspace")
        require(sourceValidation.optBoolean("sourceReady")) {
            "Codynex MC1-B apk-proof source validation failed"
        }
        require(sourceValidation.optString("nativeLibraryName") == MC1B_LIBRARY_NAME) {
            "Codynex MC1-B NativeActivity library declaration drift"
        }

        val sourceManifest = projectFile(
            ref,
            MC1B_APK_PROJECT + "/app/src/main/AndroidManifest.xml"
        )
        val sourceManifestText = readTextBounded(sourceManifest)
        require(sourceManifestText.contains("package=\"" + MC1B_PACKAGE + "\"")) {
            "Codynex MC1-B package declaration drift"
        }
        require(sourceManifestText.contains("android:value=\"" + MC1B_LIBRARY_NAME + "\"")) {
            "Codynex MC1-B library declaration drift"
        }

        val host = readOwnApkEntry(MC1B_HOST_APK_ENTRY, MC1B_MAX_HOST_BYTES)
        verifyElfImage(host, 1, 40)

        val buildRoot = File(apkProject, "build/riftbuild").canonicalFile
        require(confinedTo(apkProject, buildRoot)) {
            "Codynex MC1-B build root escaped apk-proof"
        }
        val preparedRoot = File(buildRoot, "prepared").canonicalFile
        require(confinedTo(buildRoot, preparedRoot)) {
            "Codynex MC1-B prepared root escaped build/riftbuild"
        }
        if (preparedRoot.exists()) {
            require(deleteTreeBounded(preparedRoot, MAX_PROJECT_FILES)) {
                "Could not clear stale Codynex MC1-B prepared package"
            }
        }

        val libRoot = File(preparedRoot, "lib/armeabi-v7a").canonicalFile
        val assetRoot = File(preparedRoot, "assets").canonicalFile
        require(confinedTo(preparedRoot, libRoot)) {
            "Codynex MC1-B library root escaped prepared package"
        }
        require(confinedTo(preparedRoot, assetRoot)) {
            "Codynex MC1-B asset root escaped prepared package"
        }
        require(libRoot.mkdirs() || libRoot.isDirectory) {
            "Could not create Codynex MC1-B library directory"
        }
        require(assetRoot.mkdirs() || assetRoot.isDirectory) {
            "Could not create Codynex MC1-B asset directory"
        }

        val manifestBytes = buildMc1bBinaryManifest()
        val manifestOutput = File(preparedRoot, "AndroidManifest.xml").canonicalFile
        val hostOutput = File(libRoot, MC1B_LIBRARY_FILE).canonicalFile
        val seedOutput = File(assetRoot, "mc1b_seed.bin").canonicalFile

        atomicWrite(manifestOutput, manifestBytes)
        atomicWrite(hostOutput, host)
        atomicWrite(seedOutput, seed)

        require(isBinaryAndroidManifest(manifestOutput)) {
            "Codynex MC1-B binary AndroidManifest.xml failed validation"
        }
        require(sha256(hostOutput) == sha256(host)) {
            "Codynex MC1-B host materialization hash mismatch"
        }
        require(sha256(seedOutput) == MC1B_SEED_SHA256) {
            "Codynex MC1-B seed materialization hash mismatch"
        }

        val runId = runId()
        val result = JSONObject()
            .put("format", "riftbuild-codynex-mc1b-materialization-v1")
            .put("runId", runId)
            .put("state", "prepared-native")
            .put("project", ref.display)
            .put("androidProject", apkDisplay)
            .put("target", "arm32")
            .put("package", MC1B_PACKAGE)
            .put("libraryName", MC1B_LIBRARY_NAME)
            .put("libraryFile", MC1B_LIBRARY_FILE)
            .put("hostSource", "self-apk:" + MC1B_HOST_APK_ENTRY)
            .put("hostBytes", host.size)
            .put("hostSha256", sha256(host))
            .put("seedSource", projectDisplay(ref, seedFile))
            .put("seedBytes", seed.size)
            .put("seedSha256", sha256(seed))
            .put(
                "manifest",
                JSONObject()
                    .put("path", projectDisplay(ref, manifestOutput))
                    .put("bytes", manifestOutput.length())
                    .put("sha256", sha256(manifestOutput))
            )
            .put("antiContamination", JSONObject()
                .put("hostParsesSource", false)
                .put("hostEmitsInstructions", false)
                .put("compilerAuthority", "assets/mc1b_seed.bin"))
            .put("manifestReady", true)
            .put("signed", false)
            .put("installableClaimed", false)
            .put("createdAt", System.currentTimeMillis())

        atomicWrite(
            File(buildRoot, "codynex-mc1b-materialization.json"),
            result.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(result)
        return result
    }

    fun prepareCodynexM2Vm0(project: String, cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)
        val seedFile = projectFile(ref, M2_VM0_SEED_HEX)
        require(seedFile.isFile) { "Codynex M2-A VM0 seed is missing" }
        val seed = decodeHex(readTextBounded(seedFile).trim())
        require(seed.size == M2_VM0_SEED_BYTES) {
            "Codynex M2-A VM0 seed byte count drift: " + seed.size
        }
        require(sha256(seed) == M2_VM0_SEED_SHA256) {
            "Codynex M2-A VM0 seed SHA-256 drift"
        }

        val apkProject = projectFile(ref, M2_VM0_APK_PROJECT)
        require(apkProject.isDirectory) { "Codynex M2-A VM0 apk-proof project is missing" }
        val apkDisplay = projectDisplay(ref, apkProject)
        val sourceValidation = validate(apkDisplay, "/D:/Workspace")
        require(sourceValidation.optBoolean("sourceReady")) {
            "Codynex M2-A VM0 apk-proof source validation failed"
        }
        require(sourceValidation.optString("nativeLibraryName") == M2_VM0_LIBRARY_NAME) {
            "Codynex M2-A VM0 NativeActivity library declaration drift"
        }

        val sourceManifest = projectFile(
            ref,
            M2_VM0_APK_PROJECT + "/app/src/main/AndroidManifest.xml"
        )
        val sourceManifestText = readTextBounded(sourceManifest)
        require(sourceManifestText.contains("package=\"" + M2_VM0_PACKAGE + "\"")) {
            "Codynex M2-A VM0 package declaration drift"
        }
        require(sourceManifestText.contains("android:value=\"" + M2_VM0_LIBRARY_NAME + "\"")) {
            "Codynex M2-A VM0 library declaration drift"
        }

        val host = readOwnApkEntry(M2_VM0_HOST_APK_ENTRY, M2_VM0_MAX_HOST_BYTES)
        verifyElfImage(host, 1, 40)

        val buildRoot = File(apkProject, "build/riftbuild").canonicalFile
        require(confinedTo(apkProject, buildRoot)) {
            "Codynex M2-A VM0 build root escaped apk-proof"
        }
        val preparedRoot = File(buildRoot, "prepared").canonicalFile
        require(confinedTo(buildRoot, preparedRoot)) {
            "Codynex M2-A VM0 prepared root escaped build/riftbuild"
        }
        if (preparedRoot.exists()) {
            require(deleteTreeBounded(preparedRoot, MAX_PROJECT_FILES)) {
                "Could not clear stale Codynex M2-A VM0 prepared package"
            }
        }

        val libRoot = File(preparedRoot, "lib/armeabi-v7a").canonicalFile
        val assetRoot = File(preparedRoot, "assets").canonicalFile
        require(confinedTo(preparedRoot, libRoot)) {
            "Codynex M2-A VM0 library root escaped prepared package"
        }
        require(confinedTo(preparedRoot, assetRoot)) {
            "Codynex M2-A VM0 asset root escaped prepared package"
        }
        require(libRoot.mkdirs() || libRoot.isDirectory) {
            "Could not create Codynex M2-A VM0 library directory"
        }
        require(assetRoot.mkdirs() || assetRoot.isDirectory) {
            "Could not create Codynex M2-A VM0 asset directory"
        }

        val manifestBytes = buildM2Vm0BinaryManifest()
        val manifestOutput = File(preparedRoot, "AndroidManifest.xml").canonicalFile
        val hostOutput = File(libRoot, M2_VM0_LIBRARY_FILE).canonicalFile
        val seedOutput = File(assetRoot, "vm0_seed.bin").canonicalFile

        atomicWrite(manifestOutput, manifestBytes)
        atomicWrite(hostOutput, host)
        atomicWrite(seedOutput, seed)

        require(isBinaryAndroidManifest(manifestOutput)) {
            "Codynex M2-A VM0 binary AndroidManifest.xml failed validation"
        }
        require(sha256(hostOutput) == sha256(host)) {
            "Codynex M2-A VM0 host materialization hash mismatch"
        }
        require(sha256(seedOutput) == M2_VM0_SEED_SHA256) {
            "Codynex M2-A VM0 seed materialization hash mismatch"
        }

        val runId = runId()
        val result = JSONObject()
            .put("format", "riftbuild-codynex-m2-vm0-materialization-v1")
            .put("runId", runId)
            .put("state", "prepared-native")
            .put("project", ref.display)
            .put("androidProject", apkDisplay)
            .put("target", "arm32")
            .put("package", M2_VM0_PACKAGE)
            .put("libraryName", M2_VM0_LIBRARY_NAME)
            .put("libraryFile", M2_VM0_LIBRARY_FILE)
            .put("hostSource", "self-apk:" + M2_VM0_HOST_APK_ENTRY)
            .put("hostBytes", host.size)
            .put("hostSha256", sha256(host))
            .put("seedSource", projectDisplay(ref, seedFile))
            .put("seedBytes", seed.size)
            .put("seedSha256", sha256(seed))
            .put(
                "manifest",
                JSONObject()
                    .put("path", projectDisplay(ref, manifestOutput))
                    .put("bytes", manifestOutput.length())
                    .put("sha256", sha256(manifestOutput))
            )
            .put("antiContamination", JSONObject()
                .put("hostParsesSource", false)
                .put("hostEmitsInstructions", false)
                .put("vmAuthority", "assets/vm0_seed.bin"))
            .put("manifestReady", true)
            .put("signed", false)
            .put("installableClaimed", false)
            .put("createdAt", System.currentTimeMillis())

        atomicWrite(
            File(buildRoot, "codynex-m2-vm0-materialization.json"),
            result.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(result)
        return result
    }

    fun prepareCodynexM2B(project: String, cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)

        val vmFile = projectFile(ref, M2_B_VM_HEX)
        require(vmFile.isFile) { "Codynex M2-B VM1 seed is missing" }
        val vm = decodeHex(readTextBounded(vmFile).trim())
        require(vm.size == M2_B_VM_BYTES) {
            "Codynex M2-B VM1 byte count drift: " + vm.size
        }
        require(sha256(vm) == M2_B_VM_SHA256) {
            "Codynex M2-B VM1 SHA-256 drift"
        }

        val compilerFile = projectFile(ref, M2_B_COMPILER_HEX)
        require(compilerFile.isFile) { "Codynex M2-B compiler bytecode is missing" }
        val compiler = decodeHex(readTextBounded(compilerFile).trim())
        require(compiler.size == M2_B_COMPILER_BYTES) {
            "Codynex M2-B compiler byte count drift: " + compiler.size
        }
        require(sha256(compiler) == M2_B_COMPILER_SHA256) {
            "Codynex M2-B compiler SHA-256 drift"
        }

        val apkProject = projectFile(ref, M2_B_APK_PROJECT)
        require(apkProject.isDirectory) { "Codynex M2-B apk-proof project is missing" }
        val apkDisplay = projectDisplay(ref, apkProject)
        val sourceValidation = validate(apkDisplay, "/D:/Workspace")
        require(sourceValidation.optBoolean("sourceReady")) {
            "Codynex M2-B apk-proof source validation failed"
        }
        require(sourceValidation.optString("nativeLibraryName") == M2_B_LIBRARY_NAME) {
            "Codynex M2-B NativeActivity library declaration drift"
        }

        val sourceManifest = projectFile(
            ref,
            M2_B_APK_PROJECT + "/app/src/main/AndroidManifest.xml"
        )
        val sourceManifestText = readTextBounded(sourceManifest)
        require(sourceManifestText.contains("package=\"" + M2_B_PACKAGE + "\"")) {
            "Codynex M2-B package declaration drift"
        }
        require(sourceManifestText.contains("android:value=\"" + M2_B_LIBRARY_NAME + "\"")) {
            "Codynex M2-B library declaration drift"
        }

        val host = readOwnApkEntry(M2_B_HOST_APK_ENTRY, M2_B_MAX_HOST_BYTES)
        verifyElfImage(host, 1, 40)

        val buildRoot = File(apkProject, "build/riftbuild").canonicalFile
        require(confinedTo(apkProject, buildRoot)) {
            "Codynex M2-B build root escaped apk-proof"
        }
        val preparedRoot = File(buildRoot, "prepared").canonicalFile
        require(confinedTo(buildRoot, preparedRoot)) {
            "Codynex M2-B prepared root escaped build/riftbuild"
        }
        if (preparedRoot.exists()) {
            require(deleteTreeBounded(preparedRoot, MAX_PROJECT_FILES)) {
                "Could not clear stale Codynex M2-B prepared package"
            }
        }

        val libRoot = File(preparedRoot, "lib/armeabi-v7a").canonicalFile
        val assetRoot = File(preparedRoot, "assets").canonicalFile
        require(confinedTo(preparedRoot, libRoot)) {
            "Codynex M2-B library root escaped prepared package"
        }
        require(confinedTo(preparedRoot, assetRoot)) {
            "Codynex M2-B asset root escaped prepared package"
        }
        require(libRoot.mkdirs() || libRoot.isDirectory) {
            "Could not create Codynex M2-B library directory"
        }
        require(assetRoot.mkdirs() || assetRoot.isDirectory) {
            "Could not create Codynex M2-B asset directory"
        }

        val manifestBytes = buildM2BBinaryManifest()
        val manifestOutput = File(preparedRoot, "AndroidManifest.xml").canonicalFile
        val hostOutput = File(libRoot, M2_B_LIBRARY_FILE).canonicalFile
        val vmOutput = File(assetRoot, "vm1_seed.bin").canonicalFile
        val compilerOutput = File(assetRoot, "mc1b_compiler.bin").canonicalFile

        atomicWrite(manifestOutput, manifestBytes)
        atomicWrite(hostOutput, host)
        atomicWrite(vmOutput, vm)
        atomicWrite(compilerOutput, compiler)

        require(isBinaryAndroidManifest(manifestOutput)) {
            "Codynex M2-B binary AndroidManifest.xml failed validation"
        }
        require(sha256(hostOutput) == sha256(host)) {
            "Codynex M2-B host materialization hash mismatch"
        }
        require(sha256(vmOutput) == M2_B_VM_SHA256) {
            "Codynex M2-B VM1 materialization hash mismatch"
        }
        require(sha256(compilerOutput) == M2_B_COMPILER_SHA256) {
            "Codynex M2-B compiler materialization hash mismatch"
        }

        val runId = runId()
        val result = JSONObject()
            .put("format", "riftbuild-codynex-m2b-materialization-v1")
            .put("runId", runId)
            .put("state", "prepared-native")
            .put("project", ref.display)
            .put("androidProject", apkDisplay)
            .put("target", "arm32")
            .put("package", M2_B_PACKAGE)
            .put("libraryName", M2_B_LIBRARY_NAME)
            .put("libraryFile", M2_B_LIBRARY_FILE)
            .put("hostSource", "self-apk:" + M2_B_HOST_APK_ENTRY)
            .put("hostBytes", host.size)
            .put("hostSha256", sha256(host))
            .put("vmSource", projectDisplay(ref, vmFile))
            .put("vmBytes", vm.size)
            .put("vmSha256", sha256(vm))
            .put("compilerSource", projectDisplay(ref, compilerFile))
            .put("compilerBytes", compiler.size)
            .put("compilerSha256", sha256(compiler))
            .put(
                "manifest",
                JSONObject()
                    .put("path", projectDisplay(ref, manifestOutput))
                    .put("bytes", manifestOutput.length())
                    .put("sha256", sha256(manifestOutput))
            )
            .put("antiContamination", JSONObject()
                .put("hostParsesSource", false)
                .put("hostEmitsInstructions", false)
                .put("vmAuthority", "assets/vm1_seed.bin")
                .put("compilerAuthority", "assets/mc1b_compiler.bin"))
            .put("manifestReady", true)
            .put("signed", false)
            .put("installableClaimed", false)
            .put("createdAt", System.currentTimeMillis())

        atomicWrite(
            File(buildRoot, "codynex-m2b-materialization.json"),
            result.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(result)
        return result
    }

    fun prepareCodynexMc2A(project: String, cwd: String = "/D:/Workspace"): JSONObject {
        val ref = resolveProject(project, cwd)

        val vmFile = projectFile(ref, MC2_A_VM_HEX)
        require(vmFile.isFile) { "Codynex MC2-A VM1 seed is missing" }
        val vm = decodeHex(readTextBounded(vmFile).trim())
        require(vm.size == MC2_A_VM_BYTES) {
            "Codynex MC2-A VM1 byte count drift: " + vm.size
        }
        require(sha256(vm) == MC2_A_VM_SHA256) {
            "Codynex MC2-A VM1 SHA-256 drift"
        }

        val compilerFile = projectFile(ref, MC2_A_COMPILER_HEX)
        require(compilerFile.isFile) { "Codynex MC2-A compiler A is missing" }
        val compiler = decodeHex(readTextBounded(compilerFile).trim())
        require(compiler.size == MC2_A_COMPILER_BYTES) {
            "Codynex MC2-A compiler byte count drift: " + compiler.size
        }
        require(sha256(compiler) == MC2_A_COMPILER_SHA256) {
            "Codynex MC2-A compiler SHA-256 drift"
        }

        val sourceFile = projectFile(ref, MC2_A_SOURCE)
        require(sourceFile.isFile) { "Codynex MC2-A external compiler source is missing" }
        val source = readTextBounded(sourceFile).toByteArray(Charsets.UTF_8)
        require(source.size == MC2_A_SOURCE_BYTES) {
            "Codynex MC2-A source byte count drift: " + source.size
        }
        require(sha256(source) == MC2_A_SOURCE_SHA256) {
            "Codynex MC2-A source SHA-256 drift"
        }

        val apkProject = projectFile(ref, MC2_A_APK_PROJECT)
        require(apkProject.isDirectory) { "Codynex MC2-A apk-proof project is missing" }
        val apkDisplay = projectDisplay(ref, apkProject)
        val sourceValidation = validate(apkDisplay, "/D:/Workspace")
        require(sourceValidation.optBoolean("sourceReady")) {
            "Codynex MC2-A apk-proof source validation failed"
        }
        require(sourceValidation.optString("nativeLibraryName") == MC2_A_LIBRARY_NAME) {
            "Codynex MC2-A NativeActivity library declaration drift"
        }

        val sourceManifest = projectFile(
            ref,
            MC2_A_APK_PROJECT + "/app/src/main/AndroidManifest.xml"
        )
        val sourceManifestText = readTextBounded(sourceManifest)
        require(sourceManifestText.contains("package=\"" + MC2_A_PACKAGE + "\"")) {
            "Codynex MC2-A package declaration drift"
        }
        require(sourceManifestText.contains("android:value=\"" + MC2_A_LIBRARY_NAME + "\"")) {
            "Codynex MC2-A library declaration drift"
        }

        val host = readOwnApkEntry(MC2_A_HOST_APK_ENTRY, MC2_A_MAX_HOST_BYTES)
        verifyElfImage(host, 1, 40)

        val buildRoot = File(apkProject, "build/riftbuild").canonicalFile
        require(confinedTo(apkProject, buildRoot)) {
            "Codynex MC2-A build root escaped apk-proof"
        }
        val preparedRoot = File(buildRoot, "prepared").canonicalFile
        require(confinedTo(buildRoot, preparedRoot)) {
            "Codynex MC2-A prepared root escaped build/riftbuild"
        }
        if (preparedRoot.exists()) {
            require(deleteTreeBounded(preparedRoot, MAX_PROJECT_FILES)) {
                "Could not clear stale Codynex MC2-A prepared package"
            }
        }

        val libRoot = File(preparedRoot, "lib/armeabi-v7a").canonicalFile
        val assetRoot = File(preparedRoot, "assets").canonicalFile
        require(confinedTo(preparedRoot, libRoot)) {
            "Codynex MC2-A library root escaped prepared package"
        }
        require(confinedTo(preparedRoot, assetRoot)) {
            "Codynex MC2-A asset root escaped prepared package"
        }
        require(libRoot.mkdirs() || libRoot.isDirectory) {
            "Could not create Codynex MC2-A library directory"
        }
        require(assetRoot.mkdirs() || assetRoot.isDirectory) {
            "Could not create Codynex MC2-A asset directory"
        }

        val manifestBytes = buildMc2ABinaryManifest()
        val manifestOutput = File(preparedRoot, "AndroidManifest.xml").canonicalFile
        val hostOutput = File(libRoot, MC2_A_LIBRARY_FILE).canonicalFile
        val vmOutput = File(assetRoot, "vm1_seed.bin").canonicalFile
        val compilerOutput = File(assetRoot, "selfhost_compiler.bin").canonicalFile
        val sourceOutput = File(assetRoot, "selfhost_compiler.cx0").canonicalFile

        atomicWrite(manifestOutput, manifestBytes)
        atomicWrite(hostOutput, host)
        atomicWrite(vmOutput, vm)
        atomicWrite(compilerOutput, compiler)
        atomicWrite(sourceOutput, source)

        require(isBinaryAndroidManifest(manifestOutput)) {
            "Codynex MC2-A binary AndroidManifest.xml failed validation"
        }
        require(sha256(hostOutput) == sha256(host)) {
            "Codynex MC2-A host materialization hash mismatch"
        }
        require(sha256(vmOutput) == MC2_A_VM_SHA256) {
            "Codynex MC2-A VM1 materialization hash mismatch"
        }
        require(sha256(compilerOutput) == MC2_A_COMPILER_SHA256) {
            "Codynex MC2-A compiler materialization hash mismatch"
        }
        require(sha256(sourceOutput) == MC2_A_SOURCE_SHA256) {
            "Codynex MC2-A source materialization hash mismatch"
        }

        val runId = runId()
        val result = JSONObject()
            .put("format", "riftbuild-codynex-mc2a-materialization-v1")
            .put("runId", runId)
            .put("state", "prepared-native")
            .put("project", ref.display)
            .put("androidProject", apkDisplay)
            .put("target", "arm32")
            .put("package", MC2_A_PACKAGE)
            .put("libraryName", MC2_A_LIBRARY_NAME)
            .put("libraryFile", MC2_A_LIBRARY_FILE)
            .put("hostSource", "self-apk:" + MC2_A_HOST_APK_ENTRY)
            .put("hostBytes", host.size)
            .put("hostSha256", sha256(host))
            .put("vmSource", projectDisplay(ref, vmFile))
            .put("vmBytes", vm.size)
            .put("vmSha256", sha256(vm))
            .put("compilerSource", projectDisplay(ref, compilerFile))
            .put("compilerBytes", compiler.size)
            .put("compilerSha256", sha256(compiler))
            .put("externalSource", projectDisplay(ref, sourceFile))
            .put("externalSourceBytes", source.size)
            .put("externalSourceSha256", sha256(source))
            .put(
                "manifest",
                JSONObject()
                    .put("path", projectDisplay(ref, manifestOutput))
                    .put("bytes", manifestOutput.length())
                    .put("sha256", sha256(manifestOutput))
            )
            .put("antiContamination", JSONObject()
                .put("hostParsesSource", false)
                .put("hostEmitsInstructions", false)
                .put("vmAuthority", "assets/vm1_seed.bin")
                .put("compilerAuthority", "assets/selfhost_compiler.bin")
                .put("sourceAuthority", "assets/selfhost_compiler.cx0"))
            .put("manifestReady", true)
            .put("signed", false)
            .put("installableClaimed", false)
            .put("createdAt", System.currentTimeMillis())

        atomicWrite(
            File(buildRoot, "codynex-mc2a-materialization.json"),
            result.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(result)
        return result
    }

    @Synchronized
    fun prepareCodynexApp(
        project: String,
        sourcePath: String,
        cwd: String = "/D:/Workspace"
    ): JSONObject {
        val ref = resolveProject(project, cwd)

        CODYNEX_APPHOST_SOURCE_SHA256.forEach { (path, expectedSha) ->
            verifyProjectSource(ref, path, expectedSha)
        }

        val appProject = projectFile(ref, CODYNEX_APPHOST_PROJECT)
        require(appProject.isDirectory) {
            "Codynex standalone app-host project is missing"
        }
        val appDisplay = projectDisplay(ref, appProject)
        val sourceValidation = validate(appDisplay, "/D:/Workspace")
        require(sourceValidation.optBoolean("sourceReady")) {
            "Codynex standalone app-host source validation failed"
        }
        require(sourceValidation.optBoolean("requiresDex")) {
            "Codynex standalone app host must remain code-bearing"
        }
        require(
            sourceValidation.optString("activityName") == CODYNEX_APP_ACTIVITY
        ) {
            "Codynex standalone app launch activity drift"
        }

        val sourceFile = projectFile(ref, sourcePath)
        require(sourceFile.isFile && sourceFile.extension == "cx") {
            "Codynex standalone app source must be an existing .cx file"
        }
        val sourceText = readTextBounded(sourceFile)
        val sourceBytes = sourceText.toByteArray(Charsets.UTF_8)
        require(
            sourceBytes.isNotEmpty() &&
                sourceBytes.size <= CODYNEX_APP_MAX_SOURCE_BYTES
        ) {
            "Codynex standalone app source must be 1.." +
                CODYNEX_APP_MAX_SOURCE_BYTES + " UTF-8 bytes"
        }

        val compiled =
            codynexRuntime.compileCodynexC0Project(
                rootSource = sourceText,
                moduleSources = emptyMap()
            )
        require(
            compiled.compiler == "codynex-c0-ref/0.11.0" ||
                compiled.compiler == "codynex-c0-ref/0.12.0"
        ) {
            "Codynex standalone app compiler identity drift"
        }
        require(compiled.moduleCount == 1) {
            "Codynex standalone app proof currently requires one module"
        }
        require(
            compiled.vm1.isNotEmpty() &&
                compiled.vm1.size <= CODYNEX_APP_MAX_PROGRAM_BYTES
        ) {
            "Codynex standalone app VM1 program exceeds bounds"
        }

        val vmFile = projectFile(ref, EDITOR_VM_HEX)
        require(vmFile.isFile) {
            "Codynex standalone app VM1 runtime authority is missing"
        }
        val vmText = readTextBounded(vmFile).toByteArray(Charsets.UTF_8)
        require(vmText.size == EDITOR_VM_HEX_BYTES) {
            "Codynex standalone app VM1 hex byte count drift: " +
                vmText.size
        }
        require(sha256(vmText) == EDITOR_VM_HEX_SHA256) {
            "Codynex standalone app VM1 runtime SHA-256 drift"
        }

        val host =
            readOwnApkEntry(
                EDITOR_HOST_APK_ENTRY,
                EDITOR_MAX_HOST_BYTES
            )
        verifyElfImage(host, 1, 40)

        val dexEntries = readOwnDexEntries()
        require(dexEntries.isNotEmpty()) {
            "Installed RiftOS APK contains no Codynex app-host DEX payload"
        }
        dexEntries.forEach { (name, bytes) ->
            require(bytes.size >= 8) {
                "RiftOS DEX payload is too small: " + name
            }
            require(
                bytes[0] == 'd'.code.toByte() &&
                    bytes[1] == 'e'.code.toByte() &&
                    bytes[2] == 'x'.code.toByte() &&
                    bytes[3] == '\n'.code.toByte() &&
                    bytes[7] == 0.toByte()
            ) {
                "RiftOS DEX payload has invalid magic: " + name
            }
        }

        val buildRoot =
            File(appProject, "build/riftbuild").canonicalFile
        require(confinedTo(appProject, buildRoot)) {
            "Codynex standalone app build root escaped app-host project"
        }
        val preparedRoot = File(buildRoot, "prepared").canonicalFile
        require(confinedTo(buildRoot, preparedRoot)) {
            "Codynex standalone app prepared root escaped build/riftbuild"
        }
        if (preparedRoot.exists()) {
            require(deleteTreeBounded(preparedRoot, MAX_PROJECT_FILES)) {
                "Could not clear stale Codynex standalone app package"
            }
        }

        val libRoot =
            File(preparedRoot, "lib/armeabi-v7a").canonicalFile
        val assetRoot = File(preparedRoot, "assets").canonicalFile
        require(confinedTo(preparedRoot, libRoot)) {
            "Codynex standalone app library root escaped package"
        }
        require(confinedTo(preparedRoot, assetRoot)) {
            "Codynex standalone app asset root escaped package"
        }
        require(libRoot.mkdirs() || libRoot.isDirectory) {
            "Could not create Codynex standalone app library directory"
        }
        require(assetRoot.mkdirs() || assetRoot.isDirectory) {
            "Could not create Codynex standalone app asset directory"
        }

        val manifestOutput =
            File(preparedRoot, "AndroidManifest.xml").canonicalFile
        val hostOutput =
            File(libRoot, CODYNEX_APP_LIBRARY_FILE).canonicalFile
        val vmOutput = File(assetRoot, "vm1_seed.hex").canonicalFile
        val programOutput =
            File(assetRoot, CODYNEX_APP_PROGRAM_ASSET).canonicalFile

        atomicWrite(manifestOutput, buildCodynexAppBinaryManifest())
        atomicWrite(hostOutput, host)
        atomicWrite(vmOutput, vmText)
        atomicWrite(programOutput, compiled.vm1)

        val dexReceipt = JSONArray()
        for ((name, bytes) in dexEntries) {
            require(DEX_ENTRY.matches(name)) {
                "Unsafe Codynex standalone DEX output name: " + name
            }
            val output = File(preparedRoot, name).canonicalFile
            require(confinedTo(preparedRoot, output)) {
                "Codynex standalone DEX output escaped prepared package"
            }
            atomicWrite(output, bytes)
            require(sha256(output) == sha256(bytes)) {
                "Codynex standalone DEX materialization hash mismatch: " +
                    name
            }
            dexReceipt.put(
                JSONObject()
                    .put("name", name)
                    .put("bytes", bytes.size)
                    .put("sha256", sha256(bytes))
            )
        }

        require(isBinaryAndroidManifest(manifestOutput)) {
            "Codynex standalone binary AndroidManifest.xml failed validation"
        }
        require(sha256(hostOutput) == sha256(host)) {
            "Codynex standalone VM bridge materialization hash mismatch"
        }
        require(sha256(vmOutput) == EDITOR_VM_HEX_SHA256) {
            "Codynex standalone VM1 runtime materialization hash mismatch"
        }
        require(sha256(programOutput) == sha256(compiled.vm1)) {
            "Codynex standalone program materialization hash mismatch"
        }

        val runId = runId()
        val result =
            JSONObject()
                .put(
                    "format",
                    "riftbuild-codynex-standalone-app-materialization-v1"
                )
                .put("runId", runId)
                .put("state", "prepared-code")
                .put("project", ref.display)
                .put("androidProject", appDisplay)
                .put("target", "arm32")
                .put("package", CODYNEX_APP_PACKAGE)
                .put("activity", CODYNEX_APP_ACTIVITY)
                .put("source", projectDisplay(ref, sourceFile))
                .put("sourceSha256", sha256(sourceBytes))
                .put("compiler", compiled.compiler)
                .put("moduleCount", compiled.moduleCount)
                .put("programBytes", compiled.vm1.size)
                .put("programSha256", sha256(compiled.vm1))
                .put("vmSha256", EDITOR_VM_HEX_SHA256)
                .put("hostSha256", sha256(host))
                .put("dex", dexReceipt)
                .put("manifestSha256", sha256(manifestOutput))
                .put(
                    "antiContamination",
                    JSONObject()
                        .put("hostParsesSource", false)
                        .put("hostContainsAppSemantics", false)
                        .put("appSemantics", "assets/program.vm1")
                        .put("uiProtocol", "CXUI v1")
                )
                .put("manifestReady", true)
                .put("dexReady", true)
                .put("signed", false)
                .put("installableClaimed", false)
                .put("createdAt", System.currentTimeMillis())

        atomicWrite(
            File(buildRoot, "codynex-standalone-app-materialization.json"),
            result.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(result)
        return result
    }

    @Synchronized
    fun prepareRiftppEditor(
        project: String,
        cwd: String = "/D:/Workspace"
    ): JSONObject {
        val ref = resolveProject(project, cwd)

        RIFTPP_EDITOR_SOURCE_SHA256.forEach { (sourcePath, expectedSha) ->
            verifyProjectSource(ref, sourcePath, expectedSha)
        }

        val editorProject = projectFile(ref, RIFTPP_EDITOR_PROJECT)
        require(editorProject.isDirectory) {
            "Rift++ Project v1 editor project is missing"
        }

        val editorDisplay = projectDisplay(ref, editorProject)
        val sourceValidation = validate(editorDisplay, "/D:/Workspace")

        require(sourceValidation.optBoolean("sourceReady")) {
            "Rift++ Project v1 editor source validation failed"
        }
        require(sourceValidation.optBoolean("requiresDex")) {
            "Rift++ Project v1 editor must remain a code-bearing Activity package"
        }

        val sourceActivity = sourceValidation.optString("activityName")
        require(
            sourceActivity == RIFTPP_EDITOR_ACTIVITY ||
                sourceActivity == ".MainActivity"
        ) {
            "Rift++ Project v1 editor launch activity drift: " + sourceActivity
        }

        fun readPinnedAsset(
            sourcePath: String,
            expectedBytes: Int,
            expectedSha: String,
            label: String
        ): ByteArray {
            val file = projectFile(ref, sourcePath)
            require(file.isFile) { "$label is missing" }
            val bytes = readTextBounded(file).toByteArray(Charsets.UTF_8)

            require(bytes.size == expectedBytes) {
                "$label byte count drift: " + bytes.size
            }
            require(sha256(bytes) == expectedSha) {
                "$label SHA-256 drift"
            }

            return bytes
        }

        val compiler = readPinnedAsset(
            RIFTPP_EDITOR_COMPILER,
            RIFTPP_EDITOR_COMPILER_BYTES,
            RIFTPP_EDITOR_COMPILER_SHA256,
            "Rift++ frozen S3 ARM32 compiler"
        )
        val app2Frontend = readPinnedAsset(
            RIFTPP_EDITOR_APP2_FRONTEND,
            RIFTPP_EDITOR_APP2_FRONTEND_BYTES,
            RIFTPP_EDITOR_APP2_FRONTEND_SHA256,
            "Rift++ App v2 ARM32 frontend"
        )
        val projectFrontend = readPinnedAsset(
            RIFTPP_EDITOR_PROJECT_FRONTEND,
            RIFTPP_EDITOR_PROJECT_FRONTEND_BYTES,
            RIFTPP_EDITOR_PROJECT_FRONTEND_SHA256,
            "Rift++ Project v1 ARM32 frontend"
        )
        val runtime = readPinnedAsset(
            RIFTPP_EDITOR_RUNTIME,
            RIFTPP_EDITOR_RUNTIME_BYTES,
            RIFTPP_EDITOR_RUNTIME_SHA256,
            "Rift++ App v2 ARM32 runtime"
        )
        val sampleMain = readPinnedAsset(
            RIFTPP_EDITOR_SAMPLE_MAIN,
            RIFTPP_EDITOR_SAMPLE_MAIN_BYTES,
            RIFTPP_EDITOR_SAMPLE_MAIN_SHA256,
            "Rift++ Project v1 sample entry"
        )
        val sampleUi = readPinnedAsset(
            RIFTPP_EDITOR_SAMPLE_UI,
            RIFTPP_EDITOR_SAMPLE_UI_BYTES,
            RIFTPP_EDITOR_SAMPLE_UI_SHA256,
            "Rift++ Project v1 sample UI module"
        )
        val sampleManifest = readPinnedAsset(
            RIFTPP_EDITOR_SAMPLE_MANIFEST,
            RIFTPP_EDITOR_SAMPLE_MANIFEST_BYTES,
            RIFTPP_EDITOR_SAMPLE_MANIFEST_SHA256,
            "Rift++ Project v1 sample manifest"
        )

        val host = readOwnApkEntry(
            RIFTPP_EDITOR_HOST_APK_ENTRY,
            RIFTPP_EDITOR_MAX_HOST_BYTES
        )
        verifyElfImage(host, 1, 40)

        val dexEntries = readOwnDexEntries()
        require(dexEntries.isNotEmpty()) {
            "Installed RiftOS APK contains no Rift++ editor DEX payload"
        }

        dexEntries.forEach { (name, bytes) ->
            require(bytes.size >= 8) {
                "RiftOS DEX payload is too small: " + name
            }
            require(
                bytes[0] == 'd'.code.toByte() &&
                    bytes[1] == 'e'.code.toByte() &&
                    bytes[2] == 'x'.code.toByte() &&
                    bytes[3] == '\n'.code.toByte() &&
                    bytes[7] == 0.toByte()
            ) {
                "RiftOS DEX payload has invalid magic: " + name
            }
        }

        val buildRoot = File(editorProject, "build/riftbuild").canonicalFile
        require(confinedTo(editorProject, buildRoot)) {
            "Rift++ editor build root escaped editor project"
        }

        val preparedRoot = File(buildRoot, "prepared").canonicalFile
        require(confinedTo(buildRoot, preparedRoot)) {
            "Rift++ editor prepared root escaped build/riftbuild"
        }

        if (preparedRoot.exists()) {
            require(deleteTreeBounded(preparedRoot, MAX_PROJECT_FILES)) {
                "Could not clear stale Rift++ editor prepared package"
            }
        }

        val libRoot = File(preparedRoot, "lib/armeabi-v7a").canonicalFile
        val s3Root = File(preparedRoot, "assets/riftpp/s3").canonicalFile
        val frontendRoot =
            File(preparedRoot, "assets/riftpp/frontend").canonicalFile
        val runtimeRoot =
            File(preparedRoot, "assets/riftpp/runtime").canonicalFile
        val sampleRoot =
            File(preparedRoot, "assets/riftpp/examples/notepad").canonicalFile
        val sampleSrcRoot =
            File(sampleRoot, "src").canonicalFile

        for (
            dir in listOf(
                libRoot,
                s3Root,
                frontendRoot,
                runtimeRoot,
                sampleRoot,
                sampleSrcRoot
            )
        ) {
            require(confinedTo(preparedRoot, dir)) {
                "Rift++ editor output escaped prepared package"
            }
            require(dir.mkdirs() || dir.isDirectory) {
                "Could not create Rift++ editor output directory"
            }
        }

        val manifestOutput =
            File(preparedRoot, "AndroidManifest.xml").canonicalFile
        val hostOutput =
            File(libRoot, RIFTPP_EDITOR_LIBRARY_FILE).canonicalFile
        val compilerOutput =
            File(s3Root, "compiler.arm32.native.hex").canonicalFile
        val app2FrontendOutput =
            File(frontendRoot, "frontend.app2.arm32.r4.hex").canonicalFile
        val projectFrontendOutput =
            File(frontendRoot, "frontend.project1.arm32.r4.hex").canonicalFile
        val runtimeOutput =
            File(runtimeRoot, "runtime.app2.arm32.r4.hex").canonicalFile
        val sampleMainOutput =
            File(sampleSrcRoot, "main.riftpp").canonicalFile
        val sampleUiOutput =
            File(sampleSrcRoot, "ui.riftpp").canonicalFile
        val sampleManifestOutput =
            File(sampleRoot, "app.rift.json").canonicalFile

        atomicWrite(manifestOutput, buildRiftppEditorBinaryManifest())
        atomicWrite(hostOutput, host)
        atomicWrite(compilerOutput, compiler)
        atomicWrite(app2FrontendOutput, app2Frontend)
        atomicWrite(projectFrontendOutput, projectFrontend)
        atomicWrite(runtimeOutput, runtime)
        atomicWrite(sampleMainOutput, sampleMain)
        atomicWrite(sampleUiOutput, sampleUi)
        atomicWrite(sampleManifestOutput, sampleManifest)

        val dexReceipt = JSONArray()

        for ((name, bytes) in dexEntries) {
            require(DEX_ENTRY.matches(name)) {
                "Unsafe Rift++ editor DEX output name: " + name
            }

            val output = File(preparedRoot, name).canonicalFile
            require(confinedTo(preparedRoot, output)) {
                "Rift++ editor DEX output escaped prepared package"
            }

            atomicWrite(output, bytes)

            require(sha256(output) == sha256(bytes)) {
                "Rift++ editor DEX materialization hash mismatch: " + name
            }

            dexReceipt.put(
                JSONObject()
                    .put("name", name)
                    .put("bytes", bytes.size)
                    .put("sha256", sha256(bytes))
            )
        }

        require(isBinaryAndroidManifest(manifestOutput)) {
            "Rift++ editor binary AndroidManifest.xml failed validation"
        }
        require(sha256(hostOutput) == sha256(host)) {
            "Rift++ editor JNI bridge materialization hash mismatch"
        }
        require(sha256(compilerOutput) == RIFTPP_EDITOR_COMPILER_SHA256) {
            "Rift++ editor S3 asset materialization hash mismatch"
        }
        require(
            sha256(app2FrontendOutput) ==
                RIFTPP_EDITOR_APP2_FRONTEND_SHA256
        ) {
            "Rift++ editor App v2 frontend asset materialization hash mismatch"
        }
        require(
            sha256(projectFrontendOutput) ==
                RIFTPP_EDITOR_PROJECT_FRONTEND_SHA256
        ) {
            "Rift++ editor Project v1 frontend asset materialization hash mismatch"
        }
        require(sha256(runtimeOutput) == RIFTPP_EDITOR_RUNTIME_SHA256) {
            "Rift++ editor runtime asset materialization hash mismatch"
        }
        require(
            sha256(sampleMainOutput) ==
                RIFTPP_EDITOR_SAMPLE_MAIN_SHA256
        ) {
            "Rift++ editor sample entry materialization hash mismatch"
        }
        require(
            sha256(sampleUiOutput) ==
                RIFTPP_EDITOR_SAMPLE_UI_SHA256
        ) {
            "Rift++ editor sample UI materialization hash mismatch"
        }
        require(
            sha256(sampleManifestOutput) ==
                RIFTPP_EDITOR_SAMPLE_MANIFEST_SHA256
        ) {
            "Rift++ editor sample manifest materialization hash mismatch"
        }

        val sourceReceipt = JSONObject()
        RIFTPP_EDITOR_SOURCE_SHA256.forEach { (sourcePath, expectedSha) ->
            sourceReceipt.put(sourcePath, expectedSha)
        }

        val runId = runId()
        val result = JSONObject()
            .put("format", "riftbuild-riftpp-editor-materialization-v3")
            .put("runId", runId)
            .put("state", "prepared-code")
            .put("project", ref.display)
            .put("androidProject", editorDisplay)
            .put("target", "arm32")
            .put("package", RIFTPP_EDITOR_PACKAGE)
            .put("activity", RIFTPP_EDITOR_ACTIVITY)
            .put("libraryName", RIFTPP_EDITOR_LIBRARY_NAME)
            .put("libraryFile", RIFTPP_EDITOR_LIBRARY_FILE)
            .put("hostSource", "self-apk:" + RIFTPP_EDITOR_HOST_APK_ENTRY)
            .put("hostBytes", host.size)
            .put("hostSha256", sha256(host))
            .put("dexSource", "self-apk:classes*.dex")
            .put("dex", dexReceipt)
            .put("localSourceAuthority", sourceReceipt)
            .put("compilerSource", RIFTPP_EDITOR_COMPILER)
            .put("compilerSha256", sha256(compiler))
            .put("app2FrontendSource", RIFTPP_EDITOR_APP2_FRONTEND)
            .put("app2FrontendSha256", sha256(app2Frontend))
            .put("projectFrontendSource", RIFTPP_EDITOR_PROJECT_FRONTEND)
            .put("projectFrontendSha256", sha256(projectFrontend))
            .put("runtimeSource", RIFTPP_EDITOR_RUNTIME)
            .put("runtimeSha256", sha256(runtime))
            .put("sampleMain", RIFTPP_EDITOR_SAMPLE_MAIN)
            .put("sampleMainSha256", sha256(sampleMain))
            .put("sampleUi", RIFTPP_EDITOR_SAMPLE_UI)
            .put("sampleUiSha256", sha256(sampleUi))
            .put("sampleManifest", RIFTPP_EDITOR_SAMPLE_MANIFEST)
            .put("sampleManifestSha256", sha256(sampleManifest))
            .put(
                "antiContamination",
                JSONObject()
                    .put("temporaryPlatformShell", "Kotlin/Android Activity")
                    .put("filesystemAuthority", "bounded project transport only")
                    .put("projectMetadataAuthority", "app.rift.json transport metadata")
                    .put("compilerAuthority", "Rift++ editor native lane")
                    .put("bootstrapCompilerAuthority", "frozen Rift++ S3 ARM32 recovery root")
                    .put("s3NextBootstrapAuthority", "workspace-supplied promoted S2 Generation-C native compiler")
                    .put("s3NextOpcodeSurface", "full 21-op 00..14")
                    .put("developmentCompilerAuthority", "workspace-supplied Rift++ S3 Next")
                    .put("legacyFrontendAuthority", "Rift++ App v2 record source")
                    .put("projectFrontendAuthority", "Rift++ Project v1 record source")
                    .put("runtimeAuthority", "Rift++ App v2 record source")
                    .put("uiProtocol", "RUI2")
                    .put("kotlinParsesRiftpp", false)
                    .put("kotlinTransformsRiftpp", false)
                    .put("kotlinEmitsRpa2", false)
                    .put("kotlinInterpretsRpa2", false)
                    .put("kotlinRendersGenericRui2", true)
                    .put("temporaryApkPackSign", true)
                    .put(
                        "replacementTarget",
                        "native Rift++ editor/filesystem/compiler/runtime/packer/signer"
                    )
                    .put("remoteBuildRequired", false)
            )
            .put("manifestReady", true)
            .put("dexReady", true)
            .put("signed", false)
            .put("installableClaimed", false)
            .put("createdAt", System.currentTimeMillis())

        atomicWrite(
            File(buildRoot, "riftpp-editor-materialization.json"),
            result.toString(2).toByteArray(Charsets.UTF_8)
        )

        writeRun(result)
        return result
    }


    @Synchronized
    fun prepareCodynexEditor(
        project: String,
        cwd: String = "/D:/Workspace"
    ): JSONObject {
        val ref = resolveProject(project, cwd)

        EDITOR_SOURCE_SHA256.forEach { (path, expectedSha) ->
            verifyProjectSource(ref, path, expectedSha)
        }

        val editorProject = projectFile(ref, EDITOR_PROJECT)
        require(editorProject.isDirectory) {
            "Codynex E0 editor project is missing"
        }
        val editorDisplay = projectDisplay(ref, editorProject)
        val sourceValidation = validate(editorDisplay, "/D:/Workspace")
        require(sourceValidation.optBoolean("sourceReady")) {
            "Codynex E0 editor source validation failed"
        }
        require(sourceValidation.optBoolean("requiresDex")) {
            "Codynex E0 editor must remain a code-bearing Activity package"
        }
        require(sourceValidation.optString("activityName") == EDITOR_ACTIVITY) {
            "Codynex E0 editor launch activity drift"
        }

        val vmFile = projectFile(ref, EDITOR_VM_HEX)
        require(vmFile.isFile) { "Codynex E0 VM1 hex authority is missing" }
        val vmText = readTextBounded(vmFile).toByteArray(Charsets.UTF_8)
        require(vmText.size == EDITOR_VM_HEX_BYTES) {
            "Codynex E0 VM1 hex byte count drift: " + vmText.size
        }
        require(sha256(vmText) == EDITOR_VM_HEX_SHA256) {
            "Codynex E0 VM1 hex SHA-256 drift"
        }

        val compilerFile = projectFile(ref, EDITOR_COMPILER_HEX)
        require(compilerFile.isFile) {
            "Codynex E0 compiler hex authority is missing"
        }
        val compilerText =
            readTextBounded(compilerFile).toByteArray(Charsets.UTF_8)
        require(compilerText.size == EDITOR_COMPILER_HEX_BYTES) {
            "Codynex E0 compiler hex byte count drift: " + compilerText.size
        }
        require(sha256(compilerText) == EDITOR_COMPILER_HEX_SHA256) {
            "Codynex E0 compiler hex SHA-256 drift"
        }

        val sourceFile = projectFile(ref, EDITOR_SOURCE0)
        require(sourceFile.isFile) {
            "Codynex E0 Source0 authority is missing"
        }
        val sourceText =
            readTextBounded(sourceFile).toByteArray(Charsets.UTF_8)
        require(sourceText.size == EDITOR_SOURCE0_BYTES) {
            "Codynex E0 Source0 byte count drift: " + sourceText.size
        }
        require(sha256(sourceText) == EDITOR_SOURCE0_SHA256) {
            "Codynex E0 Source0 SHA-256 drift"
        }

        val host =
            readOwnApkEntry(EDITOR_HOST_APK_ENTRY, EDITOR_MAX_HOST_BYTES)
        verifyElfImage(host, 1, 40)

        val dexEntries = readOwnDexEntries()
        require(dexEntries.isNotEmpty()) {
            "Installed RiftOS APK contains no editor DEX payload"
        }
        dexEntries.forEach { (name, bytes) ->
            require(bytes.size >= 8) {
                "RiftOS DEX payload is too small: " + name
            }
            require(
                bytes[0] == 'd'.code.toByte() &&
                    bytes[1] == 'e'.code.toByte() &&
                    bytes[2] == 'x'.code.toByte() &&
                    bytes[3] == '\n'.code.toByte() &&
                    bytes[7] == 0.toByte()
            ) {
                "RiftOS DEX payload has invalid magic: " + name
            }
        }

        val buildRoot = File(editorProject, "build/riftbuild").canonicalFile
        require(confinedTo(editorProject, buildRoot)) {
            "Codynex E0 build root escaped editor project"
        }
        val preparedRoot = File(buildRoot, "prepared").canonicalFile
        require(confinedTo(buildRoot, preparedRoot)) {
            "Codynex E0 prepared root escaped build/riftbuild"
        }
        if (preparedRoot.exists()) {
            require(deleteTreeBounded(preparedRoot, MAX_PROJECT_FILES)) {
                "Could not clear stale Codynex E0 prepared package"
            }
        }

        val libRoot = File(preparedRoot, "lib/armeabi-v7a").canonicalFile
        val assetRoot = File(preparedRoot, "assets").canonicalFile
        require(confinedTo(preparedRoot, libRoot)) {
            "Codynex E0 library root escaped prepared package"
        }
        require(confinedTo(preparedRoot, assetRoot)) {
            "Codynex E0 asset root escaped prepared package"
        }
        require(libRoot.mkdirs() || libRoot.isDirectory) {
            "Could not create Codynex E0 library directory"
        }
        require(assetRoot.mkdirs() || assetRoot.isDirectory) {
            "Could not create Codynex E0 asset directory"
        }

        val manifestBytes = buildEditorBinaryManifest()
        val manifestOutput =
            File(preparedRoot, "AndroidManifest.xml").canonicalFile
        val hostOutput =
            File(libRoot, EDITOR_LIBRARY_FILE).canonicalFile
        val vmOutput = File(assetRoot, "vm1_seed.hex").canonicalFile
        val compilerOutput =
            File(assetRoot, "selfhost_compiler.hex").canonicalFile
        val sourceOutput =
            File(assetRoot, "selfhost_compiler.cx0").canonicalFile

        atomicWrite(manifestOutput, manifestBytes)
        atomicWrite(hostOutput, host)
        atomicWrite(vmOutput, vmText)
        atomicWrite(compilerOutput, compilerText)
        atomicWrite(sourceOutput, sourceText)

        val dexReceipt = JSONArray()
        for ((name, bytes) in dexEntries) {
            require(DEX_ENTRY.matches(name)) {
                "Unsafe Codynex E0 DEX output name: " + name
            }
            val output = File(preparedRoot, name).canonicalFile
            require(confinedTo(preparedRoot, output)) {
                "Codynex E0 DEX output escaped prepared package"
            }
            atomicWrite(output, bytes)
            require(sha256(output) == sha256(bytes)) {
                "Codynex E0 DEX materialization hash mismatch: " + name
            }
            dexReceipt.put(
                JSONObject()
                    .put("name", name)
                    .put("bytes", bytes.size)
                    .put("sha256", sha256(bytes))
            )
        }

        require(isBinaryAndroidManifest(manifestOutput)) {
            "Codynex E0 binary AndroidManifest.xml failed validation"
        }
        require(sha256(hostOutput) == sha256(host)) {
            "Codynex E0 VM bridge materialization hash mismatch"
        }
        require(sha256(vmOutput) == EDITOR_VM_HEX_SHA256) {
            "Codynex E0 VM1 asset materialization hash mismatch"
        }
        require(sha256(compilerOutput) == EDITOR_COMPILER_HEX_SHA256) {
            "Codynex E0 compiler asset materialization hash mismatch"
        }
        require(sha256(sourceOutput) == EDITOR_SOURCE0_SHA256) {
            "Codynex E0 Source0 asset materialization hash mismatch"
        }

        val sourceReceipt = JSONObject()
        EDITOR_SOURCE_SHA256.forEach { (path, expectedSha) ->
            sourceReceipt.put(path, expectedSha)
        }

        val runId = runId()
        val result = JSONObject()
            .put("format", "riftbuild-codynex-editor-materialization-v1")
            .put("runId", runId)
            .put("state", "prepared-code")
            .put("project", ref.display)
            .put("androidProject", editorDisplay)
            .put("target", "arm32")
            .put("package", EDITOR_PACKAGE)
            .put("activity", EDITOR_ACTIVITY)
            .put("libraryName", EDITOR_LIBRARY_NAME)
            .put("libraryFile", EDITOR_LIBRARY_FILE)
            .put("hostSource", "self-apk:" + EDITOR_HOST_APK_ENTRY)
            .put("hostBytes", host.size)
            .put("hostSha256", sha256(host))
            .put("dexSource", "self-apk:classes*.dex")
            .put("dex", dexReceipt)
            .put("localSourceAuthority", sourceReceipt)
            .put("vmSource", projectDisplay(ref, vmFile))
            .put("vmBytes", vmText.size)
            .put("vmSha256", sha256(vmText))
            .put("compilerSource", projectDisplay(ref, compilerFile))
            .put("compilerBytes", compilerText.size)
            .put("compilerSha256", sha256(compilerText))
            .put("externalSource", projectDisplay(ref, sourceFile))
            .put("externalSourceBytes", sourceText.size)
            .put("externalSourceSha256", sha256(sourceText))
            .put(
                "manifest",
                JSONObject()
                    .put("path", projectDisplay(ref, manifestOutput))
                    .put("bytes", manifestOutput.length())
                    .put("sha256", sha256(manifestOutput))
            )
            .put(
                "antiContamination",
                JSONObject()
                    .put("editorCoreLanguageAgnostic", true)
                    .put("editorSourceAuthority", "local Codynex external/editor")
                    .put("runtimeAuthority", "assets/vm1_seed.hex")
                    .put("compilerAuthority", "assets/selfhost_compiler.hex")
                    .put("sourceAuthority", "assets/selfhost_compiler.cx0")
                    .put("remoteBuildRequired", false)
            )
            .put("manifestReady", true)
            .put("dexReady", true)
            .put("signed", false)
            .put("installableClaimed", false)
            .put("createdAt", System.currentTimeMillis())

        atomicWrite(
            File(buildRoot, "codynex-editor-materialization.json"),
            result.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(result)
        return result
    }


    private fun runVm1Bounded(
        program: ByteArray,
        source: ByteArray,
        outputCapacity: Int,
        stepBudget: Int
    ): Vm1Run {
        if (program.isEmpty() || program.size % 4 != 0) {
            return Vm1Run(-4, 0, ByteArray(outputCapacity.coerceAtLeast(0)), 0)
        }
        require(outputCapacity in 0..RIFTPP_APP0_MAX_PROGRAM_BYTES) {
            "VM1 output capacity exceeds App0 bound"
        }
        require(stepBudget in 1..RIFTPP_APP0_STEP_BUDGET) {
            "VM1 step budget exceeds App0 bound"
        }

        val output = ByteArray(outputCapacity)
        val regs = IntArray(8)
        val instructionCount = program.size / 4
        var pc = 0
        var steps = 0

        fun invalid(): Vm1Run = Vm1Run(-1, 0, output, steps)

        while (true) {
            if (pc !in 0 until instructionCount) {
                return Vm1Run(-3, 0, output, steps)
            }
            if (steps >= stepBudget) {
                return Vm1Run(-2, 0, output, steps)
            }
            steps += 1

            val base = pc * 4
            val op = program[base].toInt() and 0xff
            val a = program[base + 1].toInt() and 0xff
            val b = program[base + 2].toInt() and 0xff
            val c = program[base + 3].toInt() and 0xff
            pc += 1

            when (op) {
                1 -> {
                    if (a >= 8 || c != 0) return invalid()
                    regs[a] = b
                }
                2 -> {
                    if (a >= 8 || b >= 8 || c >= 8) return invalid()
                    regs[a] = regs[b] + regs[c]
                }
                3 -> {
                    if (a >= 8 || b != 0 || c != 0) return invalid()
                    return Vm1Run(0, regs[a], output, steps)
                }
                4 -> {
                    if (a >= 8 || b >= 8 || c >= 8) return invalid()
                    regs[a] = regs[b] - regs[c]
                }
                5 -> {
                    if (a >= 8 || b >= 8 || c >= 8) return invalid()
                    regs[a] = if (regs[b] == regs[c]) 1 else 0
                }
                6 -> {
                    if (a >= 8 || b >= 8 || c >= 8) return invalid()
                    regs[a] = if (Integer.compareUnsigned(regs[b], regs[c]) < 0) 1 else 0
                }
                7 -> {
                    if (a >= 8) return invalid()
                    if (regs[a] != 0) {
                        val target = b or (c shl 8)
                        if (target !in 0 until instructionCount) return invalid()
                        pc = target
                    }
                }
                8 -> {
                    if (a >= 8 || b >= 3 || c != 0) return invalid()
                    regs[a] = when (b) {
                        0 -> source.size
                        1 -> output.size
                        else -> 0
                    }
                }
                9 -> {
                    if (a >= 8 || c >= 8) return invalid()
                    val index = Integer.toUnsignedLong(regs[c])
                    when (b) {
                        0 -> {
                            if (index >= source.size.toLong()) return invalid()
                            regs[a] = source[index.toInt()].toInt() and 0xff
                        }
                        2 -> return invalid()
                        else -> return invalid()
                    }
                }
                10 -> {
                    if (a >= 8 || c >= 8) return invalid()
                    val index = Integer.toUnsignedLong(regs[c])
                    when (b) {
                        1 -> {
                            if (index >= output.size.toLong()) return invalid()
                            output[index.toInt()] = (regs[a] and 0xff).toByte()
                        }
                        2 -> return invalid()
                        else -> return invalid()
                    }
                }
                else -> return invalid()
            }
        }
    }

    private fun analyzeApp0Vm1(program: ByteArray): App0Requirements {
        require(program.isNotEmpty() && program.size % 4 == 0) {
            "Rift++ App0 emitted VM1 must be non-empty fixed-width instructions"
        }

        val instructionCount = program.size / 4
        var sourceBytes = false
        var outputBytes = false
        var scratchBytes = false

        for (pc in 0 until instructionCount) {
            val base = pc * 4
            val op = program[base].toInt() and 0xff
            val a = program[base + 1].toInt() and 0xff
            val b = program[base + 2].toInt() and 0xff
            val c = program[base + 3].toInt() and 0xff

            when (op) {
                1 -> require(a < 8 && c == 0) { "Invalid VM1 const at instruction $pc" }
                2, 4, 5, 6 -> require(a < 8 && b < 8 && c < 8) {
                    "Invalid VM1 register operation at instruction $pc"
                }
                3 -> require(a < 8 && b == 0 && c == 0) {
                    "Invalid VM1 return at instruction $pc"
                }
                7 -> {
                    require(a < 8) { "Invalid VM1 branch register at instruction $pc" }
                    val target = b or (c shl 8)
                    require(target in 0 until instructionCount) {
                        "Invalid VM1 branch target at instruction $pc"
                    }
                }
                8 -> {
                    require(a < 8 && b < 3 && c == 0) {
                        "Invalid VM1 length operation at instruction $pc"
                    }
                    when (b) {
                        0 -> sourceBytes = true
                        1 -> outputBytes = true
                        2 -> scratchBytes = true
                    }
                }
                9 -> {
                    require(a < 8 && c < 8 && (b == 0 || b == 2)) {
                        "Invalid VM1 read8 operation at instruction $pc"
                    }
                    if (b == 0) sourceBytes = true else scratchBytes = true
                }
                10 -> {
                    require(a < 8 && c < 8 && (b == 1 || b == 2)) {
                        "Invalid VM1 write8 operation at instruction $pc"
                    }
                    if (b == 1) outputBytes = true else scratchBytes = true
                }
                else -> error("Unknown VM1 opcode $op at instruction $pc")
            }
        }

        return App0Requirements(
            instructionCount = instructionCount,
            requiresSourceBytes = sourceBytes,
            requiresOutputBytes = outputBytes,
            requiresScratchBytes = scratchBytes
        )
    }

    private fun buildNativeActivityBinaryManifest(
        packageName: String,
        versionCode: Int,
        versionName: String,
        libraryName: String
    ): ByteArray {
        val strings = listOf(
            "name", "hasCode", "exported", "value", "minSdkVersion", "versionCode",
            "versionName", "targetSdkVersion", "android",
            "http://schemas.android.com/apk/res/android", "manifest", "package",
            packageName, versionCode.toString(), versionName, "uses-sdk", "26", "36",
            "application", "false", "activity", "android.app.NativeActivity", "true",
            "meta-data", "android.app.lib_name", libraryName, "intent-filter", "action",
            "android.intent.action.MAIN", "category", "android.intent.category.LAUNCHER"
        )

        fun index(value: String): Int {
            val found = strings.indexOf(value)
            require(found >= 0) { "NativeActivity manifest string is not in pool: $value" }
            return found
        }

        fun stringAttr(
            name: String,
            value: String,
            namespace: Int = index("http://schemas.android.com/apk/res/android")
        ): ManifestAttr = ManifestAttr(
            namespace,
            index(name),
            index(value),
            XML_VALUE_STRING,
            index(value)
        )

        fun intAttr(name: String, rawValue: String, value: Int): ManifestAttr = ManifestAttr(
            index("http://schemas.android.com/apk/res/android"),
            index(name),
            index(rawValue),
            XML_VALUE_INT_DEC,
            value
        )

        fun boolAttr(name: String, rawValue: String, value: Boolean): ManifestAttr = ManifestAttr(
            index("http://schemas.android.com/apk/res/android"),
            index(name),
            index(rawValue),
            XML_VALUE_INT_BOOLEAN,
            if (value) -1 else 0
        )

        fun stringPool(): ByteArray {
            val offsets = ArrayList<Int>(strings.size)
            val data = ByteArrayOutputStream()
            for (value in strings) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                require(value.length < 0x80 && bytes.size < 0x80) {
                    "NativeActivity manifest string exceeds one-byte UTF-8 pool length"
                }
                offsets.add(data.size())
                writeManifestLength8(data, value.length)
                writeManifestLength8(data, bytes.size)
                data.write(bytes)
                data.write(0)
            }
            while (data.size() % 4 != 0) data.write(0)

            val stringsStart = 28 + (strings.size * 4)
            val dataBytes = data.toByteArray()
            val output = ByteArrayOutputStream()
            writeManifestChunkHeader(
                output,
                XML_STRING_POOL_TYPE,
                28,
                stringsStart + dataBytes.size
            )
            writeManifestU32(output, strings.size)
            writeManifestU32(output, 0)
            writeManifestU32(output, XML_UTF8_FLAG)
            writeManifestU32(output, stringsStart)
            writeManifestU32(output, 0)
            for (offset in offsets) writeManifestU32(output, offset)
            output.write(dataBytes)
            return output.toByteArray()
        }

        fun namespace(type: Int): ByteArray {
            val output = ByteArrayOutputStream()
            writeManifestNodeHeader(output, type, 24)
            writeManifestU32(output, index("android"))
            writeManifestU32(output, index("http://schemas.android.com/apk/res/android"))
            return output.toByteArray()
        }

        fun startElement(name: String, attrs: List<ManifestAttr>): ByteArray {
            val output = ByteArrayOutputStream()
            writeManifestNodeHeader(output, XML_START_ELEMENT_TYPE, 36 + (attrs.size * 20))
            writeManifestU32(output, XML_NO_INDEX)
            writeManifestU32(output, index(name))
            writeManifestU16(output, 20)
            writeManifestU16(output, 20)
            writeManifestU16(output, attrs.size)
            writeManifestU16(output, 0)
            writeManifestU16(output, 0)
            writeManifestU16(output, 0)
            for (attr in attrs) {
                writeManifestU32(output, attr.namespace)
                writeManifestU32(output, attr.name)
                writeManifestU32(output, attr.rawValue)
                writeManifestU16(output, 8)
                output.write(0)
                output.write(attr.dataType)
                writeManifestU32(output, attr.data)
            }
            return output.toByteArray()
        }

        fun endElement(name: String): ByteArray {
            val output = ByteArrayOutputStream()
            writeManifestNodeHeader(output, XML_END_ELEMENT_TYPE, 24)
            writeManifestU32(output, XML_NO_INDEX)
            writeManifestU32(output, index(name))
            return output.toByteArray()
        }

        val body = ByteArrayOutputStream()
        body.write(stringPool())
        body.write(buildManifestResourceMap())
        body.write(namespace(XML_START_NAMESPACE_TYPE))
        body.write(startElement(
            "manifest",
            listOf(
                stringAttr("package", packageName, XML_NO_INDEX),
                intAttr("versionCode", versionCode.toString(), versionCode),
                stringAttr("versionName", versionName)
            )
        ))
        body.write(startElement(
            "uses-sdk",
            listOf(
                intAttr("minSdkVersion", "26", 26),
                intAttr("targetSdkVersion", "36", 36)
            )
        ))
        body.write(endElement("uses-sdk"))
        body.write(startElement(
            "application",
            listOf(boolAttr("hasCode", "false", false))
        ))
        body.write(startElement(
            "activity",
            listOf(
                stringAttr("name", "android.app.NativeActivity"),
                boolAttr("exported", "true", true)
            )
        ))
        body.write(startElement(
            "meta-data",
            listOf(
                stringAttr("name", "android.app.lib_name"),
                stringAttr("value", libraryName)
            )
        ))
        body.write(endElement("meta-data"))
        body.write(startElement("intent-filter", emptyList()))
        body.write(startElement(
            "action",
            listOf(stringAttr("name", "android.intent.action.MAIN"))
        ))
        body.write(endElement("action"))
        body.write(startElement(
            "category",
            listOf(stringAttr("name", "android.intent.category.LAUNCHER"))
        ))
        body.write(endElement("category"))
        body.write(endElement("intent-filter"))
        body.write(endElement("activity"))
        body.write(endElement("application"))
        body.write(endElement("manifest"))
        body.write(namespace(XML_END_NAMESPACE_TYPE))

        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_TYPE, 8, 8 + bodyBytes.size)
        output.write(bodyBytes)
        return output.toByteArray()
    }

    private fun decodeHex(raw: String): ByteArray {
        require(raw.isNotBlank() && raw.length % 2 == 0) {
            "Codynex machine-seed hex must contain complete byte pairs"
        }
        require(raw.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Codynex machine-seed hex contains non-hex characters"
        }
        return ByteArray(raw.length / 2) { index ->
            raw.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun readOwnApkEntry(entryName: String, maxBytes: Long): ByteArray {
        require(entryName.startsWith("lib/") && safeZipPath(entryName)) {
            "Unsafe RiftOS self-APK entry"
        }
        val apk = File(appContext.applicationInfo.sourceDir).canonicalFile
        require(apk.isFile) { "Installed RiftOS base APK is unavailable" }
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(entryName)
                ?: throw IllegalStateException(
                    "Installed RiftOS APK does not contain " + entryName +
                        "; rebuild/install RiftOS with the requested native proof host first"
                )
            require(!entry.isDirectory) { "RiftOS native proof host entry is not a file" }
            require(entry.size < 0L || entry.size <= maxBytes) {
                "RiftOS native proof host exceeds bounded extraction limit"
            }
            val output = ByteArrayOutputStream()
            zip.getInputStream(entry).buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    RiftDeadline.check("RiftBuild native proof host extraction")
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    require(total <= maxBytes) {
                        "RiftOS native proof host exceeds bounded extraction limit"
                    }
                    output.write(buffer, 0, read)
                }
            }
            return output.toByteArray()
        }
    }

    private fun readOwnDexEntries(): List<Pair<String, ByteArray>> {
        val apk = File(appContext.applicationInfo.sourceDir).canonicalFile
        require(apk.isFile) { "Installed RiftOS base APK is unavailable" }

        ZipFile(apk).use { zip ->
            val names = ArrayList<String>()
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                if (!entry.isDirectory && DEX_ENTRY.matches(entry.name)) {
                    names += entry.name
                }
            }

            require(names.contains("classes.dex")) {
                "Installed RiftOS APK does not contain classes.dex"
            }

            val ordered = names.sortedBy(::dexEntryOrder)
            val output = ArrayList<Pair<String, ByteArray>>(ordered.size)
            var totalBytes = 0L

            for (name in ordered) {
                RiftDeadline.check("RiftBuild editor DEX extraction")
                val entry = zip.getEntry(name)
                    ?: error("RiftOS DEX entry disappeared: " + name)
                require(entry.size < 0L || entry.size <= EDITOR_MAX_DEX_BYTES) {
                    "RiftOS DEX entry exceeds editor extraction limit: " + name
                }

                val bytes = ByteArrayOutputStream()
                zip.getInputStream(entry).buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var entryBytes = 0L
                    while (true) {
                        RiftDeadline.check("RiftBuild editor DEX extraction")
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        entryBytes += read
                        totalBytes += read
                        require(entryBytes <= EDITOR_MAX_DEX_BYTES) {
                            "RiftOS DEX entry exceeds editor extraction limit: " + name
                        }
                        require(totalBytes <= EDITOR_MAX_TOTAL_DEX_BYTES) {
                            "RiftOS DEX payload exceeds editor total extraction limit"
                        }
                        bytes.write(buffer, 0, read)
                    }
                }
                output += name to bytes.toByteArray()
            }

            return output
        }
    }

    fun submit(args: JSONObject, cwd: String = "/D:/Workspace"): JSONObject {
        require(!args.has("command") && !args.has("shell") && !args.has("exec")) { "RiftBuild does not accept raw commands" }
        val project = args.optString("project").ifBlank { args.optString("projectPath") }
        require(project.isNotBlank()) { "build.submit requires project" }
        val target = normalizeTarget(args.optString("target", "universal"))
        val currentPlan = plan(project, target, cwd)
        return if (currentPlan.optBoolean("packReady")) pack(project, target, cwd)
        else blockedRun(resolveProject(project, cwd), target, currentPlan)
    }

    @Synchronized
    fun pack(project: String, target: String = "universal", cwd: String = "/D:/Workspace"): JSONObject {
        val normalizedTarget = normalizeTarget(target)
        val ref = resolveProject(project, cwd)
        val currentPlan = plan(project, normalizedTarget, cwd)
        if (!currentPlan.optBoolean("packReady")) return blockedRun(ref, normalizedTarget, currentPlan)

        val prepared = File(ref.file, "build/riftbuild/prepared").canonicalFile
        val entries = collectPreparedEntries(prepared, normalizedTarget)
        val id = runId()
        val outDir = File(artifactProjectRoot(ref), id).apply { mkdirs() }.canonicalFile
        require(confinedTo(artifactRoot, outDir)) { "RiftBuild output escaped D:/Builds" }
        val apk = File(outDir, safeName(ref.file.name) + "-" + normalizedTarget + "-unsigned.apk")
        val temp = File(outDir, "." + apk.name + ".tmp")
        val seen = linkedSetOf<String>()
        var total = 0L

        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                for ((entryName, source) in entries) {
                    require(seen.add(entryName)) { "duplicate APK entry: " + entryName }
                    require(seen.size <= MAX_PACKAGE_FILES) { "APK entry-count limit exceeded" }
                    total += source.length()
                    require(total <= MAX_PACKAGE_BYTES) { "APK package byte limit exceeded" }
                    zip.putNextEntry(ZipEntry(entryName).apply { time = 0L })
                    source.inputStream().buffered().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            RiftDeadline.check("RiftBuild APK pack")
                            val read = input.read(buffer)
                            if (read <= 0) break
                            zip.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(temp.renameTo(apk)) { "could not publish unsigned APK" }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }

        val receipt = JSONObject()
            .put("format", "riftbuild-apk-package-receipt-v1")
            .put("state", "packaged-unsigned")
            .put("runId", id)
            .put("project", ref.display)
            .put("projectSha256", currentPlan.optString("projectSha256"))
            .put("target", normalizedTarget)
            .put("artifact", artifactDisplay(apk))
            .put("artifactSha256", sha256(apk))
            .put("artifactBytes", apk.length())
            .put("entries", seen.size)
            .put("signed", false)
            .put("installableClaimed", false)
            .put("blockers", JSONArray()
                .put("run riftbuild sign on this bounded unsigned artifact")
                .put("install is allowed only after independent v2 verification and Android user confirmation"))
            .put("createdAt", System.currentTimeMillis())
        atomicWrite(File(outDir, "receipt.json"), receipt.toString(2).toByteArray(Charsets.UTF_8))
        writeRun(receipt)
        return receipt
    }


    @Synchronized
    fun signArtifact(rawArtifact: String): JSONObject {
        val unsignedApk = resolveArtifact(rawArtifact)
        require(unsignedApk.name.endsWith("-unsigned.apk")) { "RiftBuild sign accepts only *-unsigned.apk artifacts" }
        val signedApk = File(
            unsignedApk.parentFile,
            unsignedApk.name.removeSuffix("-unsigned.apk") + "-signed.apk"
        ).canonicalFile
        require(confinedTo(artifactRoot, signedApk)) { "signed APK output escaped D:/Builds" }

        val signed = apkSigner.sign(unsignedApk, signedApk)
        val id = runId()
        val receipt = JSONObject()
            .put("format", "riftbuild-apk-v2-signing-receipt-v1")
            .put("state", "signed-v2")
            .put("runId", id)
            .put("inputArtifact", artifactDisplay(unsignedApk))
            .put("inputSha256", sha256(unsignedApk))
            .put("artifact", artifactDisplay(signedApk))
            .put("artifactSha256", signed.apkSha256)
            .put("artifactBytes", signed.outputBytes)
            .put("scheme", 2)
            .put("signatureAlgorithmId", "0x0103")
            .put("certificateSha256", signed.certificateSha256)
            .put("publicKeySha256", signed.publicKeySha256)
            .put("contentDigestSha256", signed.contentDigestSha256)
            .put("signingBlockBytes", signed.signingBlockBytes)
            .put("signatureVerified", true)
            .put("installableClaimed", false)
            .put("createdAt", System.currentTimeMillis())
        atomicWrite(
            File(signedApk.parentFile, "signing-receipt.json"),
            receipt.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(receipt)
        return receipt
    }

    fun verifyArtifact(rawArtifact: String): JSONObject {
        val signedApk = resolveArtifact(rawArtifact)
        require(signedApk.name.endsWith("-signed.apk")) { "RiftBuild verify accepts only *-signed.apk artifacts" }
        val verified = apkSigner.verify(signedApk)
        val id = runId()
        val receipt = JSONObject()
            .put("format", "riftbuild-apk-v2-verification-receipt-v1")
            .put("state", "verified-v2")
            .put("runId", id)
            .put("artifact", artifactDisplay(signedApk))
            .put("artifactSha256", verified.apkSha256)
            .put("artifactBytes", signedApk.length())
            .put("scheme", 2)
            .put("signatureAlgorithmId", "0x0103")
            .put("certificateSha256", verified.certificateSha256)
            .put("publicKeySha256", verified.publicKeySha256)
            .put("contentDigestSha256", verified.contentDigestSha256)
            .put("signingBlockBytes", verified.signingBlockBytes)
            .put("signatureVerified", true)
            .put("installableClaimed", false)
            .put("verifiedAt", System.currentTimeMillis())
        atomicWrite(
            File(signedApk.parentFile, "verification-receipt.json"),
            receipt.toString(2).toByteArray(Charsets.UTF_8)
        )
        writeRun(receipt)
        return receipt
    }

    @Synchronized
    fun installProof(rawArtifact: String): JSONObject {
        val signedApk = resolveArtifact(rawArtifact)
        require(signedApk.name.endsWith("-signed.apk")) { "RiftBuild install-proof accepts only *-signed.apk artifacts" }
        val verified = apkSigner.verify(signedApk)
        val result = installer.installProof(signedApk, verified)
            .put("format", "riftbuild-install-proof-v1")
            .put("runId", runId())
            .put("artifact", artifactDisplay(signedApk))
            .put("artifactSha256", verified.apkSha256)
            .put("certificateSha256", verified.certificateSha256)
            .put("signatureVerified", true)
            .put("installableClaimed", false)
        writeRun(result)
        return result
    }

    fun runs(limit: Int = 20): JSONArray {
        val out = JSONArray()
        runRoot.listFiles()
            ?.filter { it.isFile && it.extension.equals("json", true) }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit.coerceIn(1, MAX_RUNS))
            ?.forEach { file ->
                if (file.length() <= MAX_TEXT_BYTES) {
                    runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()?.let(out::put)
                }
            }
        return out
    }

    fun artifacts(project: String? = null, cwd: String = "/D:/Workspace"): JSONArray {
        val root = if (project.isNullOrBlank()) artifactRoot else artifactProjectRoot(resolveProject(project, cwd))
        if (!root.isDirectory) return JSONArray()
        val out = JSONArray()
        var rows = 0
        root.walkTopDown().forEach { file ->
            RiftDeadline.check("RiftBuild artifact list")
            if (file == root) return@forEach
            rows += 1
            require(rows <= MAX_PACKAGE_FILES) { "RiftBuild artifact listing limit exceeded" }
            out.put(JSONObject()
                .put("path", artifactDisplay(file))
                .put("name", file.name)
                .put("kind", if (file.isDirectory) "directory" else "file")
                .put("size", if (file.isFile) file.length() else 0L)
                .put("modified", file.lastModified()))
        }
        return out
    }

    private fun resolveProject(raw: String, cwd: String): ProjectRef {
        require(raw.isNotBlank()) { "RiftBuild project path is required" }
        val rawDisplay = workspaceAlias(raw)
        val cwdDisplay = workspaceAlias(cwd)
        val joined = if (rawDisplay.startsWith("/")) rawDisplay else {
            val base = if (cwdDisplay == "/D:/Workspace" || cwdDisplay.startsWith("/D:/Workspace/")) cwdDisplay else "/D:/Workspace"
            base.trimEnd('/') + "/" + rawDisplay
        }
        val display = RiftVolumePaths.normalizeDisplay(joined)
        require(display == "/D:/Workspace" || display.startsWith("/D:/Workspace/")) { "RiftBuild projects must live under D:/Workspace" }
        val file = File(riftRoot, RiftVolumePaths.resolveRelative(display)).canonicalFile
        require(confinedTo(workspaceRoot, file)) { "RiftBuild project escaped workspace" }
        return ProjectRef(display, file)
    }

    private fun workspaceAlias(raw: String): String {
        val value = raw.trim().replace('\\', '/')
        return when {
            value == "/workspace" || value == "workspace" -> "/D:/Workspace"
            value.startsWith("/workspace/") -> "/D:/Workspace/" + value.removePrefix("/workspace/")
            value.startsWith("workspace/") -> "/D:/Workspace/" + value.removePrefix("workspace/")
            else -> value
        }
    }

    private fun normalizeTarget(raw: String): String {
        val value = raw.trim().lowercase().ifBlank { "universal" }
        require(TARGETS.contains(value)) { "RiftBuild target must be arm32, arm64 or universal" }
        return value
    }


    private fun readRiftppV0Image(
        ref: ProjectRef,
        exports: JSONObject,
        key: String,
        expectedAbi: String,
        expectedClass: Int,
        expectedMachine: Int
    ): RiftppV0Image {
        val value = exports.optJSONObject(key) ?: error("Rift++ V0 export is missing: " + key)
        require(value.optString("abi") == expectedAbi) { "Rift++ V0 ABI drift: " + key }
        require(value.optInt("elfClass") == if (expectedClass == 2) 64 else 32) { "Rift++ V0 ELF class metadata drift: " + key }
        require(value.optInt("machine") == expectedMachine) { "Rift++ V0 machine metadata drift: " + key }

        val sourcePath = value.optString("path")
        val sourceSha = value.optString("sourceSha256")
        verifyProjectSource(ref, sourcePath, sourceSha)

        val data = value.optJSONArray("data") ?: error("Rift++ V0 byte array is missing: " + key)
        val declaredBytes = value.optInt("bytes", -1)
        require(declaredBytes == data.length()) { "Rift++ V0 byte-count metadata drift: " + key }
        require(declaredBytes in 1..MAX_TEXT_BYTES.toInt()) { "Rift++ V0 byte array exceeds bounded bridge limit" }

        val bytes = ByteArray(data.length())
        for (index in 0 until data.length()) {
            val number = data.optInt(index, -1)
            require(number in 0..255) { "Rift++ V0 bridge byte outside 0..255 at " + key + "[" + index + "]" }
            bytes[index] = number.toByte()
        }

        val rawSha = value.optString("rawSha256")
        require(SHA256_HEX.matches(rawSha)) { "Rift++ V0 raw SHA-256 metadata is invalid: " + key }
        require(sha256(bytes) == rawSha) { "Rift++ V0 raw SHA-256 mismatch: " + key }

        val canonical = value.optString("canonicalValueSha256")
        require(SHA256_HEX.matches(canonical)) { "Rift++ V0 canonical value SHA-256 metadata is invalid: " + key }

        verifyElfImage(bytes, expectedClass, expectedMachine)
        return RiftppV0Image(
            key = key,
            abi = expectedAbi,
            elfClass = if (expectedClass == 2) 64 else 32,
            machine = expectedMachine,
            sourcePath = sourcePath,
            sourceSha256 = sourceSha,
            rawSha256 = rawSha,
            canonicalValueSha256 = canonical,
            bytes = bytes
        )
    }

    private fun verifyProjectSource(ref: ProjectRef, relative: String, expectedSha256: String) {
        require(safeZipPath(relative)) { "Unsafe Rift++ V0 source path" }
        require(SHA256_HEX.matches(expectedSha256)) { "Invalid Rift++ V0 source SHA-256" }
        val source = projectFile(ref, relative)
        require(source.isFile) { "Rift++ V0 source file is missing: " + relative }
        require(sha256(source) == expectedSha256) { "Rift++ V0 source identity drift: " + relative }
    }

    private fun verifyElfImage(bytes: ByteArray, expectedClass: Int, expectedMachine: Int) {
        require(bytes.size >= 40) { "Rift++ V0 ELF image is truncated" }
        require(
            (bytes[0].toInt() and 0xff) == 0x7f &&
                bytes[1].toInt() == 'E'.code &&
                bytes[2].toInt() == 'L'.code &&
                bytes[3].toInt() == 'F'.code
        ) { "Rift++ V0 ELF magic mismatch" }
        require((bytes[4].toInt() and 0xff) == expectedClass) { "Rift++ V0 ELF class mismatch" }
        require((bytes[5].toInt() and 0xff) == 1) { "Rift++ V0 ELF must be little-endian" }
        require((bytes[16].toInt() and 0xff) == 3 && (bytes[17].toInt() and 0xff) == 0) { "Rift++ V0 ELF must be ET_DYN" }
        val machine = (bytes[18].toInt() and 0xff) or ((bytes[19].toInt() and 0xff) shl 8)
        require(machine == expectedMachine) { "Rift++ V0 ELF machine mismatch" }
        if (expectedClass == 1 && expectedMachine == 40) {
            require((bytes[39].toInt() and 0xff) == 5) { "Rift++ V0 ARM ELF must declare EABI5" }
        }
    }

    private fun projectFile(ref: ProjectRef, relative: String): File {
        require(safeZipPath(relative)) { "Unsafe project-relative RiftBuild path" }
        val file = File(ref.file, relative).canonicalFile
        require(confinedTo(ref.file, file)) { "RiftBuild project-relative path escaped project root" }
        return file
    }

    private fun projectDisplay(ref: ProjectRef, file: File): String {
        val canonical = file.canonicalFile
        require(confinedTo(ref.file, canonical)) { "RiftBuild display path escaped project root" }
        val relative = canonical.relativeTo(ref.file).invariantSeparatorsPath
        return if (relative.isBlank()) ref.display else ref.display + "/" + relative
    }

    private fun hasPreparedNative(prepared: JSONObject, target: String): Boolean {
        val arm64 = prepared.optJSONArray("arm64Libraries")?.length() ?: 0
        val arm32 = prepared.optJSONArray("arm32Libraries")?.length() ?: 0
        return when (target) {
            "arm64" -> arm64 > 0
            "arm32" -> arm32 > 0
            else -> arm64 > 0 && arm32 > 0
        }
    }

    private fun buildMc0BinaryManifest(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(buildMc0ManifestStringPool())
        body.write(buildManifestResourceMap())
        body.write(buildMc0ManifestNamespace(XML_START_NAMESPACE_TYPE))

        body.write(buildMc0ManifestStartElement(
            "manifest",
            listOf(
                mc0ManifestStringAttr("package", MC0_PACKAGE, XML_NO_INDEX),
                mc0ManifestIntAttr("versionCode", "1", 1),
                mc0ManifestStringAttr("versionName", MC0_VERSION_NAME)
            )
        ))
        body.write(buildMc0ManifestStartElement(
            "uses-sdk",
            listOf(
                mc0ManifestIntAttr("minSdkVersion", "26", 26),
                mc0ManifestIntAttr("targetSdkVersion", "36", 36)
            )
        ))
        body.write(buildMc0ManifestEndElement("uses-sdk"))
        body.write(buildMc0ManifestStartElement(
            "application",
            listOf(mc0ManifestBoolAttr("hasCode", "false", false))
        ))
        body.write(buildMc0ManifestStartElement(
            "activity",
            listOf(
                mc0ManifestStringAttr("name", "android.app.NativeActivity"),
                mc0ManifestBoolAttr("exported", "true", true)
            )
        ))
        body.write(buildMc0ManifestStartElement(
            "meta-data",
            listOf(
                mc0ManifestStringAttr("name", "android.app.lib_name"),
                mc0ManifestStringAttr("value", MC0_LIBRARY_NAME)
            )
        ))
        body.write(buildMc0ManifestEndElement("meta-data"))
        body.write(buildMc0ManifestStartElement("intent-filter", emptyList()))
        body.write(buildMc0ManifestStartElement(
            "action",
            listOf(mc0ManifestStringAttr("name", "android.intent.action.MAIN"))
        ))
        body.write(buildMc0ManifestEndElement("action"))
        body.write(buildMc0ManifestStartElement(
            "category",
            listOf(mc0ManifestStringAttr("name", "android.intent.category.LAUNCHER"))
        ))
        body.write(buildMc0ManifestEndElement("category"))
        body.write(buildMc0ManifestEndElement("intent-filter"))
        body.write(buildMc0ManifestEndElement("activity"))
        body.write(buildMc0ManifestEndElement("application"))
        body.write(buildMc0ManifestEndElement("manifest"))
        body.write(buildMc0ManifestNamespace(XML_END_NAMESPACE_TYPE))

        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_TYPE, 8, 8 + bodyBytes.size)
        output.write(bodyBytes)
        return output.toByteArray()
    }

    private fun buildMc0ManifestStringPool(): ByteArray {
        val offsets = ArrayList<Int>(MC0_MANIFEST_STRINGS.size)
        val data = ByteArrayOutputStream()
        for (value in MC0_MANIFEST_STRINGS) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(value.length < 0x80 && bytes.size < 0x80) {
                "Codynex MC0 manifest string exceeds one-byte UTF-8 pool length"
            }
            offsets.add(data.size())
            writeManifestLength8(data, value.length)
            writeManifestLength8(data, bytes.size)
            data.write(bytes)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)

        val stringsStart = 28 + (MC0_MANIFEST_STRINGS.size * 4)
        val dataBytes = data.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(
            output,
            XML_STRING_POOL_TYPE,
            28,
            stringsStart + dataBytes.size
        )
        writeManifestU32(output, MC0_MANIFEST_STRINGS.size)
        writeManifestU32(output, 0)
        writeManifestU32(output, XML_UTF8_FLAG)
        writeManifestU32(output, stringsStart)
        writeManifestU32(output, 0)
        for (offset in offsets) writeManifestU32(output, offset)
        output.write(dataBytes)
        return output.toByteArray()
    }

    private fun buildMc0ManifestNamespace(type: Int): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, type, 24)
        writeManifestU32(output, mc0ManifestStringIndex("android"))
        writeManifestU32(
            output,
            mc0ManifestStringIndex("http://schemas.android.com/apk/res/android")
        )
        return output.toByteArray()
    }

    private fun buildMc0ManifestStartElement(
        name: String,
        attrs: List<ManifestAttr>
    ): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_START_ELEMENT_TYPE, 36 + (attrs.size * 20))
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, mc0ManifestStringIndex(name))
        writeManifestU16(output, 20)
        writeManifestU16(output, 20)
        writeManifestU16(output, attrs.size)
        writeManifestU16(output, 0)
        writeManifestU16(output, 0)
        writeManifestU16(output, 0)
        for (attr in attrs) {
            writeManifestU32(output, attr.namespace)
            writeManifestU32(output, attr.name)
            writeManifestU32(output, attr.rawValue)
            writeManifestU16(output, 8)
            output.write(0)
            output.write(attr.dataType)
            writeManifestU32(output, attr.data)
        }
        return output.toByteArray()
    }

    private fun buildMc0ManifestEndElement(name: String): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_END_ELEMENT_TYPE, 24)
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, mc0ManifestStringIndex(name))
        return output.toByteArray()
    }

    private fun mc0ManifestStringAttr(
        name: String,
        value: String,
        namespace: Int = mc0ManifestStringIndex(
            "http://schemas.android.com/apk/res/android"
        )
    ): ManifestAttr =
        ManifestAttr(
            namespace,
            mc0ManifestStringIndex(name),
            mc0ManifestStringIndex(value),
            XML_VALUE_STRING,
            mc0ManifestStringIndex(value)
        )

    private fun mc0ManifestIntAttr(
        name: String,
        rawValue: String,
        value: Int
    ): ManifestAttr =
        ManifestAttr(
            mc0ManifestStringIndex("http://schemas.android.com/apk/res/android"),
            mc0ManifestStringIndex(name),
            mc0ManifestStringIndex(rawValue),
            XML_VALUE_INT_DEC,
            value
        )

    private fun mc0ManifestBoolAttr(
        name: String,
        rawValue: String,
        value: Boolean
    ): ManifestAttr =
        ManifestAttr(
            mc0ManifestStringIndex("http://schemas.android.com/apk/res/android"),
            mc0ManifestStringIndex(name),
            mc0ManifestStringIndex(rawValue),
            XML_VALUE_INT_BOOLEAN,
            if (value) -1 else 0
        )

    private fun mc0ManifestStringIndex(value: String): Int {
        val index = MC0_MANIFEST_STRINGS.indexOf(value)
        require(index >= 0) {
            "Codynex MC0 manifest string is not in the frozen pool: " + value
        }
        return index
    }

private fun buildMc1aBinaryManifest(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(buildMc1aManifestStringPool())
        body.write(buildManifestResourceMap())
        body.write(buildMc1aManifestNamespace(XML_START_NAMESPACE_TYPE))

        body.write(buildMc1aManifestStartElement(
            "manifest",
            listOf(
                mc1aManifestStringAttr("package", MC1A_PACKAGE, XML_NO_INDEX),
                mc1aManifestIntAttr("versionCode", "1", 1),
                mc1aManifestStringAttr("versionName", MC1A_VERSION_NAME)
            )
        ))
        body.write(buildMc1aManifestStartElement(
            "uses-sdk",
            listOf(
                mc1aManifestIntAttr("minSdkVersion", "26", 26),
                mc1aManifestIntAttr("targetSdkVersion", "36", 36)
            )
        ))
        body.write(buildMc1aManifestEndElement("uses-sdk"))
        body.write(buildMc1aManifestStartElement(
            "application",
            listOf(mc1aManifestBoolAttr("hasCode", "false", false))
        ))
        body.write(buildMc1aManifestStartElement(
            "activity",
            listOf(
                mc1aManifestStringAttr("name", "android.app.NativeActivity"),
                mc1aManifestBoolAttr("exported", "true", true)
            )
        ))
        body.write(buildMc1aManifestStartElement(
            "meta-data",
            listOf(
                mc1aManifestStringAttr("name", "android.app.lib_name"),
                mc1aManifestStringAttr("value", MC1A_LIBRARY_NAME)
            )
        ))
        body.write(buildMc1aManifestEndElement("meta-data"))
        body.write(buildMc1aManifestStartElement("intent-filter", emptyList()))
        body.write(buildMc1aManifestStartElement(
            "action",
            listOf(mc1aManifestStringAttr("name", "android.intent.action.MAIN"))
        ))
        body.write(buildMc1aManifestEndElement("action"))
        body.write(buildMc1aManifestStartElement(
            "category",
            listOf(mc1aManifestStringAttr("name", "android.intent.category.LAUNCHER"))
        ))
        body.write(buildMc1aManifestEndElement("category"))
        body.write(buildMc1aManifestEndElement("intent-filter"))
        body.write(buildMc1aManifestEndElement("activity"))
        body.write(buildMc1aManifestEndElement("application"))
        body.write(buildMc1aManifestEndElement("manifest"))
        body.write(buildMc1aManifestNamespace(XML_END_NAMESPACE_TYPE))

        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_TYPE, 8, 8 + bodyBytes.size)
        output.write(bodyBytes)
        return output.toByteArray()
    }

    private fun buildMc1aManifestStringPool(): ByteArray {
        val offsets = ArrayList<Int>(MC1A_MANIFEST_STRINGS.size)
        val data = ByteArrayOutputStream()
        for (value in MC1A_MANIFEST_STRINGS) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(value.length < 0x80 && bytes.size < 0x80) {
                "Codynex MC1-A manifest string exceeds one-byte UTF-8 pool length"
            }
            offsets.add(data.size())
            writeManifestLength8(data, value.length)
            writeManifestLength8(data, bytes.size)
            data.write(bytes)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)

        val stringsStart = 28 + (MC1A_MANIFEST_STRINGS.size * 4)
        val dataBytes = data.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(
            output,
            XML_STRING_POOL_TYPE,
            28,
            stringsStart + dataBytes.size
        )
        writeManifestU32(output, MC1A_MANIFEST_STRINGS.size)
        writeManifestU32(output, 0)
        writeManifestU32(output, XML_UTF8_FLAG)
        writeManifestU32(output, stringsStart)
        writeManifestU32(output, 0)
        for (offset in offsets) writeManifestU32(output, offset)
        output.write(dataBytes)
        return output.toByteArray()
    }

    private fun buildMc1aManifestNamespace(type: Int): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, type, 24)
        writeManifestU32(output, mc1aManifestStringIndex("android"))
        writeManifestU32(
            output,
            mc1aManifestStringIndex("http://schemas.android.com/apk/res/android")
        )
        return output.toByteArray()
    }

    private fun buildMc1aManifestStartElement(
        name: String,
        attrs: List<ManifestAttr>
    ): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_START_ELEMENT_TYPE, 36 + (attrs.size * 20))
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, mc1aManifestStringIndex(name))
        writeManifestU16(output, 20)
        writeManifestU16(output, 20)
        writeManifestU16(output, attrs.size)
        writeManifestU16(output, 0)
        writeManifestU16(output, 0)
        writeManifestU16(output, 0)
        for (attr in attrs) {
            writeManifestU32(output, attr.namespace)
            writeManifestU32(output, attr.name)
            writeManifestU32(output, attr.rawValue)
            writeManifestU16(output, 8)
            output.write(0)
            output.write(attr.dataType)
            writeManifestU32(output, attr.data)
        }
        return output.toByteArray()
    }

    private fun buildMc1aManifestEndElement(name: String): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_END_ELEMENT_TYPE, 24)
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, mc1aManifestStringIndex(name))
        return output.toByteArray()
    }

    private fun mc1aManifestStringAttr(
        name: String,
        value: String,
        namespace: Int = mc1aManifestStringIndex(
            "http://schemas.android.com/apk/res/android"
        )
    ): ManifestAttr =
        ManifestAttr(
            namespace,
            mc1aManifestStringIndex(name),
            mc1aManifestStringIndex(value),
            XML_VALUE_STRING,
            mc1aManifestStringIndex(value)
        )

    private fun mc1aManifestIntAttr(
        name: String,
        rawValue: String,
        value: Int
    ): ManifestAttr =
        ManifestAttr(
            mc1aManifestStringIndex("http://schemas.android.com/apk/res/android"),
            mc1aManifestStringIndex(name),
            mc1aManifestStringIndex(rawValue),
            XML_VALUE_INT_DEC,
            value
        )

    private fun mc1aManifestBoolAttr(
        name: String,
        rawValue: String,
        value: Boolean
    ): ManifestAttr =
        ManifestAttr(
            mc1aManifestStringIndex("http://schemas.android.com/apk/res/android"),
            mc1aManifestStringIndex(name),
            mc1aManifestStringIndex(rawValue),
            XML_VALUE_INT_BOOLEAN,
            if (value) -1 else 0
        )

    private fun mc1aManifestStringIndex(value: String): Int {
        val index = MC1A_MANIFEST_STRINGS.indexOf(value)
        require(index >= 0) {
            "Codynex MC1-A manifest string is not in the frozen pool: " + value
        }
        return index
    }

private fun buildMc1bBinaryManifest(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(buildMc1bManifestStringPool())
        body.write(buildManifestResourceMap())
        body.write(buildMc1bManifestNamespace(XML_START_NAMESPACE_TYPE))

        body.write(buildMc1bManifestStartElement(
            "manifest",
            listOf(
                mc1bManifestStringAttr("package", MC1B_PACKAGE, XML_NO_INDEX),
                mc1bManifestIntAttr("versionCode", "1", 1),
                mc1bManifestStringAttr("versionName", MC1B_VERSION_NAME)
            )
        ))
        body.write(buildMc1bManifestStartElement(
            "uses-sdk",
            listOf(
                mc1bManifestIntAttr("minSdkVersion", "26", 26),
                mc1bManifestIntAttr("targetSdkVersion", "36", 36)
            )
        ))
        body.write(buildMc1bManifestEndElement("uses-sdk"))
        body.write(buildMc1bManifestStartElement(
            "application",
            listOf(mc1bManifestBoolAttr("hasCode", "false", false))
        ))
        body.write(buildMc1bManifestStartElement(
            "activity",
            listOf(
                mc1bManifestStringAttr("name", "android.app.NativeActivity"),
                mc1bManifestBoolAttr("exported", "true", true)
            )
        ))
        body.write(buildMc1bManifestStartElement(
            "meta-data",
            listOf(
                mc1bManifestStringAttr("name", "android.app.lib_name"),
                mc1bManifestStringAttr("value", MC1B_LIBRARY_NAME)
            )
        ))
        body.write(buildMc1bManifestEndElement("meta-data"))
        body.write(buildMc1bManifestStartElement("intent-filter", emptyList()))
        body.write(buildMc1bManifestStartElement(
            "action",
            listOf(mc1bManifestStringAttr("name", "android.intent.action.MAIN"))
        ))
        body.write(buildMc1bManifestEndElement("action"))
        body.write(buildMc1bManifestStartElement(
            "category",
            listOf(mc1bManifestStringAttr("name", "android.intent.category.LAUNCHER"))
        ))
        body.write(buildMc1bManifestEndElement("category"))
        body.write(buildMc1bManifestEndElement("intent-filter"))
        body.write(buildMc1bManifestEndElement("activity"))
        body.write(buildMc1bManifestEndElement("application"))
        body.write(buildMc1bManifestEndElement("manifest"))
        body.write(buildMc1bManifestNamespace(XML_END_NAMESPACE_TYPE))

        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_TYPE, 8, 8 + bodyBytes.size)
        output.write(bodyBytes)
        return output.toByteArray()
    }

    private fun buildMc1bManifestStringPool(): ByteArray {
        val offsets = ArrayList<Int>(MC1B_MANIFEST_STRINGS.size)
        val data = ByteArrayOutputStream()
        for (value in MC1B_MANIFEST_STRINGS) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(value.length < 0x80 && bytes.size < 0x80) {
                "Codynex MC1-B manifest string exceeds one-byte UTF-8 pool length"
            }
            offsets.add(data.size())
            writeManifestLength8(data, value.length)
            writeManifestLength8(data, bytes.size)
            data.write(bytes)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)

        val stringsStart = 28 + (MC1B_MANIFEST_STRINGS.size * 4)
        val dataBytes = data.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(
            output,
            XML_STRING_POOL_TYPE,
            28,
            stringsStart + dataBytes.size
        )
        writeManifestU32(output, MC1B_MANIFEST_STRINGS.size)
        writeManifestU32(output, 0)
        writeManifestU32(output, XML_UTF8_FLAG)
        writeManifestU32(output, stringsStart)
        writeManifestU32(output, 0)
        for (offset in offsets) writeManifestU32(output, offset)
        output.write(dataBytes)
        return output.toByteArray()
    }

    private fun buildMc1bManifestNamespace(type: Int): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, type, 24)
        writeManifestU32(output, mc1bManifestStringIndex("android"))
        writeManifestU32(
            output,
            mc1bManifestStringIndex("http://schemas.android.com/apk/res/android")
        )
        return output.toByteArray()
    }

    private fun buildMc1bManifestStartElement(
        name: String,
        attrs: List<ManifestAttr>
    ): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_START_ELEMENT_TYPE, 36 + (attrs.size * 20))
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, mc1bManifestStringIndex(name))
        writeManifestU16(output, 20)
        writeManifestU16(output, 20)
        writeManifestU16(output, attrs.size)
        writeManifestU16(output, 0)
        writeManifestU16(output, 0)
        writeManifestU16(output, 0)
        for (attr in attrs) {
            writeManifestU32(output, attr.namespace)
            writeManifestU32(output, attr.name)
            writeManifestU32(output, attr.rawValue)
            writeManifestU16(output, 8)
            output.write(0)
            output.write(attr.dataType)
            writeManifestU32(output, attr.data)
        }
        return output.toByteArray()
    }

    private fun buildMc1bManifestEndElement(name: String): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_END_ELEMENT_TYPE, 24)
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, mc1bManifestStringIndex(name))
        return output.toByteArray()
    }

    private fun mc1bManifestStringAttr(
        name: String,
        value: String,
        namespace: Int = mc1bManifestStringIndex(
            "http://schemas.android.com/apk/res/android"
        )
    ): ManifestAttr =
        ManifestAttr(
            namespace,
            mc1bManifestStringIndex(name),
            mc1bManifestStringIndex(value),
            XML_VALUE_STRING,
            mc1bManifestStringIndex(value)
        )

    private fun mc1bManifestIntAttr(
        name: String,
        rawValue: String,
        value: Int
    ): ManifestAttr =
        ManifestAttr(
            mc1bManifestStringIndex("http://schemas.android.com/apk/res/android"),
            mc1bManifestStringIndex(name),
            mc1bManifestStringIndex(rawValue),
            XML_VALUE_INT_DEC,
            value
        )

    private fun mc1bManifestBoolAttr(
        name: String,
        rawValue: String,
        value: Boolean
    ): ManifestAttr =
        ManifestAttr(
            mc1bManifestStringIndex("http://schemas.android.com/apk/res/android"),
            mc1bManifestStringIndex(name),
            mc1bManifestStringIndex(rawValue),
            XML_VALUE_INT_BOOLEAN,
            if (value) -1 else 0
        )

    private fun mc1bManifestStringIndex(value: String): Int {
        val index = MC1B_MANIFEST_STRINGS.indexOf(value)
        require(index >= 0) {
            "Codynex MC1-B manifest string is not in the frozen pool: " + value
        }
        return index
    }

    private fun buildM2Vm0BinaryManifest(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(buildM2Vm0ManifestStringPool())
        body.write(buildManifestResourceMap())
        body.write(buildM2Vm0ManifestNamespace(XML_START_NAMESPACE_TYPE))

        body.write(buildM2Vm0ManifestStartElement(
            "manifest",
            listOf(
                m2Vm0ManifestStringAttr("package", M2_VM0_PACKAGE, XML_NO_INDEX),
                m2Vm0ManifestIntAttr("versionCode", "1", 1),
                m2Vm0ManifestStringAttr("versionName", M2_VM0_VERSION_NAME)
            )
        ))
        body.write(buildM2Vm0ManifestStartElement(
            "uses-sdk",
            listOf(
                m2Vm0ManifestIntAttr("minSdkVersion", "26", 26),
                m2Vm0ManifestIntAttr("targetSdkVersion", "36", 36)
            )
        ))
        body.write(buildM2Vm0ManifestEndElement("uses-sdk"))
        body.write(buildM2Vm0ManifestStartElement(
            "application",
            listOf(m2Vm0ManifestBoolAttr("hasCode", "false", false))
        ))
        body.write(buildM2Vm0ManifestStartElement(
            "activity",
            listOf(
                m2Vm0ManifestStringAttr("name", "android.app.NativeActivity"),
                m2Vm0ManifestBoolAttr("exported", "true", true)
            )
        ))
        body.write(buildM2Vm0ManifestStartElement(
            "meta-data",
            listOf(
                m2Vm0ManifestStringAttr("name", "android.app.lib_name"),
                m2Vm0ManifestStringAttr("value", M2_VM0_LIBRARY_NAME)
            )
        ))
        body.write(buildM2Vm0ManifestEndElement("meta-data"))
        body.write(buildM2Vm0ManifestStartElement("intent-filter", emptyList()))
        body.write(buildM2Vm0ManifestStartElement(
            "action",
            listOf(m2Vm0ManifestStringAttr("name", "android.intent.action.MAIN"))
        ))
        body.write(buildM2Vm0ManifestEndElement("action"))
        body.write(buildM2Vm0ManifestStartElement(
            "category",
            listOf(m2Vm0ManifestStringAttr("name", "android.intent.category.LAUNCHER"))
        ))
        body.write(buildM2Vm0ManifestEndElement("category"))
        body.write(buildM2Vm0ManifestEndElement("intent-filter"))
        body.write(buildM2Vm0ManifestEndElement("activity"))
        body.write(buildM2Vm0ManifestEndElement("application"))
        body.write(buildM2Vm0ManifestEndElement("manifest"))
        body.write(buildM2Vm0ManifestNamespace(XML_END_NAMESPACE_TYPE))

        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_TYPE, 8, 8 + bodyBytes.size)
        output.write(bodyBytes)
        return output.toByteArray()
    }

    private fun buildM2Vm0ManifestStringPool(): ByteArray {
        val offsets = ArrayList<Int>(M2_VM0_MANIFEST_STRINGS.size)
        val data = ByteArrayOutputStream()
        for (value in M2_VM0_MANIFEST_STRINGS) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(value.length < 0x80 && bytes.size < 0x80) {
                "Codynex M2-A VM0 manifest string exceeds one-byte UTF-8 pool length"
            }
            offsets.add(data.size())
            writeManifestLength8(data, value.length)
            writeManifestLength8(data, bytes.size)
            data.write(bytes)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)

        val stringsStart = 28 + (M2_VM0_MANIFEST_STRINGS.size * 4)
        val dataBytes = data.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(
            output,
            XML_STRING_POOL_TYPE,
            28,
            stringsStart + dataBytes.size
        )
        writeManifestU32(output, M2_VM0_MANIFEST_STRINGS.size)
        writeManifestU32(output, 0)
        writeManifestU32(output, XML_UTF8_FLAG)
        writeManifestU32(output, stringsStart)
        writeManifestU32(output, 0)
        for (offset in offsets) writeManifestU32(output, offset)
        output.write(dataBytes)
        return output.toByteArray()
    }

    private fun buildM2Vm0ManifestNamespace(type: Int): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, type, 24)
        writeManifestU32(output, m2Vm0ManifestStringIndex("android"))
        writeManifestU32(
            output,
            m2Vm0ManifestStringIndex("http://schemas.android.com/apk/res/android")
        )
        return output.toByteArray()
    }

    private fun buildM2Vm0ManifestStartElement(
        name: String,
        attrs: List<ManifestAttr>
    ): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_START_ELEMENT_TYPE, 36 + (attrs.size * 20))
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, m2Vm0ManifestStringIndex(name))
        writeManifestU16(output, 20)
        writeManifestU16(output, 20)
        writeManifestU16(output, attrs.size)
        writeManifestU16(output, 0)
        writeManifestU16(output, 0)
        writeManifestU16(output, 0)
        for (attr in attrs) {
            writeManifestU32(output, attr.namespace)
            writeManifestU32(output, attr.name)
            writeManifestU32(output, attr.rawValue)
            writeManifestU16(output, 8)
            output.write(0)
            output.write(attr.dataType)
            writeManifestU32(output, attr.data)
        }
        return output.toByteArray()
    }

    private fun buildM2Vm0ManifestEndElement(name: String): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_END_ELEMENT_TYPE, 24)
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, m2Vm0ManifestStringIndex(name))
        return output.toByteArray()
    }

    private fun m2Vm0ManifestStringAttr(
        name: String,
        value: String,
        namespace: Int = m2Vm0ManifestStringIndex(
            "http://schemas.android.com/apk/res/android"
        )
    ): ManifestAttr =
        ManifestAttr(
            namespace,
            m2Vm0ManifestStringIndex(name),
            m2Vm0ManifestStringIndex(value),
            XML_VALUE_STRING,
            m2Vm0ManifestStringIndex(value)
        )

    private fun m2Vm0ManifestIntAttr(
        name: String,
        rawValue: String,
        value: Int
    ): ManifestAttr =
        ManifestAttr(
            m2Vm0ManifestStringIndex("http://schemas.android.com/apk/res/android"),
            m2Vm0ManifestStringIndex(name),
            m2Vm0ManifestStringIndex(rawValue),
            XML_VALUE_INT_DEC,
            value
        )

    private fun m2Vm0ManifestBoolAttr(
        name: String,
        rawValue: String,
        value: Boolean
    ): ManifestAttr =
        ManifestAttr(
            m2Vm0ManifestStringIndex("http://schemas.android.com/apk/res/android"),
            m2Vm0ManifestStringIndex(name),
            m2Vm0ManifestStringIndex(rawValue),
            XML_VALUE_INT_BOOLEAN,
            if (value) -1 else 0
        )

    private fun m2Vm0ManifestStringIndex(value: String): Int {
        val index = M2_VM0_MANIFEST_STRINGS.indexOf(value)
        require(index >= 0) {
            "Codynex M2-A VM0 manifest string is not in the frozen pool: " + value
        }
        return index
    }

    private fun buildM2BBinaryManifest(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(buildM2BManifestStringPool())
        body.write(buildManifestResourceMap())
        body.write(buildM2BManifestNamespace(XML_START_NAMESPACE_TYPE))

        body.write(buildM2BManifestStartElement(
            "manifest",
            listOf(
                m2BManifestStringAttr("package", M2_B_PACKAGE, XML_NO_INDEX),
                m2BManifestIntAttr("versionCode", "1", 1),
                m2BManifestStringAttr("versionName", M2_B_VERSION_NAME)
            )
        ))
        body.write(buildM2BManifestStartElement(
            "uses-sdk",
            listOf(
                m2BManifestIntAttr("minSdkVersion", "26", 26),
                m2BManifestIntAttr("targetSdkVersion", "36", 36)
            )
        ))
        body.write(buildM2BManifestEndElement("uses-sdk"))
        body.write(buildM2BManifestStartElement(
            "application",
            listOf(m2BManifestBoolAttr("hasCode", "false", false))
        ))
        body.write(buildM2BManifestStartElement(
            "activity",
            listOf(
                m2BManifestStringAttr("name", "android.app.NativeActivity"),
                m2BManifestBoolAttr("exported", "true", true)
            )
        ))
        body.write(buildM2BManifestStartElement(
            "meta-data",
            listOf(
                m2BManifestStringAttr("name", "android.app.lib_name"),
                m2BManifestStringAttr("value", M2_B_LIBRARY_NAME)
            )
        ))
        body.write(buildM2BManifestEndElement("meta-data"))
        body.write(buildM2BManifestStartElement("intent-filter", emptyList()))
        body.write(buildM2BManifestStartElement(
            "action",
            listOf(m2BManifestStringAttr("name", "android.intent.action.MAIN"))
        ))
        body.write(buildM2BManifestEndElement("action"))
        body.write(buildM2BManifestStartElement(
            "category",
            listOf(m2BManifestStringAttr("name", "android.intent.category.LAUNCHER"))
        ))
        body.write(buildM2BManifestEndElement("category"))
        body.write(buildM2BManifestEndElement("intent-filter"))
        body.write(buildM2BManifestEndElement("activity"))
        body.write(buildM2BManifestEndElement("application"))
        body.write(buildM2BManifestEndElement("manifest"))
        body.write(buildM2BManifestNamespace(XML_END_NAMESPACE_TYPE))

        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_TYPE, 8, 8 + bodyBytes.size)
        output.write(bodyBytes)
        return output.toByteArray()
    }

    private fun buildM2BManifestStringPool(): ByteArray {
        val offsets = ArrayList<Int>(M2_B_MANIFEST_STRINGS.size)
        val data = ByteArrayOutputStream()
        for (value in M2_B_MANIFEST_STRINGS) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(value.length < 0x80 && bytes.size < 0x80) {
                "Codynex M2-B manifest string exceeds one-byte UTF-8 pool length"
            }
            offsets.add(data.size())
            writeManifestLength8(data, value.length)
            writeManifestLength8(data, bytes.size)
            data.write(bytes)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)

        val stringsStart = 28 + (M2_B_MANIFEST_STRINGS.size * 4)
        val dataBytes = data.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(
            output,
            XML_STRING_POOL_TYPE,
            28,
            stringsStart + dataBytes.size
        )
        writeManifestU32(output, M2_B_MANIFEST_STRINGS.size)
        writeManifestU32(output, 0)
        writeManifestU32(output, XML_UTF8_FLAG)
        writeManifestU32(output, stringsStart)
        writeManifestU32(output, 0)
        for (offset in offsets) writeManifestU32(output, offset)
        output.write(dataBytes)
        return output.toByteArray()
    }

    private fun buildM2BManifestNamespace(type: Int): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, type, 24)
        writeManifestU32(output, m2BManifestStringIndex("android"))
        writeManifestU32(
            output,
            m2BManifestStringIndex("http://schemas.android.com/apk/res/android")
        )
        return output.toByteArray()
    }

    private fun buildM2BManifestStartElement(
        name: String,
        attrs: List<ManifestAttr>
    ): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_START_ELEMENT_TYPE, 36 + (attrs.size * 20))
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, m2BManifestStringIndex(name))
        writeManifestU16(output, 20)
        writeManifestU16(output, 20)
        writeManifestU16(output, attrs.size)
        writeManifestU16(output, 0)
        writeManifestU16(output, 0)
        writeManifestU16(output, 0)
        for (attr in attrs) {
            writeManifestU32(output, attr.namespace)
            writeManifestU32(output, attr.name)
            writeManifestU32(output, attr.rawValue)
            writeManifestU16(output, 8)
            output.write(0)
            output.write(attr.dataType)
            writeManifestU32(output, attr.data)
        }
        return output.toByteArray()
    }

    private fun buildM2BManifestEndElement(name: String): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_END_ELEMENT_TYPE, 24)
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, m2BManifestStringIndex(name))
        return output.toByteArray()
    }

    private fun m2BManifestStringAttr(
        name: String,
        value: String,
        namespace: Int = m2BManifestStringIndex(
            "http://schemas.android.com/apk/res/android"
        )
    ): ManifestAttr =
        ManifestAttr(
            namespace,
            m2BManifestStringIndex(name),
            m2BManifestStringIndex(value),
            XML_VALUE_STRING,
            m2BManifestStringIndex(value)
        )

    private fun m2BManifestIntAttr(
        name: String,
        rawValue: String,
        value: Int
    ): ManifestAttr =
        ManifestAttr(
            m2BManifestStringIndex("http://schemas.android.com/apk/res/android"),
            m2BManifestStringIndex(name),
            m2BManifestStringIndex(rawValue),
            XML_VALUE_INT_DEC,
            value
        )

    private fun m2BManifestBoolAttr(
        name: String,
        rawValue: String,
        value: Boolean
    ): ManifestAttr =
        ManifestAttr(
            m2BManifestStringIndex("http://schemas.android.com/apk/res/android"),
            m2BManifestStringIndex(name),
            m2BManifestStringIndex(rawValue),
            XML_VALUE_INT_BOOLEAN,
            if (value) -1 else 0
        )

    private fun m2BManifestStringIndex(value: String): Int {
        val index = M2_B_MANIFEST_STRINGS.indexOf(value)
        require(index >= 0) {
            "Codynex M2-B manifest string is not in the frozen pool: " + value
        }
        return index
    }

    private fun buildMc2ABinaryManifest(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(buildMc2AManifestStringPool())
        body.write(buildManifestResourceMap())
        body.write(buildMc2AManifestNamespace(XML_START_NAMESPACE_TYPE))

        body.write(buildMc2AManifestStartElement(
            "manifest",
            listOf(
                mc2AManifestStringAttr("package", MC2_A_PACKAGE, XML_NO_INDEX),
                mc2AManifestIntAttr("versionCode", "1", 1),
                mc2AManifestStringAttr("versionName", MC2_A_VERSION_NAME)
            )
        ))
        body.write(buildMc2AManifestStartElement(
            "uses-sdk",
            listOf(
                mc2AManifestIntAttr("minSdkVersion", "26", 26),
                mc2AManifestIntAttr("targetSdkVersion", "36", 36)
            )
        ))
        body.write(buildMc2AManifestEndElement("uses-sdk"))
        body.write(buildMc2AManifestStartElement(
            "application",
            listOf(mc2AManifestBoolAttr("hasCode", "false", false))
        ))
        body.write(buildMc2AManifestStartElement(
            "activity",
            listOf(
                mc2AManifestStringAttr("name", "android.app.NativeActivity"),
                mc2AManifestBoolAttr("exported", "true", true)
            )
        ))
        body.write(buildMc2AManifestStartElement(
            "meta-data",
            listOf(
                mc2AManifestStringAttr("name", "android.app.lib_name"),
                mc2AManifestStringAttr("value", MC2_A_LIBRARY_NAME)
            )
        ))
        body.write(buildMc2AManifestEndElement("meta-data"))
        body.write(buildMc2AManifestStartElement("intent-filter", emptyList()))
        body.write(buildMc2AManifestStartElement(
            "action",
            listOf(mc2AManifestStringAttr("name", "android.intent.action.MAIN"))
        ))
        body.write(buildMc2AManifestEndElement("action"))
        body.write(buildMc2AManifestStartElement(
            "category",
            listOf(mc2AManifestStringAttr("name", "android.intent.category.LAUNCHER"))
        ))
        body.write(buildMc2AManifestEndElement("category"))
        body.write(buildMc2AManifestEndElement("intent-filter"))
        body.write(buildMc2AManifestEndElement("activity"))
        body.write(buildMc2AManifestEndElement("application"))
        body.write(buildMc2AManifestEndElement("manifest"))
        body.write(buildMc2AManifestNamespace(XML_END_NAMESPACE_TYPE))

        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_TYPE, 8, 8 + bodyBytes.size)
        output.write(bodyBytes)
        return output.toByteArray()
    }

    private fun buildMc2AManifestStringPool(): ByteArray {
        val offsets = ArrayList<Int>(MC2_A_MANIFEST_STRINGS.size)
        val data = ByteArrayOutputStream()
        for (value in MC2_A_MANIFEST_STRINGS) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(value.length < 0x80 && bytes.size < 0x80) {
                "Codynex MC2-A manifest string exceeds one-byte UTF-8 pool length"
            }
            offsets.add(data.size())
            writeManifestLength8(data, value.length)
            writeManifestLength8(data, bytes.size)
            data.write(bytes)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)

        val stringsStart = 28 + (MC2_A_MANIFEST_STRINGS.size * 4)
        val dataBytes = data.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(
            output,
            XML_STRING_POOL_TYPE,
            28,
            stringsStart + dataBytes.size
        )
        writeManifestU32(output, MC2_A_MANIFEST_STRINGS.size)
        writeManifestU32(output, 0)
        writeManifestU32(output, XML_UTF8_FLAG)
        writeManifestU32(output, stringsStart)
        writeManifestU32(output, 0)
        for (offset in offsets) writeManifestU32(output, offset)
        output.write(dataBytes)
        return output.toByteArray()
    }

    private fun buildMc2AManifestNamespace(type: Int): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, type, 24)
        writeManifestU32(output, mc2AManifestStringIndex("android"))
        writeManifestU32(
            output,
            mc2AManifestStringIndex("http://schemas.android.com/apk/res/android")
        )
        return output.toByteArray()
    }

    private fun buildMc2AManifestStartElement(
        name: String,
        attrs: List<ManifestAttr>
    ): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_START_ELEMENT_TYPE, 36 + (attrs.size * 20))
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, mc2AManifestStringIndex(name))
        writeManifestU16(output, 20)
        writeManifestU16(output, 20)
        writeManifestU16(output, attrs.size)
        writeManifestU16(output, 0)
        writeManifestU16(output, 0)
        writeManifestU16(output, 0)
        for (attr in attrs) {
            writeManifestU32(output, attr.namespace)
            writeManifestU32(output, attr.name)
            writeManifestU32(output, attr.rawValue)
            writeManifestU16(output, 8)
            output.write(0)
            output.write(attr.dataType)
            writeManifestU32(output, attr.data)
        }
        return output.toByteArray()
    }

    private fun buildMc2AManifestEndElement(name: String): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_END_ELEMENT_TYPE, 24)
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, mc2AManifestStringIndex(name))
        return output.toByteArray()
    }

    private fun mc2AManifestStringAttr(
        name: String,
        value: String,
        namespace: Int = mc2AManifestStringIndex(
            "http://schemas.android.com/apk/res/android"
        )
    ): ManifestAttr =
        ManifestAttr(
            namespace,
            mc2AManifestStringIndex(name),
            mc2AManifestStringIndex(value),
            XML_VALUE_STRING,
            mc2AManifestStringIndex(value)
        )

    private fun mc2AManifestIntAttr(
        name: String,
        rawValue: String,
        value: Int
    ): ManifestAttr =
        ManifestAttr(
            mc2AManifestStringIndex("http://schemas.android.com/apk/res/android"),
            mc2AManifestStringIndex(name),
            mc2AManifestStringIndex(rawValue),
            XML_VALUE_INT_DEC,
            value
        )

    private fun mc2AManifestBoolAttr(
        name: String,
        rawValue: String,
        value: Boolean
    ): ManifestAttr =
        ManifestAttr(
            mc2AManifestStringIndex("http://schemas.android.com/apk/res/android"),
            mc2AManifestStringIndex(name),
            mc2AManifestStringIndex(rawValue),
            XML_VALUE_INT_BOOLEAN,
            if (value) -1 else 0
        )

    private fun mc2AManifestStringIndex(value: String): Int {
        val index = MC2_A_MANIFEST_STRINGS.indexOf(value)
        require(index >= 0) {
            "Codynex MC2-A manifest string is not in the frozen pool: " + value
        }
        return index
    }

    private fun buildEditorBinaryManifest(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(buildEditorManifestStringPool())
        body.write(buildManifestResourceMap())
        body.write(buildEditorManifestNamespace(XML_START_NAMESPACE_TYPE))

        body.write(buildEditorManifestStartElement(
            "manifest",
            listOf(
                editorManifestStringAttr("package", EDITOR_PACKAGE, XML_NO_INDEX),
                editorManifestIntAttr("versionCode", "1", 1),
                editorManifestStringAttr("versionName", EDITOR_VERSION_NAME)
            )
        ))
        body.write(buildEditorManifestStartElement(
            "uses-sdk",
            listOf(
                editorManifestIntAttr("minSdkVersion", "26", 26),
                editorManifestIntAttr("targetSdkVersion", "36", 36)
            )
        ))
        body.write(buildEditorManifestEndElement("uses-sdk"))
        body.write(buildEditorManifestStartElement("queries", emptyList()))
        body.write(buildEditorManifestStartElement(
            "package",
            listOf(editorManifestStringAttr("name", "com.riftos.app"))
        ))
        body.write(buildEditorManifestEndElement("package"))
        body.write(buildEditorManifestEndElement("queries"))
        body.write(buildEditorManifestStartElement(
            "application",
            listOf(editorManifestBoolAttr("hasCode", "true", true))
        ))
        body.write(buildEditorManifestStartElement(
            "activity",
            listOf(
                editorManifestStringAttr("name", EDITOR_ACTIVITY),
                editorManifestBoolAttr("exported", "true", true)
            )
        ))
        body.write(buildEditorManifestStartElement("intent-filter", emptyList()))
        body.write(buildEditorManifestStartElement(
            "action",
            listOf(editorManifestStringAttr("name", "android.intent.action.MAIN"))
        ))
        body.write(buildEditorManifestEndElement("action"))
        body.write(buildEditorManifestStartElement(
            "category",
            listOf(editorManifestStringAttr("name", "android.intent.category.LAUNCHER"))
        ))
        body.write(buildEditorManifestEndElement("category"))
        body.write(buildEditorManifestEndElement("intent-filter"))
        body.write(buildEditorManifestEndElement("activity"))
        body.write(buildEditorManifestEndElement("application"))
        body.write(buildEditorManifestEndElement("manifest"))
        body.write(buildEditorManifestNamespace(XML_END_NAMESPACE_TYPE))

        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_TYPE, 8, 8 + bodyBytes.size)
        output.write(bodyBytes)
        return output.toByteArray()
    }

    private fun buildCodynexAppBinaryManifest(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(buildEditorManifestStringPool())
        body.write(buildManifestResourceMap())
        body.write(buildEditorManifestNamespace(XML_START_NAMESPACE_TYPE))

        body.write(
            buildEditorManifestStartElement(
                "manifest",
                listOf(
                    editorManifestStringAttr(
                        "package",
                        CODYNEX_APP_PACKAGE,
                        XML_NO_INDEX
                    ),
                    editorManifestIntAttr("versionCode", "1", 1),
                    editorManifestStringAttr(
                        "versionName",
                        CODYNEX_APP_VERSION_NAME
                    )
                )
            )
        )
        body.write(
            buildEditorManifestStartElement(
                "uses-sdk",
                listOf(
                    editorManifestIntAttr("minSdkVersion", "26", 26),
                    editorManifestIntAttr("targetSdkVersion", "36", 36)
                )
            )
        )
        body.write(buildEditorManifestEndElement("uses-sdk"))
        body.write(
            buildEditorManifestStartElement(
                "application",
                listOf(editorManifestBoolAttr("hasCode", "true", true))
            )
        )
        body.write(
            buildEditorManifestStartElement(
                "activity",
                listOf(
                    editorManifestStringAttr(
                        "name",
                        CODYNEX_APP_ACTIVITY
                    ),
                    editorManifestBoolAttr("exported", "true", true)
                )
            )
        )
        body.write(buildEditorManifestStartElement("intent-filter", emptyList()))
        body.write(
            buildEditorManifestStartElement(
                "action",
                listOf(
                    editorManifestStringAttr(
                        "name",
                        "android.intent.action.MAIN"
                    )
                )
            )
        )
        body.write(buildEditorManifestEndElement("action"))
        body.write(
            buildEditorManifestStartElement(
                "category",
                listOf(
                    editorManifestStringAttr(
                        "name",
                        "android.intent.category.LAUNCHER"
                    )
                )
            )
        )
        body.write(buildEditorManifestEndElement("category"))
        body.write(buildEditorManifestEndElement("intent-filter"))
        body.write(buildEditorManifestEndElement("activity"))
        body.write(buildEditorManifestEndElement("application"))
        body.write(buildEditorManifestEndElement("manifest"))
        body.write(buildEditorManifestNamespace(XML_END_NAMESPACE_TYPE))

        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(
            output,
            XML_TYPE,
            8,
            8 + bodyBytes.size
        )
        output.write(bodyBytes)
        return output.toByteArray()
    }

    private fun buildEditorManifestStringPool(): ByteArray {
        val offsets = ArrayList<Int>(EDITOR_MANIFEST_STRINGS.size)
        val data = ByteArrayOutputStream()
        for (value in EDITOR_MANIFEST_STRINGS) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(value.length < 0x80 && bytes.size < 0x80) {
                "Codynex editor manifest string exceeds one-byte UTF-8 pool length"
            }
            offsets.add(data.size())
            writeManifestLength8(data, value.length)
            writeManifestLength8(data, bytes.size)
            data.write(bytes)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)

        val stringsStart = 28 + (EDITOR_MANIFEST_STRINGS.size * 4)
        val dataBytes = data.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(
            output,
            XML_STRING_POOL_TYPE,
            28,
            stringsStart + dataBytes.size
        )
        writeManifestU32(output, EDITOR_MANIFEST_STRINGS.size)
        writeManifestU32(output, 0)
        writeManifestU32(output, XML_UTF8_FLAG)
        writeManifestU32(output, stringsStart)
        writeManifestU32(output, 0)
        for (offset in offsets) writeManifestU32(output, offset)
        output.write(dataBytes)
        return output.toByteArray()
    }

    private fun buildEditorManifestNamespace(type: Int): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, type, 24)
        writeManifestU32(output, editorManifestStringIndex("android"))
        writeManifestU32(
            output,
            editorManifestStringIndex("http://schemas.android.com/apk/res/android")
        )
        return output.toByteArray()
    }

    private fun buildEditorManifestStartElement(
        name: String,
        attrs: List<ManifestAttr>
    ): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_START_ELEMENT_TYPE, 36 + (attrs.size * 20))
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, editorManifestStringIndex(name))
        writeManifestU16(output, 20)
        writeManifestU16(output, 20)
        writeManifestU16(output, attrs.size)
        writeManifestU16(output, 0)
        writeManifestU16(output, 0)
        writeManifestU16(output, 0)
        for (attr in attrs) {
            writeManifestU32(output, attr.namespace)
            writeManifestU32(output, attr.name)
            writeManifestU32(output, attr.rawValue)
            writeManifestU16(output, 8)
            output.write(0)
            output.write(attr.dataType)
            writeManifestU32(output, attr.data)
        }
        return output.toByteArray()
    }

    private fun buildEditorManifestEndElement(name: String): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_END_ELEMENT_TYPE, 24)
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, editorManifestStringIndex(name))
        return output.toByteArray()
    }

    private fun editorManifestStringAttr(
        name: String,
        value: String,
        namespace: Int = editorManifestStringIndex(
            "http://schemas.android.com/apk/res/android"
        )
    ): ManifestAttr =
        ManifestAttr(
            namespace,
            editorManifestStringIndex(name),
            editorManifestStringIndex(value),
            XML_VALUE_STRING,
            editorManifestStringIndex(value)
        )

    private fun editorManifestIntAttr(
        name: String,
        rawValue: String,
        value: Int
    ): ManifestAttr =
        ManifestAttr(
            editorManifestStringIndex("http://schemas.android.com/apk/res/android"),
            editorManifestStringIndex(name),
            editorManifestStringIndex(rawValue),
            XML_VALUE_INT_DEC,
            value
        )

    private fun editorManifestBoolAttr(
        name: String,
        rawValue: String,
        value: Boolean
    ): ManifestAttr =
        ManifestAttr(
            editorManifestStringIndex("http://schemas.android.com/apk/res/android"),
            editorManifestStringIndex(name),
            editorManifestStringIndex(rawValue),
            XML_VALUE_INT_BOOLEAN,
            if (value) -1 else 0
        )

    private fun editorManifestStringIndex(value: String): Int {
        val index = EDITOR_MANIFEST_STRINGS.indexOf(value)
        require(index >= 0) {
            "Codynex editor manifest string is not in the frozen pool: " + value
        }
        return index
    }

    private fun buildRiftppEditorBinaryManifest(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(buildEditorManifestStringPool())
        body.write(buildManifestResourceMap())
        body.write(buildEditorManifestNamespace(XML_START_NAMESPACE_TYPE))

        body.write(buildEditorManifestStartElement(
            "manifest",
            listOf(
                editorManifestStringAttr(
                    "package",
                    RIFTPP_EDITOR_PACKAGE,
                    XML_NO_INDEX
                ),
                editorManifestIntAttr("versionCode", "2", 2),
                editorManifestStringAttr(
                    "versionName",
                    RIFTPP_EDITOR_VERSION_NAME
                )
            )
        ))
        body.write(buildEditorManifestStartElement(
            "uses-sdk",
            listOf(
                editorManifestIntAttr("minSdkVersion", "26", 26),
                editorManifestIntAttr("targetSdkVersion", "36", 36)
            )
        ))
        body.write(buildEditorManifestEndElement("uses-sdk"))
        body.write(buildEditorManifestStartElement(
            "application",
            listOf(editorManifestBoolAttr("hasCode", "true", true))
        ))
        body.write(buildEditorManifestStartElement(
            "activity",
            listOf(
                editorManifestStringAttr("name", RIFTPP_EDITOR_ACTIVITY),
                editorManifestBoolAttr("exported", "true", true)
            )
        ))
        body.write(buildEditorManifestStartElement("intent-filter", emptyList()))
        body.write(buildEditorManifestStartElement(
            "action",
            listOf(
                editorManifestStringAttr(
                    "name",
                    "android.intent.action.MAIN"
                )
            )
        ))
        body.write(buildEditorManifestEndElement("action"))
        body.write(buildEditorManifestStartElement(
            "category",
            listOf(
                editorManifestStringAttr(
                    "name",
                    "android.intent.category.LAUNCHER"
                )
            )
        ))
        body.write(buildEditorManifestEndElement("category"))
        body.write(buildEditorManifestEndElement("intent-filter"))
        body.write(buildEditorManifestEndElement("activity"))
        body.write(buildEditorManifestStartElement(
            "service",
            listOf(
                editorManifestStringAttr(
                    "name",
                    RIFTPP_EDITOR_BRIDGE_SERVICE
                ),
                editorManifestBoolAttr(
                    "exported",
                    "true",
                    true
                )
            )
        ))
        body.write(buildEditorManifestEndElement("service"))
        body.write(buildEditorManifestEndElement("application"))
        body.write(buildEditorManifestEndElement("manifest"))
        body.write(buildEditorManifestNamespace(XML_END_NAMESPACE_TYPE))

        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_TYPE, 8, 8 + bodyBytes.size)
        output.write(bodyBytes)
        return output.toByteArray()
    }


    private fun buildRiftppV0BinaryManifest(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(buildManifestStringPool())
        body.write(buildManifestResourceMap())
        body.write(buildManifestNamespace(XML_START_NAMESPACE_TYPE))

        body.write(buildManifestStartElement(
            "manifest",
            listOf(
                manifestStringAttr("package", "com.riftpp.nativeproof", XML_NO_INDEX),
                manifestIntAttr("versionCode", "1", 1),
                manifestStringAttr("versionName", "0.1.0-native-proof")
            )
        ))
        body.write(buildManifestStartElement(
            "uses-sdk",
            listOf(
                manifestIntAttr("minSdkVersion", "26", 26),
                manifestIntAttr("targetSdkVersion", "36", 36)
            )
        ))
        body.write(buildManifestEndElement("uses-sdk"))
        body.write(buildManifestStartElement(
            "application",
            listOf(manifestBoolAttr("hasCode", "false", false))
        ))
        body.write(buildManifestStartElement(
            "activity",
            listOf(
                manifestStringAttr("name", "android.app.NativeActivity"),
                manifestBoolAttr("exported", "true", true)
            )
        ))
        body.write(buildManifestStartElement(
            "meta-data",
            listOf(
                manifestStringAttr("name", "android.app.lib_name"),
                manifestStringAttr("value", "riftpp_nativeproof")
            )
        ))
        body.write(buildManifestEndElement("meta-data"))
        body.write(buildManifestStartElement("intent-filter", emptyList()))
        body.write(buildManifestStartElement(
            "action",
            listOf(manifestStringAttr("name", "android.intent.action.MAIN"))
        ))
        body.write(buildManifestEndElement("action"))
        body.write(buildManifestStartElement(
            "category",
            listOf(manifestStringAttr("name", "android.intent.category.LAUNCHER"))
        ))
        body.write(buildManifestEndElement("category"))
        body.write(buildManifestEndElement("intent-filter"))
        body.write(buildManifestEndElement("activity"))
        body.write(buildManifestEndElement("application"))
        body.write(buildManifestEndElement("manifest"))
        body.write(buildManifestNamespace(XML_END_NAMESPACE_TYPE))

        val bodyBytes = body.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_TYPE, 8, 8 + bodyBytes.size)
        output.write(bodyBytes)
        val bytes = output.toByteArray()
        require(bytes.size == RIFTPP_V0_BINARY_MANIFEST_BYTES) { "RiftBuild V0 binary manifest size oracle failed" }
        require(sha256(bytes) == RIFTPP_V0_BINARY_MANIFEST_SHA) { "RiftBuild V0 binary manifest SHA-256 oracle failed" }
        return bytes
    }

    private fun buildManifestStringPool(): ByteArray {
        val offsets = ArrayList<Int>(RIFTPP_V0_MANIFEST_STRINGS.size)
        val data = ByteArrayOutputStream()
        for (value in RIFTPP_V0_MANIFEST_STRINGS) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(value.length < 0x80 && bytes.size < 0x80) { "RiftBuild V0 manifest string exceeds one-byte UTF-8 pool length" }
            offsets.add(data.size())
            writeManifestLength8(data, value.length)
            writeManifestLength8(data, bytes.size)
            data.write(bytes)
            data.write(0)
        }
        while (data.size() % 4 != 0) data.write(0)

        val stringsStart = 28 + (RIFTPP_V0_MANIFEST_STRINGS.size * 4)
        val dataBytes = data.toByteArray()
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_STRING_POOL_TYPE, 28, stringsStart + dataBytes.size)
        writeManifestU32(output, RIFTPP_V0_MANIFEST_STRINGS.size)
        writeManifestU32(output, 0)
        writeManifestU32(output, XML_UTF8_FLAG)
        writeManifestU32(output, stringsStart)
        writeManifestU32(output, 0)
        for (offset in offsets) writeManifestU32(output, offset)
        output.write(dataBytes)
        return output.toByteArray()
    }

    private fun buildManifestResourceMap(): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestChunkHeader(output, XML_RESOURCE_MAP_TYPE, 8, 8 + (RIFTPP_V0_MANIFEST_RESOURCE_IDS.size * 4))
        for (id in RIFTPP_V0_MANIFEST_RESOURCE_IDS) writeManifestU32(output, id)
        return output.toByteArray()
    }

    private fun buildManifestNamespace(type: Int): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, type, 24)
        writeManifestU32(output, manifestStringIndex("android"))
        writeManifestU32(output, manifestStringIndex("http://schemas.android.com/apk/res/android"))
        return output.toByteArray()
    }

    private fun buildManifestStartElement(name: String, attrs: List<ManifestAttr>): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_START_ELEMENT_TYPE, 36 + (attrs.size * 20))
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, manifestStringIndex(name))
        writeManifestU16(output, 20)
        writeManifestU16(output, 20)
        writeManifestU16(output, attrs.size)
        writeManifestU16(output, 0)
        writeManifestU16(output, 0)
        writeManifestU16(output, 0)
        for (attr in attrs) {
            writeManifestU32(output, attr.namespace)
            writeManifestU32(output, attr.name)
            writeManifestU32(output, attr.rawValue)
            writeManifestU16(output, 8)
            output.write(0)
            output.write(attr.dataType)
            writeManifestU32(output, attr.data)
        }
        return output.toByteArray()
    }

    private fun buildManifestEndElement(name: String): ByteArray {
        val output = ByteArrayOutputStream()
        writeManifestNodeHeader(output, XML_END_ELEMENT_TYPE, 24)
        writeManifestU32(output, XML_NO_INDEX)
        writeManifestU32(output, manifestStringIndex(name))
        return output.toByteArray()
    }

    private fun manifestStringAttr(name: String, value: String, namespace: Int = manifestStringIndex("http://schemas.android.com/apk/res/android")): ManifestAttr =
        ManifestAttr(namespace, manifestStringIndex(name), manifestStringIndex(value), XML_VALUE_STRING, manifestStringIndex(value))

    private fun manifestIntAttr(name: String, rawValue: String, value: Int): ManifestAttr =
        ManifestAttr(
            manifestStringIndex("http://schemas.android.com/apk/res/android"),
            manifestStringIndex(name),
            manifestStringIndex(rawValue),
            XML_VALUE_INT_DEC,
            value
        )

    private fun manifestBoolAttr(name: String, rawValue: String, value: Boolean): ManifestAttr =
        ManifestAttr(
            manifestStringIndex("http://schemas.android.com/apk/res/android"),
            manifestStringIndex(name),
            manifestStringIndex(rawValue),
            XML_VALUE_INT_BOOLEAN,
            if (value) -1 else 0
        )

    private fun manifestStringIndex(value: String): Int {
        val index = RIFTPP_V0_MANIFEST_STRINGS.indexOf(value)
        require(index >= 0) { "RiftBuild V0 manifest string is not in the frozen pool: " + value }
        return index
    }

    private fun writeManifestNodeHeader(output: ByteArrayOutputStream, type: Int, size: Int) {
        writeManifestChunkHeader(output, type, 16, size)
        writeManifestU32(output, 1)
        writeManifestU32(output, XML_NO_INDEX)
    }

    private fun writeManifestChunkHeader(output: ByteArrayOutputStream, type: Int, headerSize: Int, size: Int) {
        writeManifestU16(output, type)
        writeManifestU16(output, headerSize)
        writeManifestU32(output, size)
    }

    private fun writeManifestLength8(output: ByteArrayOutputStream, value: Int) {
        require(value in 0..0x7f) { "RiftBuild V0 manifest UTF-8 length overflow" }
        output.write(value)
    }

    private fun writeManifestU16(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeManifestU32(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 24) and 0xff)
    }

    private fun inspectPrepared(ref: ProjectRef, target: String, requiresDex: Boolean = false): JSONObject {
        val prepared = File(ref.file, "build/riftbuild/prepared").canonicalFile
        if (!prepared.isDirectory || !confinedTo(ref.file, prepared)) {
            return JSONObject()
                .put("ready", false)
                .put("root", ref.display + "/build/riftbuild/prepared")
                .put("blockers", JSONArray().put("prepared directory missing"))
        }
        val manifest = File(prepared, "AndroidManifest.xml")
        val arm64 = nativeLibraries(prepared, "arm64-v8a")
        val arm32 = nativeLibraries(prepared, "armeabi-v7a")
        val dexFiles = prepared.listFiles()
            ?.filter { it.isFile && DEX_ENTRY.matches(it.name) }
            ?.sortedBy { dexEntryOrder(it.name) }
            .orEmpty()
        val blockers = JSONArray()
        if (!isBinaryAndroidManifest(manifest)) blockers.put("AndroidManifest.xml must be compiled Android binary XML")
        if ((target == "arm64" || target == "universal") && arm64.isEmpty()) blockers.put("arm64-v8a native library missing")
        if ((target == "arm32" || target == "universal") && arm32.isEmpty()) blockers.put("armeabi-v7a native library missing")
        if (requiresDex && dexFiles.none { it.name == "classes.dex" }) blockers.put("classes.dex missing for code-bearing Activity package")
        return JSONObject()
            .put("ready", blockers.length() == 0)
            .put("root", ref.display + "/build/riftbuild/prepared")
            .put("binaryManifest", isBinaryAndroidManifest(manifest))
            .put("requiresDex", requiresDex)
            .put("dexFiles", JSONArray(dexFiles.map { it.name }))
            .put("arm64Libraries", JSONArray(arm64.map { it.name }))
            .put("arm32Libraries", JSONArray(arm32.map { it.name }))
            .put("blockers", blockers)
    }

    private fun collectPreparedEntries(prepared: File, target: String): List<Pair<String, File>> {
        require(prepared.isDirectory) { "prepared package directory missing" }
        val allowedTop = setOf("AndroidManifest.xml", "resources.arsc", "lib", "assets")
        prepared.listFiles()?.forEach { entry ->
            require(
                allowedTop.contains(entry.name) ||
                    (entry.isFile && DEX_ENTRY.matches(entry.name))
            ) {
                "unsupported prepared APK input: " + entry.name
            }
        }

        val out = ArrayList<Pair<String, File>>()
        val manifest = File(prepared, "AndroidManifest.xml")
        require(isBinaryAndroidManifest(manifest)) { "AndroidManifest.xml must be compiled Android binary XML" }
        out += "AndroidManifest.xml" to manifest
        prepared.listFiles()
            ?.filter { it.isFile && DEX_ENTRY.matches(it.name) }
            ?.sortedBy { dexEntryOrder(it.name) }
            ?.forEach { dex -> out += dex.name to dex }
        File(prepared, "resources.arsc").takeIf { it.isFile }?.let { out += "resources.arsc" to it }

        val abis = when (target) {
            "arm64" -> listOf("arm64-v8a")
            "arm32" -> listOf("armeabi-v7a")
            else -> listOf("arm64-v8a", "armeabi-v7a")
        }
        for (abi in abis) {
            val libs = nativeLibraries(prepared, abi)
            require(libs.isNotEmpty()) { abi + " native library missing" }
            libs.sortedBy { it.name }.forEach { lib ->
                require(lib.name.endsWith(".so") && SAFE_SEGMENT.matches(lib.name)) { "unsafe native library name" }
                out += "lib/" + abi + "/" + lib.name to lib
            }
        }

        val assets = File(prepared, "assets")
        if (assets.isDirectory) {
            assets.walkTopDown().filter { it.isFile }.forEach { file ->
                RiftDeadline.check("RiftBuild prepared assets")
                require(confinedTo(assets, file)) { "prepared asset escaped root" }
                val relative = file.relativeTo(assets).invariantSeparatorsPath
                require(safeZipPath(relative)) { "unsafe prepared asset path: " + relative }
                out += "assets/" + relative to file
            }
        }
        return out
    }

    private fun dexEntryOrder(name: String): Int =
        if (name == "classes.dex") 1
        else name.removePrefix("classes").removeSuffix(".dex").toIntOrNull()
            ?: Int.MAX_VALUE

    private fun nativeLibraries(prepared: File, abi: String): List<File> {
        val dir = File(prepared, "lib/" + abi).canonicalFile
        if (!dir.isDirectory || !confinedTo(prepared, dir)) return emptyList()
        return dir.listFiles()?.filter { it.isFile && it.extension == "so" && confinedTo(dir, it) }.orEmpty()
    }

    private fun isBinaryAndroidManifest(file: File): Boolean {
        if (!file.isFile || file.length() < 8L || file.length() > Int.MAX_VALUE.toLong()) return false
        val header = ByteArray(8)
        file.inputStream().use { if (it.read(header) != 8) return false }
        val type = (header[0].toInt() and 0xff) or ((header[1].toInt() and 0xff) shl 8)
        val headerSize = (header[2].toInt() and 0xff) or ((header[3].toInt() and 0xff) shl 8)
        val declaredSize =
            (header[4].toInt() and 0xff) or
                ((header[5].toInt() and 0xff) shl 8) or
                ((header[6].toInt() and 0xff) shl 16) or
                ((header[7].toInt() and 0xff) shl 24)
        return type == XML_TYPE && headerSize == 8 && declaredSize == file.length().toInt()
    }

    private fun blockedRun(ref: ProjectRef, target: String, currentPlan: JSONObject): JSONObject {
        val value = JSONObject()
            .put("format", "riftbuild-native-run-v1")
            .put("id", runId())
            .put("state", "blocked")
            .put("project", ref.display)
            .put("projectSha256", currentPlan.optString("projectSha256"))
            .put("target", target)
            .put("plan", currentPlan)
            .put("blockers", JSONArray()
                .put("prepared native ELF and/or Android binary manifest is incomplete")
                .put("APK signing is pending"))
            .put("at", System.currentTimeMillis())
        writeRun(value)
        return value
    }

    private fun writeRun(value: JSONObject) {
        val id = value.optString("runId").ifBlank { value.optString("id") }.ifBlank { runId() }
        atomicWrite(File(runRoot, safeName(id) + ".json"), value.toString(2).toByteArray(Charsets.UTF_8))
        runRoot.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_RUNS)
            ?.forEach { it.delete() }
    }

    private fun stage(id: String, state: String, blocker: String?): JSONObject =
        JSONObject().put("id", id).put("state", state).put("blocker", blocker ?: JSONObject.NULL)

    private fun artifactProjectRoot(ref: ProjectRef): File {
        val key = safeName(ref.file.name) + "-" + sha256(ref.display.toByteArray(Charsets.UTF_8)).take(8)
        val root = File(artifactRoot, key).canonicalFile
        require(confinedTo(artifactRoot, root)) { "RiftBuild artifact root escaped D:/Builds" }
        return root
    }

    private fun artifactProjectDisplay(ref: ProjectRef): String = artifactDisplay(artifactProjectRoot(ref))

    private fun resolveArtifact(raw: String): File {
        require(raw.isNotBlank()) { "RiftBuild artifact path is required" }
        val value = raw.trim().replace('\\', '/')
        val absolute = when {
            value == "/D:/Builds" || value.startsWith("/D:/Builds/") -> value
            value == "D:/Builds" || value.startsWith("D:/Builds/") -> "/" + value
            else -> "/D:/Builds/" + value.trimStart('/')
        }
        val display = RiftVolumePaths.normalizeDisplay(absolute)
        require(display.startsWith("/D:/Builds/")) { "RiftBuild artifact must live under D:/Builds" }
        val relative = display.removePrefix("/D:/Builds/").trim('/')
        require(relative.isNotBlank()) { "RiftBuild artifact file is required" }
        val file = File(artifactRoot, relative).canonicalFile
        require(confinedTo(artifactRoot, file)) { "RiftBuild artifact escaped D:/Builds" }
        require(file.isFile) { "RiftBuild artifact not found: " + display }
        return file
    }

    private fun artifactDisplay(file: File): String {
        val canonical = file.canonicalFile
        require(confinedTo(artifactRoot, canonical)) { "artifact escaped D:/Builds" }
        val relative = canonical.relativeTo(artifactRoot).invariantSeparatorsPath
        return if (relative.isBlank()) "/D:/Builds" else "/D:/Builds/" + relative
    }

    private fun firstExisting(root: File, vararg paths: String): File? =
        paths.asSequence().map { File(root, it) }.firstOrNull { it.isFile }

    private fun readTextBounded(file: File): String {
        require(file.isFile) { "file not found" }
        require(file.length() <= MAX_TEXT_BYTES) { "text file exceeds RiftBuild limit" }
        return file.readText(Charsets.UTF_8)
    }

    private fun safeZipPath(raw: String): Boolean {
        val value = raw.replace('\\', '/')
        return value.isNotBlank() && !value.startsWith("/") &&
            !Regex("^[A-Za-z]:").containsMatchIn(value) &&
            value.split('/').all { it.isNotBlank() && it != "." && it != ".." && SAFE_SEGMENT.matches(it) }
    }

    private fun safeName(raw: String): String {
        val clean = raw.replace(Regex("[^A-Za-z0-9._+-]"), "-").trim('-').take(120)
        require(clean.isNotBlank()) { "RiftBuild name is empty after normalization" }
        return clean
    }

    private fun confinedTo(root: File, child: File): Boolean {
        val a = root.canonicalFile
        val b = child.canonicalFile
        return b == a || b.path.startsWith(a.path + File.separator)
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "." + target.name + ".riftbuild-" + System.nanoTime() + ".tmp")
        temp.writeBytes(bytes)
        if (target.exists()) require(target.delete()) { "could not replace build record" }
        require(temp.renameTo(target)) { "could not publish build record" }
    }

    private fun treeSha256(root: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach { file ->
            RiftDeadline.check("RiftBuild tree hash")
            digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    RiftDeadline.check("RiftBuild tree hash")
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            digest.update(0.toByte())
        }
        return hex(digest.digest())
    }

    private fun deleteTreeBounded(root: File, maxEntries: Int): Boolean {
        var entries = 0
        fun remove(node: File): Boolean {
            RiftDeadline.check("RiftBuild cleanup")
            require(++entries <= maxEntries) { "RiftBuild cleanup exceeds $maxEntries entries" }
            if (node.isDirectory) {
                val children = node.listFiles()
                    ?: throw IllegalStateException("Could not read RiftBuild cleanup directory")
                children.forEach { child ->
                    require(remove(child)) { "Could not delete RiftBuild cleanup entry" }
                }
            }
            return node.delete()
        }
        return !root.exists() || remove(root)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                RiftDeadline.check("RiftBuild file hash")
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
    private fun runId(): String = "build-" + System.currentTimeMillis() + "-" + java.lang.Long.toHexString(System.nanoTime()).takeLast(10)
}
