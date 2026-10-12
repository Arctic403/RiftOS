package com.riftos.external.core

import android.content.Context
import org.json.JSONObject

/**
 * Minimal independent Core v0.6.2 continuation-budget hotfix candidate.
 * Shares the device-proven v0.6.1 Core execution model; only the
 * IndependentRappSessionsV1 MAX_DEPTH change is intended in this DEX.
 *
 * This must be built to a NEW, isolated protected-component pair. It does
 * not replace the user's selected v0.6.1 or recovery backup automatically.
 */
class IndependentCoreV062 : IndependentCoreV1() {
    override fun coreStatus(context: Context): JSONObject =
        super.coreStatus(context)
            .put("candidateRevision", "0.6.2")
            .put("rappEffectContinuationLimit", 4096)
}
