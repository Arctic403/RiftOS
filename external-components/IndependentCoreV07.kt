package com.riftos.external.core

import android.content.Context
import com.riftos.app.RiftCoreSystemServicesV1
import org.json.JSONObject

/**
 * Independent Core v0.7 entrypoint. Reuses the proven external Core RAPP
 * engine, package/session/effect objects and version-one runtime ABI.
 * Only the additive, typed system-service interface is new.
 *
 * This DEX is isolated from selected Core v0.6.1 and the user-owned backup.
 * No MCP/relay runtime and no enterprise admin approval layer is included.
 */
class IndependentCoreV07 : IndependentCoreV1(), RiftCoreSystemServicesV1 {
    companion object {
        private const val VERSION = "0.7.0"
    }

    @Volatile private var systemOwner: IndependentCoreSystemServicesV07? = null
    private val systemLock = Any()

    override fun systemRequest(context: Context, request: JSONObject): JSONObject {
        // initialize() is Core V1's existing idempotent, validated bootstrap;
        // a separate "second Core" engine must never be instantiated here.
        super.initialize(context)
        val services = systemOwner ?: synchronized(systemLock) {
            systemOwner ?: IndependentCoreSystemServicesV07(
                context.applicationContext, this
            ).also { systemOwner = it }
        }
        return services.request(request)
    }

    override fun coreStatus(context: Context): JSONObject =
        super.coreStatus(context)
            .put("candidateRevision", VERSION)
            .put("systemServicesReady", true)
            .put("systemServicesSchema", "riftos.core.system-request/1")
            .put("adminTicketStackAdded", false)
}
