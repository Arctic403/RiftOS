package com.riftos.external.core

import android.content.Context
import org.json.JSONObject

/**
 * Core v0.6.3: same independently managed RAPP engine as 0.6.2, adding only
 * explicitly-granted apk.install UI-effect forwarding to the Android Shell.
 * No automatic Core activation, backup mutation, or embedded build tools.
 */
open class IndependentCoreV063 : IndependentCoreV1() {
    override fun coreStatus(context: Context): JSONObject =
        super.coreStatus(context)
            .put("candidateRevision", "0.6.3")
            .put("rappEffectContinuationLimit", 4096)
            .put("apkInstallPermissionSupported", true)
}
