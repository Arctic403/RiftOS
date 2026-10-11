package com.riftos.external.core

import android.content.Context
import android.os.Process
import android.util.AtomicFile
import android.util.Base64
import dalvik.system.DexClassLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Owns Core RAPP state, serialized event dispatch, immutable graphical frames,
 * focus and state durability IN THE EXTERNAL DEX.
 *
 * Javascript execution is a separate content-addressed, independently loaded
 * VM implementing IndependentJavascriptVmV1. No APK Core executor, session,
 * adapter, capability broker or execution class is imported or called.
 *
 * A missing or unverified VM fails closed before creating a session. No
 * executable Core candidate may be promoted until an actual VM + capability
 * provider has passed full RAPP and installed-device behavior tests.
 */
interface IndependentJavascriptVmV1 {
    fun execute(runtime: ByteArray, input: ByteArray, outputLimit: Int): ByteArray
}

/** SHA-sealed, explicitly opted-in external Javascript runtime definition. */
internal class IndependentRuntimeRegistry(private val app: Context) {
    companion object {
        private const val MAX_DEX_BYTES = 24L * 1024L * 1024L
        private val SHA = Regex("^[a-f0-9]{64}$")
        private const val SCHEMA = "riftos.external.javascript-runtime/1"
        // An untrusted manifest cannot qualify itself. A tested runtime SHA
        // must be pinned in the separately updatable Core DEX before linking.
        // No VM is approved in Core v0.2; deliberate fail-closed default.
        private const val APPROVED_VM_SHA = ""
    }

    @Volatile private var engine: IndependentJavascriptVmV1? = null
    @Volatile private var receipt = "not-registered"

    fun current(): IndependentJavascriptVmV1 {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            // Separate opt-in runtime, NOT generic providers returned by the
            // APK-owned RiftCoreRuntime.runtimes() singleton.
            val base = File(app.filesDir, "riftfs/system/external-runtimes")
            require(base.isDirectory && !Files.isSymbolicLink(base.toPath())) {
                "Independent Javascript runtime registry not installed"
            }
            val meta = File(base, "javascript.json")
            require(meta.isFile && !Files.isSymbolicLink(meta.toPath()) &&
                meta.length() in 1L..4096L) {
                "Independent Javascript runtime receipt missing"
            }
            val manifest = JSONObject(meta.readText(Charsets.UTF_8))
            val names = mutableSetOf<String>()
            val keys = manifest.keys()
            while (keys.hasNext()) names.add(keys.next())
            require(names == setOf("schema", "sha256", "entrypoint", "abi", "payload",
                "independentExecutionProven")) {
                "Independent Javascript registry has unexpected fields"
            }
            require(manifest.getString("schema") == SCHEMA &&
                manifest.getInt("abi") == 1 &&
                manifest.getBoolean("independentExecutionProven")) {
                "Independent Javascript VM not qualified"
            }
            val sha = manifest.getString("sha256")
            val entry = manifest.getString("entrypoint")
            require(sha.matches(SHA) && APPROVED_VM_SHA.matches(SHA) &&
                sha == APPROVED_VM_SHA &&
                entry.matches(Regex("^com\\.riftos\\.external\\.core\\.vm\\.[A-Za-z][A-Za-z0-9_]*$")) &&
                manifest.getString("payload") == "javascript.dex") {
                "Independent Javascript VM provenance invalid"
            }
            val dex = File(base, "javascript.dex")
            require(dex.isFile && !Files.isSymbolicLink(dex.toPath()) &&
                !dex.canWrite() && dex.length() in 112L..MAX_DEX_BYTES) {
                "Javascript VM DEX not sealed"
            }
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(dex.readBytes()).joinToString("") {
                    "%02x".format(it.toInt() and 255)
                }
            require(actual == sha) { "Javascript VM changed after qualification" }
            val loader = DexClassLoader(dex.absolutePath,
                app.codeCacheDir.absolutePath, null, javaClass.classLoader)
            val type = loader.loadClass(entry)
            require(type.classLoader === loader &&
                IndependentJavascriptVmV1::class.java.isAssignableFrom(type)) {
                "Javascript VM resolves outside independent Core ABI"
            }
            val ready = type.getDeclaredConstructor().newInstance() as IndependentJavascriptVmV1
            engine = ready
            receipt = sha
            return ready
        }
    }

    fun state(): JSONObject = JSONObject()
        .put("schema", "riftos.external.runtime-candidates/1")
        .put("registered", if (engine == null) 0 else 1)
        .put("runtimeSha256", if (engine == null) JSONObject.NULL else receipt)
        .put("runtimeExecutionReady", engine != null)
        .put("apkOwnedExecutorFallbackEnabled", false)
}

internal class IndependentRappSessions(
    private val context: Context,
    private val registry: IndependentRuntimeRegistry,
    private val checkedPackage: (String) -> JSONObject,
    private val programRoot: File
) {
    companion object {
        private const val MAX_APPS = 32
        private const val MAX_QUEUE = 64
        private const val MAX_RUNTIME = 8 * 1024 * 1024
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
        val vm: IndependentJavascriptVmV1,
        val runtime: ByteArray,
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
        val vm = registry.current() // fail closed BEFORE attaching an app
        val folder = File(programRoot, id)
        require(folder.canonicalFile.parentFile == programRoot.canonicalFile &&
            !Files.isSymbolicLink(folder.toPath())) { "Unsafe RAPP package root" }
        val runtimeFile = File(folder, "runtime.bin")
        val source = runtimeFile.readBytes()
        require(source.size in 1..MAX_RUNTIME) { "RAPP JS source bounds invalid" }
        val rawProgram = File(folder, "state.bin")
            .takeIf { it.isFile && !Files.isSymbolicLink(it.toPath()) }?.readBytes()
            ?: File(folder, "program.bin").readBytes()
        require(rawProgram.size <= MAX_RUNTIME) { "RAPP state oversized" }
        val s: Session
        synchronized(monitor) {
            val old = sessions[id]
            if (old != null && !old.closed) return attached(old)
            require(sessions.size < MAX_APPS && nextGeneration < Long.MAX_VALUE) {
                "Independent Core session limit reached"
            }
            s = Session(id, metadata.getString("name"), nextGeneration++,
                vm, source, rawProgram)
            sessions[id] = s
            s.queue.addLast(Ticket(0, 0, intArrayOf(0, 0, 0, 0), "", ByteArray(0)))
            schedule(s)
        }
        return attached(s)
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
        require(kind in 0..13 && target >= 0) { "RAPP input event invalid" }
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
            // Input events cannot be delivered to another application's
            // surface, nor to a hidden/nonfocused editable widget.
            require(kind == 0 || kind == 13 || focused == id) {
                "Input requires focused RAPP window"
            }
            if (kind != 0 && kind != 13) {
                val nodes = s.frame?.optJSONArray("nodes") ?: error("RAPP has no frame")
                var exists = false
                for (i in 0 until nodes.length()) {
                    if (nodes.optJSONObject(i)?.optInt("id") == target) {
                        exists = true
                        break
                    }
                }
                require(exists) { "Input target absent from current Core frame" }
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
        val envelope = JSONObject()
            .put("schema", EVENT_SCHEMA)
            .put("sequence", seq)
            .put("stateBase64", Base64.encodeToString(state, Base64.NO_WRAP))
            .put("event", JSONObject().put("kind", ticket.kind)
                .put("targetId", ticket.targetId)
                .put("arg0", ticket.args[0]).put("arg1", ticket.args[1])
                .put("arg2", ticket.args[2]).put("arg3", ticket.args[3])
                .put("text", ticket.text)
                .put("bytesBase64", Base64.encodeToString(ticket.bytes, Base64.NO_WRAP)))
            .toString().toByteArray(Charsets.UTF_8)
        require(envelope.size <= MAX_INPUT) { "RAPP event envelope oversized" }
        // VM must itself enforce an execution deadline; the caller also
        // enforces a bounded Java future timeout. This is not a VM sandbox.
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
        val output = JSONObject(String(raw, Charsets.UTF_8))
        require(output.getString("schema") == OUTPUT_SCHEMA) {
            "Javascript output schema mismatch"
        }
        val frame = validateFrame(output.getJSONObject("frame"))
        val nextState = output.optString("stateBase64").takeIf { it.isNotBlank() }
            ?.let { Base64.decode(it, Base64.DEFAULT) }
        if (nextState != null) {
            require(nextState.size <= MAX_RUNTIME) { "Next RAPP state exceeds bound" }
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
        // A Core-owned broker, not a view or APK singleton, must authorize
        // host effects. Until independently approved policies exist, deny
        // each effect explicitly via the normal HOST_EFFECT_RESULT envelope.
        val effect = output.optJSONObject("effect") ?: return
        require(ticket.depth < MAX_DEPTH) { "RAPP effect continuation depth exceeded" }
        val id = effect.getInt("requestId")
        require(id > 0 && effect.getString("capability").length in 1..64) {
            "RAPP effect identity invalid"
        }
        synchronized(monitor) {
            if (!s.closed && s.queue.size < MAX_QUEUE) {
                s.queue.addFirst(Ticket(13, id,
                    intArrayOf(0, 0, 0, 0),
                    "External Core capability not independently authorized",
                    ByteArray(0), ticket.depth + 1))
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
        require(APP_ID.matches(id) && bytes.size <= MAX_RUNTIME)
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
