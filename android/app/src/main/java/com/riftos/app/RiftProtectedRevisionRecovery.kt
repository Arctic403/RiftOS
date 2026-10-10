package com.riftos.app

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * Host-side fail-closed recovery for critical independently loaded components.
 *
 * This does not execute or promote unproven code. On an interrupted/crashed
 * candidate we quarantine its revision and atomically restore the prior
 * accepted external pointer IF both sealed bytes and qualification survive.
 * Otherwise we revoke the pointer to the migration-time embedded fallback.
 *
 * Core recovery runs on the next permitted main-process boot, Shell recovery
 * runs on the next graphical :riftShell start. Never hot-swap live classes.
 */
internal object RiftProtectedRevisionRecovery {
    const val SCHEMA = "riftos.host.protected-recovery/1"
    private const val MAX_QUALIFICATION_BYTES = 4096
    private const val TAG = "RiftProtectedRecovery"

    internal fun receiptFile(context: Context, component: String, sha: String): File {
        require(component == "core" || component == "shell") {
            "Critical revision component invalid"
        }
        require(sha.matches(Regex("^[0-9a-f]{64}$"))) {
            "Critical revision digest invalid"
        }
        return File(RiftBootstrapComponentStore.root(context),
            "$component.$sha.qualified.json")
    }

    fun verifiedReceipt(context: Context, component: String, candidate: JSONObject): JSONObject {
        val sha = candidate.getString("sha256")
        val entrypoint = candidate.getString("entrypoint")
        val file = receiptFile(context, component, sha)
        require(!Files.isSymbolicLink(file.toPath()) &&
            !Files.isSymbolicLink(File(file.path + ".bak").toPath())) {
            "Critical qualification path symlinked"
        }
        val input = AtomicFile(file).openRead()
        val bytes = input.use {
            val buffer = ByteArray(MAX_QUALIFICATION_BYTES + 1)
            val size = it.read(buffer)
            require(size in 1..MAX_QUALIFICATION_BYTES && it.read() == -1) {
                "Critical qualification record too large"
            }
            buffer.copyOf(size)
        }
        val receipt = JSONObject(String(bytes, Charsets.UTF_8))
        require(receipt.getString("schema") ==
            "riftos.$component.qualified/1" &&
            receipt.getInt("abi") == RiftHostCoreComponents.ABI_VERSION &&
            receipt.getString("sha256") == sha &&
            receipt.getString("entrypoint") == entrypoint &&
            receipt.getBoolean("completeOwnership") &&
            receipt.getBoolean("inactiveLoadProven")) {
            "External $component has no independent exact-SHA qualification"
        }
        return receipt
    }

    /**
     * Return next-boot policy, never actually start a second Core/Shell here.
     * A previous known-good external pointer is made ready for the following
     * boot; the currently booting process runs the embedded fallback.
     */
    @Synchronized
    fun rollback(context: Context, component: String, reason: String): JSONObject {
        require(component == "core" || component == "shell")
        val outcome = JSONObject().put("schema", SCHEMA)
            .put("component", component)
            .put("reason", reason.take(120))
            .put("selectedForCurrentBoot", "embedded")
        val result = runCatching {
            RiftComponentReleaseLedger.failed(context, component, reason)
            val lastGood = RiftComponentReleaseLedger.recoveryTarget(context, component)
            if (lastGood != null) {
                verifiedReceipt(context, component, lastGood)
                RiftBootstrapComponentStore.writeProtectedPointer(context, component, lastGood)
                RiftComponentReleaseLedger.confirmRollback(
                    context, component, lastGood.getString("sha256"))
                RiftBootstrapComponentStore.clearProtectedStartupMarker(context, component)
                "previous-external-next-boot"
            } else {
                if (component == "core") {
                    RiftBootstrapComponentStore.resetCoreToEmbedded(context)
                } else {
                    RiftBootstrapComponentStore.resetShellToEmbedded(context)
                }
                "embedded-next-boot"
            }
        }
        return result.fold(
            onSuccess = {
                outcome.put("nextBoot", it).put("rollbackRecorded", true)
            },
            onFailure = { failure ->
                // A corrupt/missing N-1 executable must NEVER remain active.
                Log.e(TAG, "External $component rollback to known good failed", failure)
                runCatching {
                    if (component == "core") {
                        RiftBootstrapComponentStore.resetCoreToEmbedded(context)
                    } else {
                        RiftBootstrapComponentStore.resetShellToEmbedded(context)
                    }
                }.onFailure {
                    Log.e(TAG, "Embedded recovery requires manual intervention", it)
                }
                outcome.put("nextBoot", "embedded-next-boot")
                    .put("rollbackRecorded", false)
                    .put("failureType", failure.javaClass.simpleName)
            }
        )
    }
}
