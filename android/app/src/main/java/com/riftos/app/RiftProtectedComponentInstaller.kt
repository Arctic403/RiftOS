package com.riftos.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/**
 * Protected Core/Shell version staging and activation. Never called from
 * a RAPP, generic module or raw shell command; Core IPC one-use trusted
 * admin ticket gates must be checked by the caller before entering here.
 *
 * Stage re-verifies exact manifest/DEX identity and static binary closure,
 * then writes an immutable revision-specific qualification receipt.
 * Activation requires a FRESH separate user approval for SHA/component and
 * only updates the next-start pointer; never hot-swaps an active classloader.
 */
internal object RiftProtectedComponentInstaller {
    const val SCHEMA = "riftos.protected-component-operation/1"
    private const val MAX_RECORD = 4096
    private val SHA = Regex("^[0-9a-f]{64}$")

    private fun metadata(context: Context, component: String, sha: String) =
        File(RiftBootstrapComponentStore.root(context), "$component.$sha.manifest.json")

    private fun atomicWrite(file: File, bytes: ByteArray) {
        require(bytes.size in 1..MAX_RECORD) { "Protected receipt exceeds bound" }
        val root = file.parentFile ?: error("Protected receipt has no root")
        require(!Files.isSymbolicLink(root.toPath()) &&
            root.isDirectory) { "Protected receipt root unavailable" }
        for (suffix in listOf("", ".bak", ".new")) {
            require(!Files.isSymbolicLink(File(file.path + suffix).toPath())) {
                "Protected component record path symlinked"
            }
        }
        val atomic = AtomicFile(file)
        val writer = atomic.startWrite()
        try {
            writer.write(bytes)
            atomic.finishWrite(writer)
        } catch (error: Throwable) {
            atomic.failWrite(writer)
            throw error
        }
    }

    private fun atomicRead(file: File): JSONObject {
        require(!Files.isSymbolicLink(file.toPath()) &&
            !Files.isSymbolicLink(File(file.path + ".bak").toPath())) {
            "Protected component record symlinked"
        }
        val bytes = AtomicFile(file).openRead().use {
            val buf = ByteArray(MAX_RECORD + 1)
            val count = it.read(buf)
            require(count in 1..MAX_RECORD && it.read() == -1) {
                "Protected component record invalid size"
            }
            buf.copyOf(count)
        }
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    @Synchronized
    fun stage(context: Context, manifestBytes: ByteArray,
              descriptor: InputStream): JSONObject {
        val manifest = RiftProtectedComponentManifest.parse(manifestBytes)
        require(manifest.component == "core" || manifest.component == "shell")
        val staged = RiftBootstrapComponentStore.stage(
            context, manifest.component, manifest.entrypoint, descriptor)
        require(staged.getString("sha256") == manifest.sha256) {
            "Protected DEX does not match exact approved manifest"
        }
        val file = RiftBootstrapComponentStore.dexFile(
            context, manifest.component, manifest.sha256)
        val inspected = RiftProtectedDexVerifier.inspect(context, manifest, file)
        require(inspected.getBoolean("inactiveLinkProven") &&
            !inspected.getBoolean("behavioralDeviceProof")) {
            "Protected structural closure proof unavailable"
        }
        val previous = metadata(context, manifest.component, manifest.sha256)
        if (previous.exists() || File(previous.path + ".bak").exists()) {
            val present = atomicRead(previous)
            require(present.getString("manifestDigest") == manifest.identityDigest()) {
                "Existing protected revision metadata identity mismatch"
            }
        } else {
            atomicWrite(previous, manifest.receipt()
                .toString().toByteArray(Charsets.UTF_8))
        }
        val qualifiedFile = RiftProtectedRevisionRecovery.receiptFile(
            context, manifest.component, manifest.sha256)
        val proof = JSONObject()
            .put("schema", "riftos." + manifest.component + ".qualified/1")
            .put("abi", manifest.abi)
            .put("sha256", manifest.sha256)
            .put("entrypoint", manifest.entrypoint)
            .put("version", manifest.version)
            .put("buildSha256", manifest.buildSha256)
            .put("manifestDigest", manifest.identityDigest())
            .put("completeOwnership", true)
            .put("inactiveLoadProven", true)
            .put("binaryClosureOnly", true)
            .put("behavioralDeviceProof", false)
            .put("classCount", inspected.getInt("classCount"))
            .put("qualifiedAt", System.currentTimeMillis())
        atomicWrite(qualifiedFile, proof.toString().toByteArray(Charsets.UTF_8))
        return JSONObject().put("schema", SCHEMA)
            .put("component", manifest.component)
            .put("entrypoint", manifest.entrypoint)
            .put("version", manifest.version)
            .put("sha256", manifest.sha256)
            .put("manifestDigest", manifest.identityDigest())
            .put("staged", true).put("qualifiedForInactiveLoad", true)
            .put("activated", false).put("deviceAccepted", false)
    }

    /** Validated metadata of a Core/Shell SHA, never a caller-controlled path. */
    fun staged(context: Context, component: String, sha: String):
        RiftProtectedComponentManifest {
        require((component == "core" || component == "shell") && sha.matches(SHA)) {
            "Invalid protected revision identity"
        }
        val receipt = atomicRead(metadata(context, component, sha))
        val parsed = JSONObject(receipt.toString())
        parsed.remove("manifestDigest")
        val manifest = RiftProtectedComponentManifest.parse(
            parsed.toString().toByteArray(Charsets.UTF_8))
        require(manifest.component == component && manifest.sha256 == sha &&
            manifest.identityDigest() == receipt.getString("manifestDigest")) {
            "Protected staged metadata mismatch"
        }
        RiftProtectedRevisionRecovery.verifiedReceipt(
            context, component, manifest.activationRecord())
        val dex = RiftBootstrapComponentStore.dexFile(context, component, sha)
        RiftProtectedDexVerifier.inspect(context, manifest, dex)
        return manifest
    }

    /**
     * Scope already approved, ticket already consumed by Core's admin gate.
     * An interrupted commit is reconciled by the recovery journal on boot.
     */
    @Synchronized
    fun activate(context: Context, component: String, sha: String): JSONObject {
        val manifest = staged(context, component, sha)
        val pointer = manifest.activationRecord()
        val root = RiftBootstrapComponentStore.root(context)
        val marker = File(root, "$component.booting")
        require(!marker.exists() &&
            RiftBootstrapComponentStore.active(context, component)
                ?.optString("sha256") != sha) {
            "Protected component already active or startup still unaccepted"
        }
        RiftComponentReleaseLedger.recordPrepared(context, component, pointer)
        try {
            RiftBootstrapComponentStore.writeProtectedPointer(context, component, pointer)
        } catch (failure: Throwable) {
            runCatching {
                RiftProtectedRevisionRecovery.rollback(
                    context, component, "activation-pointer-failed")
            }.onFailure { failure.addSuppressed(it) }
            throw failure
        }
        return JSONObject().put("schema", SCHEMA)
            .put("component", component)
            .put("sha256", sha)
            .put("entrypoint", manifest.entrypoint)
            .put("activated", true)
            .put("restartRequired", true)
            .put("deviceAccepted", false)
    }

    /**
     * Explicit one-use approved promotion AFTER a real installed-device test.
     * The running implementation SHA and Android process identity must match;
     * staging/activation alone can NEVER set last-known-good.
     */
    @Synchronized
    fun acceptProven(context: Context, component: String, sha: String,
                     trustedShellPid: Int): JSONObject {
        val manifest = staged(context, component, sha)
        val pointer = RiftBootstrapComponentStore.active(context, component)
            ?: error("No active external component to accept")
        require(pointer.getString("sha256") == sha &&
            pointer.getString("entrypoint") == manifest.entrypoint &&
            File(RiftBootstrapComponentStore.root(context),
                "$component.booting").isFile) {
            "Only a booted, still unaccepted exact external SHA may be promoted"
        }
        if (component == "core") {
            val selected = RiftCoreCandidateSwitch.status()
            require(selected.optString("selected") == "external-unaccepted" &&
                selected.optString("selectedSha256") == sha) {
                "Core is not currently executing the exact external candidate"
            }
        } else {
            val attached = RiftShellCandidateSwitch.attached(context)
                ?: error("No verified graphical Shell attach receipt")
            val shell = RiftCoreShellRecovery.status()
            require(attached.getString("sha256") == sha &&
                attached.getInt("pid") == trustedShellPid &&
                shell.optInt("shellPid", -1) == trustedShellPid &&
                shell.optString("phase") == "connected") {
                "Graphical Shell did not attach from the authenticated Android PID"
            }
        }
        RiftComponentReleaseLedger.acceptProven(context, component, sha)
        RiftBootstrapComponentStore.clearProtectedStartupMarker(context, component)
        return JSONObject().put("schema", SCHEMA)
            .put("component", component)
            .put("sha256", sha)
            .put("deviceAccepted", true)
            .put("lastKnownGood", true)
            .put("inProcessHotSwapEnabled", false)
    }

    fun status(context: Context): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("core", RiftComponentReleaseLedger.status(context, "core"))
        .put("shell", RiftComponentReleaseLedger.status(context, "shell"))
        .put("inProcessHotSwapEnabled", false)
        .put("automaticPromotionEnabled", false)
}
