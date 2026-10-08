package com.riftos.app

import android.content.Context
import java.util.concurrent.Executors

/**
 * C1.1-B1: application-context-owned execution, serialization and state
 * persistence for generic RAPP events. No Activity, View or RiftShell authority.
 *
 * The caller is currently a UI host. Capability-effect prompts, event chaining
 * and presentation are NOT yet independent of that host.
 */
class RiftCoreAppExecutor(context: Context) {
    companion object {
        private const val OUTPUT_BYTES = 512 * 1024
        private const val EVENT_TIMEOUT_MS = 6500L
    }

    data class Outcome(
        val frame: RiftAppAbi.Frame? = null,
        val effects: List<RiftAppAbi.HostEffect> = emptyList(),
        val error: String? = null
    )

    private val app = context.applicationContext
    private val sessions = RiftCoreRuntime.sessions(app)
    private val packages = RiftCoreRuntime.packages(app)
    private val providers = RiftCoreRuntime.runtimes(app)
    private val quickJs = RiftRappQuickJsExecutor()
    private val capabilityBroker = RiftRappCapabilityBroker(app)
    private val maxEffectDepth = 1024

    // Owned by Core for the main process lifetime, not by MainActivity.
    private val events = Executors.newSingleThreadExecutor { work ->
        Thread(work, "rift-core-rapp-event").apply { isDaemon = true }
    }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { work ->
        Thread(work, "rift-core-rapp-watchdog").apply { isDaemon = true }
    }

    fun execute(
        attachment: RiftCoreAppSessions.Attachment,
        payload: RiftAppAbi.RuntimePayload,
        adapter: RiftAppRuntimeAdapter,
        event: RiftAppAbi.Event,
        complete: (Outcome) -> Unit
    ) {
        require(sessions.matchesExecution(attachment, payload, adapter)) {
            "RAPP Core execution identity, permissions or runtime mismatch"
        }
        RiftBoundedAsync.submit(
            executor = events,
            watchdog = watchdog,
            timeoutMs = EVENT_TIMEOUT_MS,
            timeoutValue = { Outcome(error = "RAPP event timed out") },
            failureValue = { error ->
                Outcome(error = error.message ?: error.javaClass.simpleName)
            },
            work = {
                require(sessions.isAttached(attachment)) {
                    "RAPP Core session has no attached event client"
                }
                val effective = payload.copy(
                    program = attachment.record.programSnapshot()
                )
                val envelope = adapter.encodeEvent(
                    effective,
                    event,
                    attachment.record.nextEventSequence()
                )
                val output = executeRuntime(
                    adapter.executorKind,
                    payload.runtime,
                    envelope
                )
                RiftDeadline.check("RAPP Core runtime")
                val decoded = adapter.decodeOutput(output)
                RiftDeadline.check("RAPP Core state commit")
                decoded.nextProgram?.let { next ->
                    sessions.commitFromExecution(attachment, next) { updated ->
                        packages.persistState(attachment.record.id, updated)
                    }
                }
                Outcome(frame = decoded.frame, effects = decoded.effects)
            },
            reply = complete
        )
    }

    /**
     * C1.1-B2-B: the entire effect/result continuation belongs to Core.
     * The graphical client receives a final frame only. A stale UI attachment
     * may not authorize, execute or commit any further Core effect.
     */
    fun executeChained(
        attachment: RiftCoreAppSessions.Attachment,
        payload: RiftAppAbi.RuntimePayload,
        adapter: RiftAppRuntimeAdapter,
        event: RiftAppAbi.Event,
        complete: (Outcome) -> Unit
    ) {
        fun step(current: RiftAppAbi.Event, depth: Int) {
            if (!sessions.matchesExecution(attachment, payload, adapter)) {
                complete(Outcome(error = "RAPP Core event attachment is stale"))
                return
            }
            if (depth > maxEffectDepth) {
                complete(Outcome(error = "RAPP Core effect chain exceeded bound"))
                return
            }
            execute(attachment, payload, adapter, current) runtimeReply@{ outcome ->
                if (!sessions.matchesExecution(attachment, payload, adapter)) {
                    complete(Outcome(error = "RAPP Core event attachment is stale"))
                    return@runtimeReply
                }
                if (outcome.error != null || outcome.effects.isEmpty()) {
                    complete(outcome)
                    return@runtimeReply
                }
                if (outcome.effects.size != 1 || depth >= maxEffectDepth) {
                    complete(Outcome(error = "RAPP Core effect chain is invalid or exceeded bound"))
                    return@runtimeReply
                }
                val effect = outcome.effects.single()
                capabilityBroker.execute(
                    appId = attachment.record.id,
                    appName = attachment.record.name,
                    declared = payload.permissions,
                    effect = effect,
                    stillValid = { sessions.matchesExecution(attachment, payload, adapter) }
                ) capabilityReply@{ result ->
                    if (!sessions.matchesExecution(attachment, payload, adapter)) {
                        complete(Outcome(error = "RAPP Core event attachment is stale"))
                        return@capabilityReply
                    }
                    step(
                        RiftAppAbi.Event(
                            kind = RiftAppAbi.EventKind.HOST_EFFECT_RESULT,
                            targetId = effect.requestId,
                            arg0 = if (result.ok) 1 else 0,
                            arg1 = result.token,
                            text = if (result.ok) result.text else result.error.orEmpty(),
                            bytes = result.bytes
                        ),
                        depth + 1
                    )
                }
            }
        }
        step(event, 0)
    }

    private fun executeRuntime(
        kind: String,
        runtime: ByteArray,
        envelope: ByteArray
    ): ByteArray = when (kind) {
        RiftAppExecutionKind.NATIVE_BUFFER ->
            providers.execute(kind, runtime, envelope, OUTPUT_BYTES) {
                val result = RiftNativeBufferCompilerService.compile(
                    app, runtime, envelope, OUTPUT_BYTES
                )
                val status = result.getString("status") ?: "host-reject"
                require(status == "success") {
                    buildString {
                        append("RAPP runtime ")
                        append(status)
                        append(" · host=")
                        append(result.getInt("hostStatus", -1))
                        append(" · return=")
                        append(result.getLong("returnValue", 0xffffffffL))
                        result.getString("detail")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { append(" · "); append(it) }
                    }
                }
                result.getByteArray("output")
                    ?: error("RAPP runtime succeeded without output")
            }

        RiftAppExecutionKind.QUICKJS ->
            providers.execute(kind, runtime, envelope, OUTPUT_BYTES) {
                quickJs.execute(runtime, envelope, OUTPUT_BYTES)
            }

        else -> error("Unsupported RAPP execution kind")
    }
}
