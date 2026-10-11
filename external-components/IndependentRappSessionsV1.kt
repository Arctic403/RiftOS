package com.riftos.external.core

import android.content.Context
import android.os.Process
import android.util.AtomicFile
import android.util.Base64
import com.riftos.app.RiftHostQuickJsV1
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Independently updateable Core owns sessions, input, state and frames.
 * The stable APK Host owns only a zero-authority, bounded QuickJS primitive.
 * No APK-owned Core adapter, lifecycle, session or capability class is called.
 *
 * This is a trusted same-UID host service, not a sandbox. External Core
 * promotion remains blocked until independent capability/grant behavior and
 * real signed-host execution are physically qualified.
 */
internal class IndependentRuntimeRegistry {
    private companion object {
        private const val HOST_QUICKJS_ABI = 1
    }

    fun execute(runtime: ByteArray, input: ByteArray, outputLimit: Int): ByteArray {
        check(RiftHostQuickJsV1.abiVersion() == HOST_QUICKJS_ABI) {
            "Unsupported APK host QuickJS execution ABI"
        }
        return RiftHostQuickJsV1.execute(runtime, input, outputLimit)
    }

    fun state(): JSONObject = JSONObject()
        .put("schema", "riftos.external.runtime-candidates/1")
        .put("registered", 1)
        .put("engine", "apk-host-quickjs-v1")
        .put("abi", HOST_QUICKJS_ABI)
        .put("runtimeExecutionReady", true)
        .put("coreOwnsSessionsAndCapabilities", true)
        .put("apkOwnedCoreExecutorFallbackEnabled", false)
        .put("deviceQualificationComplete", false)
}

internal class IndependentRappSessions(
    private val context: Context,
    private val registry: IndependentRuntimeRegistry,
    private val checkedPackage: (String) -> JSONObject,
    private val programRoot: File,
    private val effects: IndependentRappEffects
) {
    companion object {
        private const val MAX_APPS = 32
        private const val MAX_QUEUE = 64
        private const val MAX_RUNTIME = 8 * 1024 * 1024
        private const val MAX_PROGRAM = 1024 * 1024
        private const val MAX_FRAME_NODES = 256
        private const val MAX_FRAME_TEXT = 16 * 1024
        private const val MAX_OUTPUT = 512 * 1024
        private const val MAX_INPUT = 512 * 1024
        private const val MAX_DEPTH = 16
        private const val EVAL_TIMEOUT_MS = 6500L
        private const val EVENT_SCHEMA = "riftos-app-event-json/1"
        private const val OUTPUT_SCHEMA = "riftos-app-output-json/1"
        private val APP_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$")
    }

    internal data class Ticket(val kind: Int, val targetId: Int,
        val args: IntArray, val text: String, val bytes: ByteArray,
        val depth: Int = 0)

    internal class Session(
        val id: String,
        val name: String,
        val generation: Long,
        val vm: IndependentRuntimeRegistry,
        val runtime: ByteArray,
        val adapter: RiftAppRuntimeAdapter,
        val permissions: Set<String>,
        initialState: ByteArray
    ) {
        var state: ByteArray = initialState.copyOf()
        var frame: JSONObject? = null
        var revision = 0L
        var sequence = 0
        var closed = false
        var running = false
        var error: String? = null
        val queue = ArrayDeque<Ticket>()
    }

    private val monitor = Any()
    private val sessions = LinkedHashMap<String, Session>()
    private val serial = Executors.newSingleThreadExecutor { job ->
        Thread(job, "rift-external-core-rapp-queue").apply { isDaemon = true }
    }
    private var nextGeneration = 1L
    private var focused: String? = null

    fun open(id: String): JSONObject {
        require(APP_ID.matches(id)) { "Invalid Core RAPP id" }
        val metadata = checkedPackage(id)
        val vm = registry // APK QuickJS only; native-buffer requires separate host ABI.
        val adapter = RiftAppAdapters.require(metadata.getString("adapter"))
        require(metadata.getString("abi") == RiftAppAbi.SCHEMA &&
            adapter.presentation == metadata.getString("presentation")) {
            "Installed RAPP ABI/adapter/presentation mismatch"
        }
        require(adapter.executorKind == RiftAppExecutionKind.QUICKJS) {
            "Native-buffer provider execution needs a qualified external Core host ABI"
        }
        val permissionsJson = metadata.optJSONArray("permissions") ?: JSONArray()
        val permissions = (0 until permissionsJson.length())
            .map { permissionsJson.getString(it) }.toSet()
        require(permissions.size == permissionsJson.length() &&
            permissions.all { it in RiftAppAbi.Capability.DECLARABLE }) {
            "Installed RAPP declares invalid or duplicate capabilities"
        }
        val folder = File(programRoot, id)
        require(folder.canonicalFile.parentFile == programRoot.canonicalFile &&
            !Files.isSymbolicLink(folder.toPath())) { "Unsafe RAPP package root" }
        val runtimeFile = File(folder, "runtime.bin")
        val source = runtimeFile.readBytes()
        require(source.size in 1..MAX_RUNTIME) { "RAPP JS source bounds invalid" }
        val rawProgram = File(folder, "state.bin")
            .takeIf { it.isFile && !Files.isSymbolicLink(it.toPath()) }?.readBytes()
            ?: File(folder, "program.bin").readBytes()
        require(rawProgram.size <= MAX_PROGRAM) { "RAPP state oversized" }
        val s: Session
        synchronized(monitor) {
            val old = sessions[id]
            if (old != null && !old.closed) return attached(old)
            require(sessions.size < MAX_APPS && nextGeneration < Long.MAX_VALUE) {
                "Independent Core session limit reached"
            }
            s = Session(id, metadata.getString("name"), nextGeneration++,
                vm, source, adapter, permissions, rawProgram)
            sessions[id] = s
            s.queue.addLast(Ticket(0, 0, intArrayOf(0, 0, 0, 0), "", ByteArray(0)))
            schedule(s)
        }
        return attached(s)
    }

    /** Core package replacement/uninstall invalidates the old attached generation. */
    fun invalidateInstalled(id: String): Boolean = synchronized(monitor) {
        val s = sessions.remove(id) ?: return@synchronized false
        s.closed = true
        s.queue.clear()
        if (focused == id) focused = null
        true
    }

    fun reattach(id: String, expected: Long): JSONObject = synchronized(monitor) {
        val s = active(id, expected)
        attached(s)
    }

    fun close(id: String, expected: Long?): JSONObject = synchronized(monitor) {
        val s = sessions[id] ?: error("Independent Core session missing")
        if (expected != null) require(s.generation == expected) { "Stale RAPP stop" }
        s.closed = true
        s.queue.clear()
        sessions.remove(id)
        if (focused == id) focused = null
        JSONObject().put("schema", "riftos.core.apps/1").put("id", id)
            .put("state", "stopped").put("attachmentGeneration", s.generation)
    }

    private fun attached(s: Session): JSONObject = JSONObject()
        .put("schema", "riftos.core.apps/1").put("id", s.id)
        .put("name", s.name)
        .put("state", if (s.error != null) "error"
            else if (s.frame == null) "starting" else "running")
        .put("attachmentGeneration", s.generation).put("attached", true)

    private fun active(id: String, generation: Long): Session {
        require(APP_ID.matches(id) && generation > 0L)
        val s = sessions[id] ?: error("Independent Core RAPP not attached")
        require(!s.closed && s.generation == generation) {
            "RAPP attachment generation stale"
        }
        return s
    }

    fun offer(id: String, generation: Long, payload: JSONObject): JSONObject {
        val kind = payload.getInt("kind")
        val target = payload.getInt("targetId")
        require(kind in 1..10 && target >= 0) {
            "RAPP Shell input event invalid or reserved for Core"
        }
        val args = intArrayOf(payload.getInt("arg0"), payload.getInt("arg1"),
            payload.getInt("arg2"), payload.getInt("arg3"))
        val text = payload.getString("text")
        require(text.toByteArray(Charsets.UTF_8).size <= 65536) {
            "Core RAPP text input too large"
        }
        val s: Session
        synchronized(monitor) {
            s = active(id, generation)
            require(s.queue.size < MAX_QUEUE) { "Core input queue full" }
            // Only interaction events target visible, focused nodes.
            // Resize/resume/pause are host-level events with targetId=0.
            if (kind in 1..7) {
                require(focused == id && target > 0) {
                    "Interactive input requires focused RAPP and positive target"
                }
                val nodes = s.frame?.optJSONArray("nodes") ?: error("RAPP has no frame")
                var exists = false
                for (i in 0 until nodes.length()) {
                    if (nodes.optJSONObject(i)?.optInt("id") == target) {
                        exists = true
                        break
                    }
                }
                require(exists) { "Input target absent from current Core frame" }
            } else {
                require(target == 0) { "Host lifecycle event cannot target an app widget" }
            }
            s.queue.addLast(Ticket(kind, target, args, text, ByteArray(0)))
            schedule(s)
        }
        return JSONObject().put("schema", "riftos.core.event/1")
            .put("accepted", true).put("appId", id)
            .put("attachmentGeneration", generation)
    }

    fun focus(id: String?): JSONObject = synchronized(monitor) {
        if (id != null) {
            require(APP_ID.matches(id) && sessions[id]?.closed == false)
        }
        focused = id
        focusView()
    }

    fun focusView(): JSONObject = synchronized(monitor) {
        JSONObject().put("schema", "riftos.core.input-focus/1")
            .put("owner", "riftos-external-core")
            .put("focusedAppId", focused ?: JSONObject.NULL)
    }

    fun snapshot(id: String): JSONObject = synchronized(monitor) {
        val s = sessions[id]
        if (s == null || s.closed || s.frame == null) {
            return JSONObject().put("schema", "riftos.core.surface-ipc/1")
                .put("appId", id).put("present", false)
        }
        val copy = JSONObject(s.frame!!.toString())
        return JSONObject().put("schema", "riftos.core.surface-ipc/1")
            .put("appId", id).put("corePid", Process.myPid())
            .put("attachmentGeneration", s.generation)
            .put("revision", s.revision)
            .put("layout", copy.optInt("layout", 1))
            .put("nodes", copy.getJSONArray("nodes"))
            .put("present", true)
    }

    fun runningApps(): JSONArray = synchronized(monitor) {
        JSONArray().also { out ->
            for (s in sessions.values) {
                out.put(JSONObject().put("schema", "riftos.core.apps/1")
                    .put("id", s.id).put("name", s.name)
                    .put("attachmentGeneration", s.generation)
                    .put("state", if (s.error != null) "error"
                        else if (s.frame != null) "running" else "starting")
                    .put("error", s.error ?: JSONObject.NULL)
                    .put("attached", !s.closed))
            }
        }
    }

    fun sessionsView(): JSONObject = synchronized(monitor) {
        JSONObject().put("schema", "riftos.core.sessions/1")
            .put("owner", "riftos-external-core")
            .put("count", sessions.size)
            .put("attached", sessions.size)
            .put("pendingEvents", sessions.values.sumOf { it.queue.size })
            .put("headlessExecution", true)
            .put("runtimeExecutionReady", sessions.isNotEmpty())
    }

    fun surfacesView(): JSONObject = synchronized(monitor) {
        val frames = JSONArray()
        for (s in sessions.values) {
            if (s.frame != null) frames.put(JSONObject()
                .put("appId", s.id).put("attachmentGeneration", s.generation)
                .put("revision", s.revision)
                .put("nodeCount", s.frame!!.getJSONArray("nodes").length()))
        }
        JSONObject().put("schema", "riftos.core.app-surfaces/1")
            .put("count", frames.length()).put("surfaces", frames)
    }

    private fun schedule(s: Session) {
        if (s.running || s.closed || s.queue.isEmpty()) return
        s.running = true
        serial.execute { drain(s) }
    }

    private fun drain(s: Session) {
        while (true) {
            val next = synchronized(monitor) {
                if (s.closed || s.queue.isEmpty()) {
                    s.running = false
                    return
                }
                s.queue.removeFirst()
            }
            try {
                dispatch(s, next)
            } catch (failure: Exception) {
                synchronized(monitor) {
                    s.error = failure.javaClass.simpleName + ": " +
                        failure.message.orEmpty().take(120)
                    s.queue.clear()
                    s.running = false
                }
                return
            }
        }
    }

    private fun dispatch(s: Session, ticket: Ticket) {
        val state: ByteArray
        val seq: Int
        synchronized(monitor) {
            if (s.closed) return
            require(s.sequence < Int.MAX_VALUE) { "RAPP Core sequence exhausted" }
            seq = ++s.sequence
            state = s.state.copyOf()
        }
        val payload = RiftAppAbi.RuntimePayload(
            id = s.id, name = s.name, abi = RiftAppAbi.SCHEMA,
            adapter = s.adapter.id, presentation = s.adapter.presentation,
            permissions = s.permissions, program = state, runtime = s.runtime)
        val event = RiftAppAbi.Event(
            kind = ticket.kind, targetId = ticket.targetId,
            arg0 = ticket.args[0], arg1 = ticket.args[1],
            arg2 = ticket.args[2], arg3 = ticket.args[3],
            text = ticket.text, bytes = ticket.bytes)
        require(s.adapter.supportsEventKind(event.kind)) {
            "RAPP adapter rejects unsupported input event"
        }
        val envelope = s.adapter.encodeEvent(payload, event, seq)
        require(envelope.size <= MAX_INPUT) { "RAPP event envelope oversized" }
        val runner = Executors.newSingleThreadExecutor { task ->
            Thread(task, "rift-external-js-event").apply { isDaemon = true }
        }
        val raw = try {
            val future = runner.submit<ByteArray> {
                s.vm.execute(s.runtime, envelope, MAX_OUTPUT)
            }
            try {
                future.get(EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (failure: java.util.concurrent.TimeoutException) {
                future.cancel(true)
                throw IllegalStateException("Independent Javascript event timed out")
            }
        } finally { runner.shutdownNow() }
        require(raw.size in 1..MAX_OUTPUT) { "RAPP output oversized" }
        val decoded = s.adapter.decodeOutput(raw)
        val nodes = JSONArray()
        for (node in decoded.frame.nodes) {
            nodes.put(JSONObject().put("kind", node.kind).put("id", node.id)
                .put("parentId", node.parentId).put("x", node.x).put("y", node.y)
                .put("width", node.width).put("height", node.height)
                .put("z", node.z).put("flags", node.flags).put("text", node.text))
        }
        val frame = validateFrame(JSONObject()
            .put("layout", decoded.frame.layout).put("nodes", nodes))
        val nextState = decoded.nextProgram
        require(nextState == null || nextState.size <= MAX_PROGRAM) {
            "Next RAPP state exceeds bound"
        }
        synchronized(monitor) {
            if (s.closed) return
            // Commit before publishing frame, never expose uncommitted state.
            if (nextState != null) {
                persist(s.id, nextState)
                s.state = nextState.copyOf()
            }
            s.frame = frame
            s.revision++
            s.error = null
        }
        // Core validates package identity and prior user grants. A shell view
        // cannot authorize effects, or inject internal effect-result events.
        val effect = decoded.effects.singleOrNull() ?: return
        require(ticket.depth < MAX_DEPTH) { "RAPP effect continuation depth exceeded" }
        val reply = effects.execute(s.id, s.permissions, effect)
        synchronized(monitor) {
            if (!s.closed && s.queue.size < MAX_QUEUE) {
                s.queue.addFirst(Ticket(13, effect.requestId,
                    intArrayOf(if (reply.ok) 1 else 0, reply.token, 0, 0),
                    if (reply.ok) reply.text else reply.error,
                    reply.bytes.copyOf(), ticket.depth + 1))
            }
        }
    }

    private fun validateFrame(frame: JSONObject): JSONObject {
        val nodes = frame.getJSONArray("nodes")
        require(nodes.length() in 1..MAX_FRAME_NODES) {
            "RAPP frame node count invalid"
        }
        val seen = HashSet<Int>()
        val bounded = JSONArray()
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            val id = node.getInt("id")
            val kind = node.getInt("kind")
            val parent = node.optInt("parentId", 0)
            val value = node.optString("text")
            require(id > 0 && seen.add(id) && kind in 1..12 &&
                parent >= 0 && parent != id &&
                value.toByteArray(Charsets.UTF_8).size <= MAX_FRAME_TEXT) {
                "RAPP frame node invalid"
            }
            bounded.put(JSONObject(node.toString()))
        }
        return JSONObject().put("layout", frame.optInt("layout", 1))
            .put("nodes", bounded)
    }

    private fun persist(id: String, bytes: ByteArray) {
        require(APP_ID.matches(id) && bytes.size <= MAX_PROGRAM)
        val folder = File(programRoot, id)
        require(folder.canonicalFile.parentFile == programRoot.canonicalFile &&
            !Files.isSymbolicLink(folder.toPath())) {
            "RAPP state root escaped"
        }
        val file = File(folder, "state.bin")
        require(!Files.isSymbolicLink(file.toPath()) &&
            !Files.isSymbolicLink(File(file.path + ".bak").toPath())) {
            "RAPP state path symlinked"
        }
        val atomic = AtomicFile(file)
        val target = atomic.startWrite()
        try {
            target.write(bytes)
            atomic.finishWrite(target)
        } catch (failure: Throwable) {
            atomic.failWrite(target)
            throw failure
        }
    }
}
