package com.riftos.app

import android.app.Application
import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.InputStream

/**
 * APK-owned user-initiated install bridge. Works in Recovery without Core.
 * It reuses the EXISTING protected component parser, sealed staging and DEX
 * verifier. Staging never selects a component; install changes next boot only.
 * No exported service, shell command or RAPP entry point is added.
 */
internal object RiftManualRecoveryInstaller {
    private fun requireRecoveryProcess(context: Context) {
        require(Build.VERSION.SDK_INT >= 28 &&
            Application.getProcessName() == context.packageName + ":riftRecovery") {
            "Protected installation requires the independent Recovery UI process"
        }
    }

    fun stage(context: Context, manifestBytes: ByteArray, dex: InputStream): JSONObject {
        requireRecoveryProcess(context)
        require(manifestBytes.size in 1..32 * 1024) {
            "Component manifest file must be bounded"
        }
        return RiftProtectedComponentInstaller.stage(context, manifestBytes, dex)
    }

    fun install(context: Context, component: String, sha256: String): JSONObject {
        requireRecoveryProcess(context)
        require(component == "core" || component == "shell") {
            "Only verified external Core and Shell can be selected"
        }
        // Existing protected installer also verifies this SHA, its manifest,
        // class closure, startup marker and accepted prior backup before select.
        return RiftProtectedComponentInstaller.activate(context, component, sha256)
    }
}
