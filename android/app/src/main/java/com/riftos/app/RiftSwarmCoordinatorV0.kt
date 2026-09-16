package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject

/** Future brain implementations plug into this interface; Rift++ V0 does not invoke one yet. */
interface RiftBrainBackend {
    val id: String
    val type: String
    fun available(): Boolean
    fun respond(request: RiftBrainRequest): RiftBrainResponse
}

data class RiftBrainRequest(
    val taskId: String,
    val role: String,
    val goal: String,
    val context: JSONObject,
    val allowedCapabilities: List<String>
)

data class RiftBrainResponse(
    val backendId: String,
    val ok: Boolean,
    val output: JSONObject,
    val error: String? = null
)

/**
 * Preview-only V0 coordinator. It turns compiled Rift Swarm IR into a deterministic assignment plan
 * but deliberately never calls RiftBrainBackend, Local Agent, shell, Git, build or workspace writes.
 */
object RiftSwarmCoordinatorV0 {
    private const val IR_SCHEMA = "rift.swarm-ir/0"

    fun preview(ir: JSONObject, requestedTask: String? = null): JSONObject {
        require(ir.optString("schema") == IR_SCHEMA) { "Rift swarm coordinator requires $IR_SCHEMA" }
        require(!ir.optBoolean("executable", true)) { "Rift++ V0 IR must remain non-executable" }

        val tasks = ir.getJSONArray("tasks")
        require(tasks.length() > 0) { "Rift++ IR has no tasks" }
        val task = if (requestedTask.isNullOrBlank()) tasks.getJSONObject(0) else {
            findByName(tasks, requestedTask)
                ?: throw IllegalArgumentException("Rift++ task not found: $requestedTask")
        }
        val swarms = ir.getJSONArray("swarms")
        val swarm = findByName(swarms, task.getString("swarm"))
            ?: throw IllegalArgumentException("Rift++ swarm missing from IR: ${task.getString("swarm")}")
        val brains = ir.getJSONArray("brains")
        val agents = ir.getJSONArray("agents")
        val backends = ir.getJSONArray("backends")
        val lead = findByName(brains, swarm.getString("lead"))
            ?: throw IllegalArgumentException("Rift++ lead brain missing from IR")
        val backend = findByName(backends, lead.getString("backend"))
            ?: throw IllegalArgumentException("Rift++ brain backend missing from IR")

        val schedule = swarm.getJSONArray("scheduleOrder")
        val assignments = JSONArray()
        for (i in 0 until schedule.length()) {
            val name = schedule.getString(i)
            if (name == lead.getString("name")) {
                assignments.put(JSONObject()
                    .put("order", i + 1)
                    .put("member", name)
                    .put("kind", "brain")
                    .put("role", lead.getString("role"))
                    .put("backend", backend.getString("name"))
                    .put("backendType", backend.getString("type"))
                    .put("backendConnected", false)
                    .put("allowedCapabilities", JSONArray()))
            } else {
                val agent = findByName(agents, name)
                    ?: throw IllegalArgumentException("Rift++ worker missing from IR: $name")
                assignments.put(JSONObject()
                    .put("order", i + 1)
                    .put("member", name)
                    .put("kind", "agent")
                    .put("role", agent.getString("role"))
                    .put("tools", agent.getJSONArray("tools"))
                    .put("allowedCapabilities", agent.getJSONArray("allow"))
                    .put("deniedCapabilities", agent.getJSONArray("deny")))
            }
        }

        return JSONObject()
            .put("schema", "rift.swarm-preview/0")
            .put("experimental", true)
            .put("execution", false)
            .put("brainBackendInterface", "RiftBrainBackend")
            .put("backendInvoked", false)
            .put("source", ir.optString("source"))
            .put("sourceSha256", ir.optString("sourceSha256"))
            .put("task", task.getString("name"))
            .put("goal", task.getString("goal"))
            .put("swarm", swarm.getString("name"))
            .put("retry", task.getInt("retry"))
            .put("requirements", task.getJSONArray("require"))
            .put("reviewersRequired", swarm.getInt("reviewersRequired"))
            .put("assignments", assignments)
            .put("note", "V0 coordinator preview only. No backend, tool, Local Agent or mutation execution occurs.")
    }

    private fun findByName(array: JSONArray, name: String): JSONObject? {
        for (i in 0 until array.length()) {
            val value = array.optJSONObject(i) ?: continue
            if (value.optString("name") == name) return value
        }
        return null
    }
}
