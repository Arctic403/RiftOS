package com.riftos.app

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local authority lease shared by single RiftOS Local Agent calls and direct Local Agent
 * batches. A batch may reserve the authority only while no standalone Local Agent call is active.
 * Standalone calls fail closed while a batch owns the lease; batch steps must present that owner ID.
 */
internal object RiftLocalAgentExecutionGate {
    private val lock = Any()
    private var batchOwnerId: String? = null
    private var standaloneCalls = 0

    fun tryReserveBatch(ownerId: String): Boolean = synchronized(lock) {
        require(ownerId.isNotBlank()) { "Local Agent batch authority owner is required" }
        if (batchOwnerId != null || standaloneCalls != 0) {
            false
        } else {
            batchOwnerId = ownerId
            true
        }
    }

    fun releaseBatch(ownerId: String) = synchronized(lock) {
        if (batchOwnerId == ownerId) batchOwnerId = null
    }

    fun <T> withAccess(batchOwner: String? = null, block: () -> T): T {
        if (batchOwner != null) {
            synchronized(lock) {
                require(batchOwnerId == batchOwner) {
                    "Local Agent batch authority lease is not owned by $batchOwner"
                }
            }
            return block()
        }

        synchronized(lock) {
            require(batchOwnerId == null) {
                "RiftOS Local Agent authority is reserved by active batch $batchOwnerId"
            }
            standaloneCalls++
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                standaloneCalls = (standaloneCalls - 1).coerceAtLeast(0)
            }
        }
    }
}

/** Bounded persistent job executor owned directly by the RiftOS Local Agent. */
object RiftLocalAgentBatch {
    private const val SCHEMA = "rift.local-agent-batch/1"
    private const val MAX_STEPS = 16
    private const val MAX_PLAN_BYTES = 512 * 1024
    private const val MAX_STEP_BYTES = 256 * 1024
    private const val MAX_STEP_ID_CHARS = 64
    private const val MAX_REQUEST_ID_CHARS = 128
    private const val MAX_RETAINED_JOBS = 16
    private const val MAX_RETAINED_STEP_RESULT_BYTES = 96 * 1024
    private const val MAX_PERSISTED_STORE_BYTES = 32 * 1024 * 1024
    private const val RESULT_PAGE_MAX = 4
    private const val TERMINAL_RETENTION_MS = 24 * 60 * 60 * 1000L

    private val allowedOps = setOf(
        "status", "open", "tree", "click", "tap", "swipe", "type", "back",
        "type-focused", "keyboard", "browser-inspect", "devlab"
    )
    private val terminalStates = setOf(
        "completed", "completed_with_failures", "failed", "failed_may_have_applied", "cancelled",
        "cancelled_may_have_applied", "interrupted_on_restart"
    )
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rift-local-agent-batch").apply { isDaemon = true }
    }
    private val activeJobId = AtomicReference<String?>(null)
    private val jobs = LinkedHashMap<String, BatchJob>()
    private var loaded = false

    private data class BatchJob(
        val id: String,
        val requestId: String,
        val failurePolicy: String,
        val planSha256: String?,
        val steps: JSONArray,
        val stepManifest: JSONArray,
        val stepCount: Int,
        val createdAtMs: Long,
        var updatedAtMs: Long,
        var status: String,
        var currentStep: Int = -1,
        var executedSteps: Int = 0,
        var failedSteps: Int = 0,
        var cancelRequested: Boolean = false,
        var error: String? = null,
        val results: JSONArray = JSONArray(),
        var future: Future<*>? = null
    )

    fun execute(context: Context, args: JSONObject): JSONObject {
        val appContext = context.applicationContext
        ensureLoaded(appContext)
        return when (args.optString("action").trim().lowercase()) {
            "submit" -> submit(appContext, args)
            "status" -> status(args)
            "result" -> result(args)
            "cancel" -> cancel(appContext, args)
            "list" -> list(appContext)
            else -> throw IllegalArgumentException(
                "rift_local_agent_batch action must be submit, status, result, cancel, or list"
            )
        }
    }

    private fun submit(context: Context, args: JSONObject): JSONObject {
        val requestId = args.optString("requestId").trim()
        require(requestId.isNotEmpty()) { "Local Agent batch submit requires requestId" }
        require(requestId.length <= MAX_REQUEST_ID_CHARS) {
            "Local Agent batch requestId exceeds $MAX_REQUEST_ID_CHARS characters"
        }
        require(requestId.matches(Regex("[A-Za-z0-9._:-]+"))) {
            "Local Agent batch requestId contains unsupported characters"
        }
        val failurePolicy = args.optString("failurePolicy", "stop").trim().lowercase()
        require(failurePolicy == "stop" || failurePolicy == "continue") {
            "Local Agent batch failurePolicy must be stop or continue"
        }
        val rawSteps = args.optJSONArray("steps")
            ?: throw IllegalArgumentException("Local Agent batch submit requires steps[]")
        val normalizedSteps = validatePlan(rawSteps)
        val planSha256 = planDigest(failurePolicy, normalizedSteps)

        synchronized(lock) {
            jobs.values.firstOrNull { it.requestId == requestId }?.let { existing ->
                require(existing.planSha256 != null) {
                    "Local Agent batch requestId $requestId is already bound to an older job whose plan identity cannot be verified"
                }
                require(existing.planSha256 == planSha256) {
                    "Local Agent batch requestId $requestId is already bound to a different plan"
                }
                return snapshot(existing, false).put("deduplicated", true)
            }
            val active = activeJobId.get()
            require(active == null) {
                "Local Agent batch $active is still active; inspect or cancel it before submitting another"
            }
            pruneLocked(makeRoom = true)
            require(jobs.size < MAX_RETAINED_JOBS) {
                "Local Agent batch history is full; wait for terminal retention pruning"
            }
            val now = System.currentTimeMillis()
            val jobId = "lab-${UUID.randomUUID()}"
            require(RiftLocalAgentExecutionGate.tryReserveBatch(jobId)) {
                "RiftOS Local Agent authority is busy; retry after the current standalone Local Agent action finishes"
            }
            val job = BatchJob(
                id = jobId,
                requestId = requestId,
                failurePolicy = failurePolicy,
                planSha256 = planSha256,
                steps = normalizedSteps,
                stepManifest = stepManifest(normalizedSteps),
                stepCount = normalizedSteps.length(),
                createdAtMs = now,
                updatedAtMs = now,
                status = "queued"
            )
            try {
                require(activeJobId.compareAndSet(null, job.id)) { "Local Agent batch lane is busy" }
                jobs[job.id] = job
                persistLocked(context)
                job.future = executor.submit { runJob(context, job) }
            } catch (error: Throwable) {
                jobs.remove(job.id)
                activeJobId.compareAndSet(job.id, null)
                RiftLocalAgentExecutionGate.releaseBatch(job.id)
                throw error
            }
            return snapshot(job, false).put("deduplicated", false)
        }
    }

    private fun validatePlan(rawSteps: JSONArray): JSONArray {
        require(rawSteps.length() in 1..MAX_STEPS) {
            "Local Agent batch requires 1..$MAX_STEPS steps"
        }
        require(rawSteps.toString().toByteArray(Charsets.UTF_8).size <= MAX_PLAN_BYTES) {
            "Local Agent batch plan exceeds $MAX_PLAN_BYTES UTF-8 bytes"
        }
        val ids = LinkedHashSet<String>()
        val normalized = JSONArray()
        for (index in 0 until rawSteps.length()) {
            val raw = rawSteps.optJSONObject(index)
                ?: throw IllegalArgumentException("Local Agent batch step $index must be an object")
            val step = JSONObject(raw.toString())
            val id = step.optString("id").trim()
            require(id.isNotEmpty()) { "Local Agent batch step $index requires id" }
            require(id.length <= MAX_STEP_ID_CHARS && id.matches(Regex("[A-Za-z0-9._:-]+"))) {
                "Local Agent batch step $index has an invalid id"
            }
            require(ids.add(id)) { "Local Agent batch step id is duplicated: $id" }
            val op = step.optString("op").trim().lowercase()
            require(op in allowedOps) { "Unsupported Local Agent batch operation: $op" }
            require(op != "batch") { "Nested Local Agent batches are forbidden" }
            step.put("id", id).put("op", op)
            validateStep(index, step)
            require(step.toString().toByteArray(Charsets.UTF_8).size <= MAX_STEP_BYTES) {
                "Local Agent batch step $id exceeds $MAX_STEP_BYTES UTF-8 bytes"
            }
            normalized.put(step)
        }
        return normalized
    }

    private fun stepManifest(steps: JSONArray): JSONArray = JSONArray().also { manifest ->
        for (index in 0 until steps.length()) {
            val step = steps.getJSONObject(index)
            manifest.put(JSONObject().put("id", step.getString("id")).put("op", step.getString("op")))
        }
    }

    private fun validateStep(index: Int, step: JSONObject) {
        fun requireFinite(name: String) {
            require(step.has(name)) { "Local Agent batch step $index requires $name" }
            require(step.optDouble(name, Double.NaN).isFinite()) {
                "Local Agent batch step $index $name must be finite"
            }
        }
        when (val op = step.getString("op")) {
            "status", "open", "back" -> Unit
            "tree" -> require(step.optInt("limit", 256) in 1..1024) {
                "Local Agent batch tree limit must be between 1 and 1024"
            }
            "click" -> requireBoundedText(step, "target", 256)
            "tap" -> {
                requireFinite("x")
                requireFinite("y")
            }
            "swipe" -> {
                listOf("x1", "y1", "x2", "y2").forEach(::requireFinite)
                require(step.optLong("durationMs", 350L) in 50L..3_000L) {
                    "Local Agent batch swipe durationMs must be between 50 and 3000"
                }
            }
            "type" -> {
                requireBoundedText(step, "target", 256)
                requireBoundedText(step, "text", 4096, true)
            }
            "type-focused" -> requireBoundedText(step, "text", 4096, true)
            "keyboard" -> {
                val action = step.optString("action").trim().lowercase()
                require(action == "status" || action == "key") {
                    "Local Agent batch keyboard action must be status or key"
                }
                if (action == "key") requireBoundedText(step, "target", 24)
            }
            "browser-inspect" -> require(step.optString("action").isNotBlank()) {
                "Local Agent batch browser-inspect action is required"
            }
            "devlab" -> require(step.optJSONObject("request") != null) {
                "Local Agent batch devlab request object is required"
            }
            else -> throw IllegalArgumentException("Unsupported Local Agent batch operation: $op")
        }
    }

    private fun requireBoundedText(
        value: JSONObject,
        key: String,
        maxChars: Int,
        allowEmpty: Boolean = false
    ) {
        require(value.has(key) && value.opt(key) is String) {
            "Local Agent batch $key must be a string"
        }
        val text = value.optString(key)
        require((allowEmpty || text.isNotEmpty()) && text.length <= maxChars) {
            "Local Agent batch $key must contain ${if (allowEmpty) "0" else "1"}..$maxChars characters"
        }
    }

    private fun runJob(context: Context, job: BatchJob) {
        try {
            runJobInternal(context, job)
        } catch (error: Throwable) {
            synchronized(lock) {
                if (job.status !in terminalStates) {
                    val cancelled = job.cancelRequested || Thread.currentThread().isInterrupted
                    job.status = when {
                        cancelled && job.executedSteps > 0 -> "cancelled_may_have_applied"
                        cancelled -> "cancelled"
                        job.executedSteps > 0 -> "failed_may_have_applied"
                        else -> "failed"
                    }
                    job.error = error.message ?: error.javaClass.simpleName
                    job.updatedAtMs = System.currentTimeMillis()
                }
                activeJobId.compareAndSet(job.id, null)
                RiftLocalAgentExecutionGate.releaseBatch(job.id)
                runCatching { persistLocked(context) }
            }
        }
    }

    private fun runJobInternal(context: Context, job: BatchJob) {
        synchronized(lock) {
            if (job.cancelRequested || Thread.currentThread().isInterrupted) {
                finishLocked(context, job, "cancelled", "Local Agent batch cancelled before execution")
                return
            }
            job.status = "running"
            job.updatedAtMs = System.currentTimeMillis()
            persistLocked(context)
        }

        for (index in 0 until job.stepCount) {
            if (shouldCancel(job)) {
                synchronized(lock) {
                    finishLocked(
                        context,
                        job,
                        "cancelled",
                        "Local Agent batch cancelled before step ${index + 1}"
                    )
                }
                return
            }
            val step = job.steps.getJSONObject(index)
            val stepId = step.getString("id")
            synchronized(lock) {
                job.currentStep = index
                job.updatedAtMs = System.currentTimeMillis()
                persistLocked(context)
            }
            val startedAt = System.currentTimeMillis()
            val request = JSONObject(step.toString()).apply { remove("id") }
            val result = try {
                val activity = RiftMcpRuntime.activeActivity()
                val executionContext: Context = activity ?: context
                val value = RiftOsLocalAgent.execute(executionContext, request, job.id)
                JSONObject()
                    .put("id", stepId)
                    .put("op", request.getString("op"))
                    .put("ok", true)
                    .put("durationMs", (System.currentTimeMillis() - startedAt).coerceAtLeast(0L))
                    .put("value", value)
            } catch (error: Throwable) {
                JSONObject()
                    .put("id", stepId)
                    .put("op", request.optString("op"))
                    .put("ok", false)
                    .put("durationMs", (System.currentTimeMillis() - startedAt).coerceAtLeast(0L))
                    .put("error", error.message ?: error.javaClass.simpleName)
            }
            synchronized(lock) {
                job.results.put(retainBoundedResult(result))
                job.executedSteps++
                if (!result.optBoolean("ok")) job.failedSteps++
                job.updatedAtMs = System.currentTimeMillis()
                persistLocked(context)
            }

            if (shouldCancel(job)) {
                synchronized(lock) {
                    finishLocked(
                        context,
                        job,
                        "cancelled_may_have_applied",
                        "Cancellation was observed after step $stepId; completed UI actions are not reversible"
                    )
                }
                return
            }
            if (!result.optBoolean("ok") && job.failurePolicy == "stop") {
                synchronized(lock) {
                    finishLocked(
                        context,
                        job,
                        "failed_may_have_applied",
                        result.optString(
                            "error",
                            "Local Agent batch step failed after one or more UI actions may have applied"
                        )
                    )
                }
                return
            }
        }
        synchronized(lock) {
            finishLocked(
                context,
                job,
                if (job.failedSteps == 0) "completed" else "completed_with_failures",
                null
            )
        }
    }

    private fun shouldCancel(job: BatchJob): Boolean =
        job.cancelRequested || Thread.currentThread().isInterrupted

    private fun finishLocked(context: Context, job: BatchJob, status: String, error: String?) {
        job.status = status
        job.error = error
        job.updatedAtMs = System.currentTimeMillis()
        activeJobId.compareAndSet(job.id, null)
        RiftLocalAgentExecutionGate.releaseBatch(job.id)
        persistLocked(context)
    }

    private fun retainBoundedResult(result: JSONObject): JSONObject {
        val serialized = result.toString()
        val bytes = serialized.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_RETAINED_STEP_RESULT_BYTES) return JSONObject(serialized)
        return JSONObject()
            .put("id", result.optString("id"))
            .put("op", result.optString("op"))
            .put("ok", result.optBoolean("ok"))
            .put("durationMs", result.optLong("durationMs"))
            .put("resultTooLarge", true)
            .put("resultBytes", bytes.size)
            .put("resultSha256", sha256(bytes))
    }

    private fun status(args: JSONObject): JSONObject = synchronized(lock) {
        snapshot(requireJob(args), false)
    }

    private fun result(args: JSONObject): JSONObject = synchronized(lock) {
        val job = requireJob(args)
        val offset = args.optInt("offset", 0)
        val limit = args.optInt("limit", RESULT_PAGE_MAX)
        require(offset >= 0) { "Local Agent batch result offset must be non-negative" }
        require(limit in 1..RESULT_PAGE_MAX) {
            "Local Agent batch result limit must be between 1 and $RESULT_PAGE_MAX"
        }
        val page = JSONArray()
        val end = (offset + limit).coerceAtMost(job.results.length())
        for (index in offset until end) {
            page.put(JSONObject(job.results.getJSONObject(index).toString()))
        }
        snapshot(job, false)
            .put("results", page)
            .put("offset", offset)
            .put("nextOffset", if (end < job.results.length()) end else JSONObject.NULL)
            .put("availableResults", job.results.length())
    }

    private fun cancel(context: Context, args: JSONObject): JSONObject = synchronized(lock) {
        val job = requireJob(args)
        if (job.status in terminalStates) return snapshot(job, false)
        job.cancelRequested = true
        val wasQueued = job.status == "queued"
        val cancelled = job.future?.cancel(true) == true
        job.status = if (wasQueued && cancelled) "cancelled" else "cancelling"
        if (job.status == "cancelled") {
            job.error = "Local Agent batch cancelled before execution"
            activeJobId.compareAndSet(job.id, null)
            RiftLocalAgentExecutionGate.releaseBatch(job.id)
        }
        job.updatedAtMs = System.currentTimeMillis()
        persistLocked(context)
        snapshot(job, false)
    }

    private fun list(context: Context): JSONObject = synchronized(lock) {
        if (pruneLocked()) persistLocked(context)
        val rows = JSONArray()
        jobs.values.sortedByDescending { it.createdAtMs }.forEach {
            rows.put(snapshot(it, false))
        }
        JSONObject()
            .put("schema", SCHEMA)
            .put("activeJobId", activeJobId.get() ?: JSONObject.NULL)
            .put("jobs", rows)
    }

    private fun requireJob(args: JSONObject): BatchJob {
        val jobId = args.optString("jobId").trim()
        require(jobId.isNotEmpty()) { "Local Agent batch control action requires jobId" }
        return jobs[jobId]
            ?: throw IllegalArgumentException("Unknown Local Agent batch job: $jobId")
    }

    private fun snapshot(job: BatchJob, includeResults: Boolean): JSONObject =
        JSONObject()
            .put("schema", SCHEMA)
            .put("jobId", job.id)
            .put("requestId", job.requestId)
            .put("failurePolicy", job.failurePolicy)
            .put("planSha256", job.planSha256 ?: JSONObject.NULL)
            .put("status", job.status)
            .put("terminal", job.status in terminalStates)
            .put("jobOk", when (job.status) {
                "completed" -> true
                "completed_with_failures", "failed", "failed_may_have_applied", "cancelled",
                "cancelled_may_have_applied", "interrupted_on_restart" -> false
                else -> JSONObject.NULL
            })
            .put("stepCount", job.stepCount)
            .put("stepManifest", JSONArray(job.stepManifest.toString()))
            .put("currentStep", job.currentStep)
            .put("executedSteps", job.executedSteps)
            .put("failedSteps", job.failedSteps)
            .put("cancelRequested", job.cancelRequested)
            .put("createdAtMs", job.createdAtMs)
            .put("updatedAtMs", job.updatedAtMs)
            .put("error", job.error ?: JSONObject.NULL)
            .also {
                if (includeResults) it.put("results", JSONArray(job.results.toString()))
            }

    private fun ensureLoaded(context: Context) = synchronized(lock) {
        if (loaded) return
        val file = store(context)
        if (!file.baseFile.isFile) {
            loaded = true
            return
        }
        require(file.baseFile.length() <= MAX_PERSISTED_STORE_BYTES) {
            "Local Agent batch store exceeds $MAX_PERSISTED_STORE_BYTES bytes"
        }
        val root = JSONObject(String(file.readFully(), Charsets.UTF_8))
        require(root.optString("schema") == SCHEMA) {
            "Unsupported Local Agent batch store schema"
        }
        val stored = root.optJSONArray("jobs") ?: JSONArray()
        require(stored.length() <= MAX_RETAINED_JOBS) {
            "Local Agent batch store contains too many jobs"
        }
        val recoveredJobs = LinkedHashMap<String, BatchJob>()
        val recoveredRequestIds = LinkedHashSet<String>()
        val nonTerminalStates = setOf("queued", "running", "cancelling")
        for (index in 0 until stored.length()) {
            val row = stored.optJSONObject(index)
                ?: throw IllegalArgumentException("Local Agent batch store job $index must be an object")
            val id = row.optString("jobId").trim()
            val requestId = row.optString("requestId").trim()
            val failurePolicy = row.optString("failurePolicy", "stop").trim().lowercase()
            val status = row.optString("status").trim().lowercase()
            val planSha256 = row.optString("planSha256").trim().takeIf { it.isNotEmpty() }
            val stepManifest = JSONArray(row.optJSONArray("stepManifest")?.toString() ?: "[]")
            val stepCount = row.optInt("stepCount", -1)
            val currentStep = row.optInt("currentStep", -1)
            val executedSteps = row.optInt("executedSteps", -1)
            val failedSteps = row.optInt("failedSteps", -1)
            val results = JSONArray((row.optJSONArray("results") ?: JSONArray()).toString())

            require(id.startsWith("lab-") && id.length <= 64) {
                "Local Agent batch store job $index has an invalid jobId"
            }
            require(requestId.isNotEmpty() &&
                requestId.length <= MAX_REQUEST_ID_CHARS &&
                requestId.matches(Regex("[A-Za-z0-9._:-]+"))
            ) {
                "Local Agent batch store job $index has an invalid requestId"
            }
            require(recoveredRequestIds.add(requestId)) {
                "Local Agent batch store contains duplicate requestId: $requestId"
            }
            require(failurePolicy == "stop" || failurePolicy == "continue") {
                "Local Agent batch store job $index has an invalid failurePolicy"
            }
            require(status in terminalStates || status in nonTerminalStates) {
                "Local Agent batch store job $index has an invalid status"
            }
            require(planSha256 == null || planSha256.matches(Regex("[0-9a-f]{64}"))) {
                "Local Agent batch store job $index has an invalid planSha256"
            }
            require(stepCount in 1..MAX_STEPS && stepManifest.length() == stepCount) {
                "Local Agent batch store job $index has an invalid step manifest"
            }
            require(currentStep in -1 until stepCount) {
                "Local Agent batch store job $index has an invalid currentStep"
            }
            require(executedSteps in 0..stepCount && failedSteps in 0..executedSteps) {
                "Local Agent batch store job $index has invalid execution counters"
            }
            require(results.length() <= executedSteps && results.length() <= stepCount) {
                "Local Agent batch store job $index has too many retained results"
            }

            val recoveredStatus =
                if (status in terminalStates) status else "interrupted_on_restart"
            val job = BatchJob(
                id = id,
                requestId = requestId,
                failurePolicy = failurePolicy,
                planSha256 = planSha256,
                steps = JSONArray(),
                stepManifest = stepManifest,
                stepCount = stepCount,
                createdAtMs = row.optLong("createdAtMs"),
                updatedAtMs = System.currentTimeMillis(),
                status = recoveredStatus,
                currentStep = currentStep,
                executedSteps = executedSteps,
                failedSteps = failedSteps,
                cancelRequested = row.optBoolean("cancelRequested"),
                error = if (recoveredStatus == "interrupted_on_restart") {
                    "Unfinished Local Agent batch was not replayed after process restart"
                } else {
                    row.optString("error").takeIf { it.isNotBlank() }
                },
                results = results
            )
            require(recoveredJobs.put(id, job) == null) {
                "Local Agent batch store contains duplicate jobId: $id"
            }
        }
        jobs.clear()
        jobs.putAll(recoveredJobs)
        pruneLocked()
        persistLocked(context)
        loaded = true
    }

    private fun persistLocked(context: Context) {
        val rows = JSONArray()
        jobs.values.forEach { rows.put(snapshot(it, true)) }
        val bytes = JSONObject().put("schema", SCHEMA).put("jobs", rows)
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PERSISTED_STORE_BYTES) {
            "Local Agent batch store exceeds $MAX_PERSISTED_STORE_BYTES bytes"
        }
        val file = store(context)
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }

    private fun pruneLocked(makeRoom: Boolean = false): Boolean {
        var changed = false
        val now = System.currentTimeMillis()
        val iterator = jobs.entries.iterator()
        while (iterator.hasNext()) {
            val job = iterator.next().value
            if (job.status in terminalStates &&
                now - job.updatedAtMs >= TERMINAL_RETENTION_MS
            ) {
                iterator.remove()
                changed = true
            }
        }
        while (jobs.size > MAX_RETAINED_JOBS || (makeRoom && jobs.size >= MAX_RETAINED_JOBS)) {
            val removable =
                jobs.entries.firstOrNull { it.value.status in terminalStates } ?: break
            jobs.remove(removable.key)
            changed = true
        }
        return changed
    }

    private fun store(context: Context): AtomicFile {
        val directory = File(context.filesDir, "rift-local-agent-batch").apply { mkdirs() }
        return AtomicFile(File(directory, "jobs-v1.json"))
    }

    private fun planDigest(failurePolicy: String, steps: JSONArray): String {
        val payload = JSONObject()
            .put("failurePolicy", failurePolicy)
            .put("steps", JSONArray(steps.toString()))
            .toString()
            .toByteArray(Charsets.UTF_8)
        return sha256(payload)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
