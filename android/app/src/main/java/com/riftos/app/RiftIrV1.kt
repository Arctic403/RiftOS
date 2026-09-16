package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Rift IR V1 core.
 *
 * This layer is intentionally independent from Android Context and file IO. Frontends lower their
 * validated representation into this contract; coordinators consume the contract later. V1 is
 * inspection-only and cannot invoke backends, tools, Local Agent authority, shell commands or writes.
 */
object RiftIrV1 {
    const val SCHEMA = "rift.ir/1"
    const val VERSION = 1
    const val PROFILE = "swarm-core"
    const val SOURCE_SWARM_SCHEMA = "rift.swarm-ir/0"

    private const val MAX_CONTEXT_TOKENS = 65_536
    private const val MAX_RETRIES = 5
    private const val MAX_REVIEWERS = 8
    private const val MAX_WORKERS = 32

    private val backendTypes = setOf("rules", "riftllm", "remote", "mock")
    private val roles = setOf(
        "lead", "architect", "research", "cpp", "android", "js", "debug", "build",
        "tests", "security", "performance", "docs", "review"
    )
    private val memoryScopes = setOf("none", "session", "project")
    private val requirements = setOf("tests", "security", "review", "performance", "docs", "build")
    private val capabilities = listOf(
        "workspace.read", "workspace.graph", "workspace.diff", "workspace.write",
        "git.status", "git.diff", "git.commit", "git.push",
        "build.plan", "build.run", "tests.run",
        "docs.read", "docs.write", "browser.inspect",
        "device.view", "device.control", "research.web",
        "mcp.read", "mcp.write"
    )
    private val capabilitySet = capabilities.toSet()
    private val mutatingCapabilities = listOf(
        "workspace.write", "git.commit", "git.push", "build.run", "docs.write",
        "device.control", "mcp.write"
    )
    private val mutatingSet = mutatingCapabilities.toSet()
    private val readOnlyRoles = listOf("security", "review")
    private val readOnlyRoleSet = readOnlyRoles.toSet()

    /** Lower the existing validated Rift++ V0 Swarm IR into the language-independent Rift IR V1. */
    fun lowerFromSwarmIr(swarmIr: JSONObject): JSONObject {
        require(swarmIr.optString("schema") == SOURCE_SWARM_SCHEMA) {
            "Rift IR V1 lowering requires $SOURCE_SWARM_SCHEMA"
        }
        require(!swarmIr.optBoolean("executable", true)) {
            "Rift IR V1 only accepts non-executable V0 Swarm IR"
        }

        val source = swarmIr.getString("source")
        val sourceSha = swarmIr.getString("sourceSha256")
        require(sourceSha.matches(Regex("^[0-9a-f]{64}$"))) { "Rift IR sourceSha256 must be lowercase SHA-256" }

        val backendIds = LinkedHashSet<String>()
        val backendsOut = JSONArray()
        val backendsIn = swarmIr.getJSONArray("backends")
        for (i in 0 until backendsIn.length()) {
            val sourceBackend = backendsIn.getJSONObject(i)
            val id = requiredId(sourceBackend, "name", "backend")
            require(backendIds.add(id)) { "Rift IR duplicate backend '$id'" }
            val type = sourceBackend.getString("type").lowercase()
            require(type in backendTypes) { "Rift IR unsupported backend type '$type'" }
            require(!sourceBackend.optBoolean("connected", true)) { "Rift IR V1 cannot lower a connected backend" }
            backendsOut.put(JSONObject()
                .put("id", id)
                .put("type", type)
                .put("model", sourceBackend.opt("model") ?: JSONObject.NULL)
                .put("connected", false))
        }
        require(backendsOut.length() > 0) { "Rift IR requires at least one backend" }

        val brainsOut = JSONArray()
        val brainIds = LinkedHashSet<String>()
        val brainRoles = LinkedHashMap<String, String>()
        var brainContextTotal = 0L
        val brainsIn = swarmIr.getJSONArray("brains")
        for (i in 0 until brainsIn.length()) {
            val sourceBrain = brainsIn.getJSONObject(i)
            val id = requiredId(sourceBrain, "name", "brain")
            require(brainIds.add(id)) { "Rift IR duplicate brain '$id'" }
            val backend = sourceBrain.getString("backend")
            require(backend in backendIds) { "Rift IR brain '$id' references unknown backend '$backend'" }
            val role = sourceBrain.getString("role").lowercase()
            require(role in roles) { "Rift IR brain '$id' has unknown role '$role'" }
            val memory = sourceBrain.getString("memory").lowercase()
            require(memory in memoryScopes) { "Rift IR brain '$id' has unknown memory scope '$memory'" }
            val context = sourceBrain.getInt("contextTokens")
            require(context in 256..MAX_CONTEXT_TOKENS) { "Rift IR brain '$id' context is out of range" }
            brainContextTotal += context.toLong()
            brainRoles[id] = role
            brainsOut.put(JSONObject()
                .put("id", id)
                .put("kind", "brain")
                .put("backend", backend)
                .put("role", role)
                .put("memory", memory)
                .put("contextTokens", context))
        }
        require(brainsOut.length() > 0) { "Rift IR requires at least one brain" }

        val agentsOut = JSONArray()
        val agentIds = LinkedHashSet<String>()
        val agentRoles = LinkedHashMap<String, String>()
        val declaredCapabilities = LinkedHashSet<String>()
        var agentContextTotal = 0L
        val agentsIn = swarmIr.getJSONArray("agents")
        for (i in 0 until agentsIn.length()) {
            val sourceAgent = agentsIn.getJSONObject(i)
            val id = requiredId(sourceAgent, "name", "agent")
            require(agentIds.add(id)) { "Rift IR duplicate agent '$id'" }
            require(id !in brainIds) { "Rift IR actor id '$id' is used by both brain and agent" }
            val role = sourceAgent.getString("role").lowercase()
            require(role in roles && role != "lead") { "Rift IR agent '$id' has invalid role '$role'" }
            val context = sourceAgent.getInt("contextTokens")
            require(context in 256..MAX_CONTEXT_TOKENS) { "Rift IR agent '$id' context is out of range" }
            val tools = stringList(sourceAgent.getJSONArray("tools"), "$id tools")
            val allow = stringList(sourceAgent.getJSONArray("allow"), "$id allow")
            val deny = stringList(sourceAgent.getJSONArray("deny"), "$id deny")
            validateCapabilityPolicy(id, role, tools, allow, deny)
            declaredCapabilities.addAll(tools)
            declaredCapabilities.addAll(allow)
            declaredCapabilities.addAll(deny)
            agentContextTotal += context.toLong()
            agentRoles[id] = role
            agentsOut.put(JSONObject()
                .put("id", id)
                .put("kind", "agent")
                .put("role", role)
                .put("contextTokens", context)
                .put("tools", JSONArray(tools))
                .put("allow", JSONArray(allow))
                .put("deny", JSONArray(deny)))
        }
        require(agentsOut.length() > 0) { "Rift IR requires at least one agent" }

        val swarmsOut = JSONArray()
        val swarmIds = LinkedHashSet<String>()
        val swarmWorkers = LinkedHashMap<String, List<String>>()
        val swarmsIn = swarmIr.getJSONArray("swarms")
        for (i in 0 until swarmsIn.length()) {
            val sourceSwarm = swarmsIn.getJSONObject(i)
            val id = requiredId(sourceSwarm, "name", "swarm")
            require(swarmIds.add(id)) { "Rift IR duplicate swarm '$id'" }
            val lead = sourceSwarm.getString("lead")
            require(lead in brainIds) { "Rift IR swarm '$id' references unknown lead '$lead'" }
            require(brainRoles[lead] == "lead") { "Rift IR swarm '$id' lead '$lead' must have role lead" }
            val workers = stringList(sourceSwarm.getJSONArray("workers"), "$id workers")
            require(workers.isNotEmpty() && workers.size <= MAX_WORKERS) { "Rift IR swarm '$id' worker count is out of range" }
            require(workers.toSet().size == workers.size) { "Rift IR swarm '$id' contains duplicate workers" }
            workers.forEach { require(it in agentIds) { "Rift IR swarm '$id' references unknown worker '$it'" } }

            val edges = mutableListOf<Pair<String, String>>()
            val edgeJson = JSONArray()
            val flowsIn = sourceSwarm.getJSONArray("flows")
            val members = (workers + lead).toSet()
            for (j in 0 until flowsIn.length()) {
                val flow = flowsIn.getJSONObject(j)
                val from = flow.getString("from")
                val to = flow.getString("to")
                require(from in members && to in members) { "Rift IR swarm '$id' flow endpoint is not a member" }
                require(from != to) { "Rift IR swarm '$id' contains self-flow '$from'" }
                if ((from to to) !in edges) {
                    edges += from to to
                    edgeJson.put(JSONObject().put("from", from).put("to", to))
                }
            }
            val derivedSchedule = deterministicSchedule(id, members, edges)
            val sourceSchedule = stringList(sourceSwarm.getJSONArray("scheduleOrder"), "$id source schedule")
            require(sourceSchedule == derivedSchedule) { "Rift IR swarm '$id' source schedule does not match its graph" }

            val reviewers = sourceSwarm.getInt("reviewersRequired")
            require(reviewers in 0..MAX_REVIEWERS) { "Rift IR swarm '$id' reviewer count is out of range" }
            val availableReviewers = workers.count { agentRoles[it] == "review" }
            require(reviewers <= availableReviewers) { "Rift IR swarm '$id' requires more reviewers than it contains" }
            swarmWorkers[id] = workers
            swarmsOut.put(JSONObject()
                .put("id", id)
                .put("lead", lead)
                .put("workers", JSONArray(workers))
                .put("edges", edgeJson)
                .put("schedule", JSONArray(derivedSchedule))
                .put("reviewersRequired", reviewers))
        }
        require(swarmsOut.length() > 0) { "Rift IR requires at least one swarm graph" }

        val tasksOut = JSONArray()
        val taskIds = LinkedHashSet<String>()
        val requiredGates = LinkedHashSet<String>()
        val tasksIn = swarmIr.getJSONArray("tasks")
        for (i in 0 until tasksIn.length()) {
            val sourceTask = tasksIn.getJSONObject(i)
            val id = requiredId(sourceTask, "name", "task")
            require(taskIds.add(id)) { "Rift IR duplicate task '$id'" }
            val swarm = sourceTask.getString("swarm")
            require(swarm in swarmIds) { "Rift IR task '$id' references unknown swarm '$swarm'" }
            val goal = sourceTask.getString("goal")
            require(goal.isNotBlank() && goal.length <= 4096) { "Rift IR task '$id' goal is invalid" }
            val retry = sourceTask.getInt("retry")
            require(retry in 0..MAX_RETRIES) { "Rift IR task '$id' retry budget is out of range" }
            val taskRequirements = stringList(sourceTask.getJSONArray("require"), "$id requirements")
            require(taskRequirements.all { it in requirements }) { "Rift IR task '$id' has an unknown validation requirement" }
            require(taskRequirements.toSet().size == taskRequirements.size) { "Rift IR task '$id' has duplicate validation requirements" }
            val availableRoles = swarmWorkers.getValue(swarm).mapNotNull { agentRoles[it] }.toSet()
            taskRequirements.forEach { needed ->
                require(needed in availableRoles) { "Rift IR task '$id' requirement '$needed' lacks a matching worker" }
            }
            requiredGates.addAll(taskRequirements)
            tasksOut.put(JSONObject()
                .put("id", id)
                .put("swarm", swarm)
                .put("goal", goal)
                .put("retryBudget", retry)
                .put("requirements", JSONArray(taskRequirements)))
        }
        require(tasksOut.length() > 0) { "Rift IR requires at least one task" }

        val declaredTotal = brainContextTotal + agentContextTotal
        val ir = JSONObject()
            .put("schema", SCHEMA)
            .put("irVersion", VERSION)
            .put("profile", PROFILE)
            .put("experimental", true)
            .put("executable", false)
            .put("identity", JSONObject()
                .put("producer", "rift++/0")
                .put("sourceSchema", SOURCE_SWARM_SCHEMA)
                .put("source", source)
                .put("sourceSha256", sourceSha))
            .put("backends", backendsOut)
            .put("actors", JSONObject()
                .put("brains", brainsOut)
                .put("agents", agentsOut))
            .put("graphs", JSONObject().put("swarms", swarmsOut))
            .put("tasks", tasksOut)
            .put("resources", JSONObject()
                .put("actorCount", brainsOut.length() + agentsOut.length())
                .put("brainCount", brainsOut.length())
                .put("agentCount", agentsOut.length())
                .put("swarmCount", swarmsOut.length())
                .put("taskCount", tasksOut.length())
                .put("contextTokens", JSONObject()
                    .put("brainDeclaredTotal", brainContextTotal)
                    .put("agentDeclaredTotal", agentContextTotal)
                    .put("declaredTotal", declaredTotal)
                    .put("maxPerActor", MAX_CONTEXT_TOKENS)))
            .put("policy", JSONObject()
                .put("capabilityRegistry", JSONArray(capabilities))
                .put("declaredCapabilities", JSONArray(declaredCapabilities.toList().sorted()))
                .put("mutatingCapabilities", JSONArray(mutatingCapabilities))
                .put("readOnlyRoles", JSONArray(readOnlyRoles))
                .put("mutationAllowed", false)
                .put("rawShellAllowed", false)
                .put("arbitraryProcessAllowed", false))
            .put("execution", JSONObject()
                .put("mode", "inspect-only")
                .put("scheduler", "deterministic-topological-v1")
                .put("defaultConcurrency", 1)
                .put("backendInvocation", false)
                .put("toolInvocation", false)
                .put("localAgentInvocation", false)
                .put("mutation", false))
            .put("validation", JSONObject()
                .put("compilerValidated", true)
                .put("graphAcyclic", true)
                .put("scheduleValidated", true)
                .put("policyValidated", true)
                .put("requiredGates", JSONArray(requiredGates.toList().sorted())))
            .put("compatibility", JSONObject()
                .put("loweredFrom", SOURCE_SWARM_SCHEMA)
                .put("sourceExecutable", false))

        validateCore(ir)
        ir.getJSONObject("validation").put("irValidated", true)
        return ir
    }

    fun validate(ir: JSONObject): JSONObject {
        val facts = validateCore(ir)
        return JSONObject()
            .put("schema", "rift.ir-validation/1")
            .put("ok", true)
            .put("irSchema", SCHEMA)
            .put("profile", PROFILE)
            .put("executable", false)
            .put("source", facts.source)
            .put("sourceSha256", facts.sourceSha)
            .put("actorCount", facts.actorCount)
            .put("swarmCount", facts.swarmCount)
            .put("taskCount", facts.taskCount)
            .put("declaredContextTokens", facts.declaredContextTokens)
            .put("requiredGates", JSONArray(facts.requiredGates))
    }

    fun inspect(ir: JSONObject): JSONObject {
        val facts = validateCore(ir)
        val schedules = JSONArray()
        val swarms = ir.getJSONObject("graphs").getJSONArray("swarms")
        for (i in 0 until swarms.length()) {
            val swarm = swarms.getJSONObject(i)
            schedules.put(JSONObject()
                .put("swarm", swarm.getString("id"))
                .put("lead", swarm.getString("lead"))
                .put("reviewersRequired", swarm.getInt("reviewersRequired"))
                .put("schedule", swarm.getJSONArray("schedule")))
        }
        return JSONObject()
            .put("schema", "rift.ir-inspection/1")
            .put("irSchema", SCHEMA)
            .put("profile", PROFILE)
            .put("experimental", true)
            .put("executable", false)
            .put("source", facts.source)
            .put("sourceSha256", facts.sourceSha)
            .put("counts", JSONObject()
                .put("actors", facts.actorCount)
                .put("swarms", facts.swarmCount)
                .put("tasks", facts.taskCount))
            .put("resources", JSONObject()
                .put("declaredContextTokens", facts.declaredContextTokens)
                .put("defaultConcurrency", 1))
            .put("requiredGates", JSONArray(facts.requiredGates))
            .put("schedules", schedules)
            .put("execution", JSONObject()
                .put("mode", "inspect-only")
                .put("backendInvocation", false)
                .put("toolInvocation", false)
                .put("localAgentInvocation", false)
                .put("mutation", false))
    }

    private data class Facts(
        val source: String,
        val sourceSha: String,
        val actorCount: Int,
        val swarmCount: Int,
        val taskCount: Int,
        val declaredContextTokens: Long,
        val requiredGates: List<String>
    )

    /** Defense-in-depth validation of the IR itself; do not trust producer validation flags. */
    private fun validateCore(ir: JSONObject): Facts {
        require(ir.optString("schema") == SCHEMA) { "Rift IR schema must be $SCHEMA" }
        require(ir.optInt("irVersion", -1) == VERSION) { "Rift IR version must be $VERSION" }
        require(ir.optString("profile") == PROFILE) { "Rift IR V1 profile must be $PROFILE" }
        require(ir.optBoolean("experimental", false)) { "Rift IR V1 must remain experimental" }
        require(!ir.optBoolean("executable", true)) { "Rift IR V1 core must remain non-executable" }

        val identity = ir.getJSONObject("identity")
        require(identity.optString("producer") == "rift++/0") { "Rift IR V1 swarm-core producer must be rift++/0" }
        require(identity.optString("sourceSchema") == SOURCE_SWARM_SCHEMA) { "Rift IR source schema mismatch" }
        val source = identity.getString("source")
        val sourceSha = identity.getString("sourceSha256")
        require(source.isNotBlank()) { "Rift IR source is blank" }
        require(sourceSha.matches(Regex("^[0-9a-f]{64}$"))) { "Rift IR sourceSha256 is invalid" }

        val backendIds = stringIdSet(ir.getJSONArray("backends"), "backend")
        require(backendIds.isNotEmpty()) { "Rift IR has no backends" }
        val backends = ir.getJSONArray("backends")
        for (i in 0 until backends.length()) {
            val backend = backends.getJSONObject(i)
            require(backend.getString("type") in backendTypes) { "Rift IR backend type is invalid" }
            require(!backend.optBoolean("connected", true)) { "Rift IR V1 backend cannot be connected" }
        }

        val actors = ir.getJSONObject("actors")
        val brains = actors.getJSONArray("brains")
        val agents = actors.getJSONArray("agents")
        val brainIds = stringIdSet(brains, "brain")
        val agentIds = stringIdSet(agents, "agent")
        require(brainIds.intersect(agentIds).isEmpty()) { "Rift IR actor ids must be globally unique" }
        require(brainIds.isNotEmpty() && agentIds.isNotEmpty()) { "Rift IR requires brains and agents" }
        val brainRoles = LinkedHashMap<String, String>()
        var brainTotal = 0L
        for (i in 0 until brains.length()) {
            val brain = brains.getJSONObject(i)
            require(brain.optString("kind") == "brain") { "Rift IR brain kind mismatch" }
            require(brain.getString("backend") in backendIds) { "Rift IR brain references unknown backend" }
            val role = brain.getString("role")
            require(role in roles) { "Rift IR brain has unknown role '$role'" }
            require(brain.getString("memory") in memoryScopes) { "Rift IR brain has invalid memory scope" }
            val context = brain.getInt("contextTokens")
            require(context in 256..MAX_CONTEXT_TOKENS) { "Rift IR brain context out of range" }
            brainTotal += context
            brainRoles[brain.getString("id")] = role
        }

        val agentRoles = LinkedHashMap<String, String>()
        val recomputedDeclaredCapabilities = LinkedHashSet<String>()
        var agentTotal = 0L
        for (i in 0 until agents.length()) {
            val agent = agents.getJSONObject(i)
            require(agent.optString("kind") == "agent") { "Rift IR agent kind mismatch" }
            val id = agent.getString("id")
            val role = agent.getString("role")
            require(role in roles && role != "lead") { "Rift IR agent '$id' has invalid role '$role'" }
            val context = agent.getInt("contextTokens")
            require(context in 256..MAX_CONTEXT_TOKENS) { "Rift IR agent '$id' context out of range" }
            val tools = stringList(agent.getJSONArray("tools"), "$id tools")
            val allow = stringList(agent.getJSONArray("allow"), "$id allow")
            val deny = stringList(agent.getJSONArray("deny"), "$id deny")
            validateCapabilityPolicy(id, role, tools, allow, deny)
            recomputedDeclaredCapabilities.addAll(tools)
            recomputedDeclaredCapabilities.addAll(allow)
            recomputedDeclaredCapabilities.addAll(deny)
            agentTotal += context
            agentRoles[id] = role
        }

        val swarms = ir.getJSONObject("graphs").getJSONArray("swarms")
        require(swarms.length() > 0) { "Rift IR has no swarm graphs" }
        val swarmIds = LinkedHashSet<String>()
        val swarmWorkers = LinkedHashMap<String, List<String>>()
        for (i in 0 until swarms.length()) {
            val swarm = swarms.getJSONObject(i)
            val id = requiredId(swarm, "id", "swarm")
            require(swarmIds.add(id)) { "Rift IR duplicate swarm '$id'" }
            val lead = swarm.getString("lead")
            require(lead in brainIds && brainRoles[lead] == "lead") { "Rift IR swarm '$id' has invalid lead" }
            val workers = stringList(swarm.getJSONArray("workers"), "$id workers")
            require(workers.isNotEmpty() && workers.size <= MAX_WORKERS && workers.toSet().size == workers.size) {
                "Rift IR swarm '$id' workers are invalid"
            }
            workers.forEach { require(it in agentIds) { "Rift IR swarm '$id' has unknown worker '$it'" } }
            val members = (workers + lead).toSet()
            val edges = mutableListOf<Pair<String, String>>()
            val edgeArray = swarm.getJSONArray("edges")
            for (j in 0 until edgeArray.length()) {
                val edge = edgeArray.getJSONObject(j)
                val from = edge.getString("from")
                val to = edge.getString("to")
                require(from in members && to in members && from != to) { "Rift IR swarm '$id' has invalid edge" }
                require((from to to) !in edges) { "Rift IR swarm '$id' contains duplicate edge '$from -> $to'" }
                edges += from to to
            }
            val derived = deterministicSchedule(id, members, edges)
            val schedule = stringList(swarm.getJSONArray("schedule"), "$id schedule")
            require(schedule == derived) { "Rift IR swarm '$id' schedule is not canonical" }
            val reviewers = swarm.getInt("reviewersRequired")
            require(reviewers in 0..MAX_REVIEWERS) { "Rift IR swarm '$id' reviewer count out of range" }
            require(reviewers <= workers.count { agentRoles[it] == "review" }) { "Rift IR swarm '$id' reviewer policy is unsatisfied" }
            swarmWorkers[id] = workers
        }

        val tasks = ir.getJSONArray("tasks")
        require(tasks.length() > 0) { "Rift IR has no tasks" }
        val taskIds = LinkedHashSet<String>()
        val gates = LinkedHashSet<String>()
        for (i in 0 until tasks.length()) {
            val task = tasks.getJSONObject(i)
            val id = requiredId(task, "id", "task")
            require(taskIds.add(id)) { "Rift IR duplicate task '$id'" }
            val swarm = task.getString("swarm")
            require(swarm in swarmIds) { "Rift IR task '$id' references unknown swarm" }
            val goal = task.getString("goal")
            require(goal.isNotBlank() && goal.length <= 4096) { "Rift IR task '$id' goal is invalid" }
            require(task.getInt("retryBudget") in 0..MAX_RETRIES) { "Rift IR task '$id' retry budget is invalid" }
            val reqs = stringList(task.getJSONArray("requirements"), "$id requirements")
            require(reqs.all { it in requirements } && reqs.toSet().size == reqs.size) { "Rift IR task '$id' requirements are invalid" }
            val availableRoles = swarmWorkers.getValue(swarm).mapNotNull { agentRoles[it] }.toSet()
            reqs.forEach { require(it in availableRoles) { "Rift IR task '$id' requirement '$it' is unsatisfied" } }
            gates.addAll(reqs)
        }

        val resources = ir.getJSONObject("resources")
        val declaredTotal = brainTotal + agentTotal
        require(resources.getInt("actorCount") == brains.length() + agents.length()) { "Rift IR actorCount mismatch" }
        require(resources.getInt("brainCount") == brains.length()) { "Rift IR brainCount mismatch" }
        require(resources.getInt("agentCount") == agents.length()) { "Rift IR agentCount mismatch" }
        require(resources.getInt("swarmCount") == swarms.length()) { "Rift IR swarmCount mismatch" }
        require(resources.getInt("taskCount") == tasks.length()) { "Rift IR taskCount mismatch" }
        val context = resources.getJSONObject("contextTokens")
        require(context.getLong("brainDeclaredTotal") == brainTotal) { "Rift IR brain context total mismatch" }
        require(context.getLong("agentDeclaredTotal") == agentTotal) { "Rift IR agent context total mismatch" }
        require(context.getLong("declaredTotal") == declaredTotal) { "Rift IR total context mismatch" }
        require(context.getInt("maxPerActor") == MAX_CONTEXT_TOKENS) { "Rift IR max actor context mismatch" }

        val policy = ir.getJSONObject("policy")
        require(stringList(policy.getJSONArray("capabilityRegistry"), "capability registry") == capabilities) { "Rift IR capability registry mismatch" }
        val declared = stringList(policy.getJSONArray("declaredCapabilities"), "declared capabilities")
        require(declared.all { it in capabilitySet }) { "Rift IR declares an unknown capability" }
        require(declared == recomputedDeclaredCapabilities.toList().sorted()) { "Rift IR declared capability set does not match actor policy" }
        require(stringList(policy.getJSONArray("mutatingCapabilities"), "mutating capabilities") == mutatingCapabilities) { "Rift IR mutation capability registry mismatch" }
        require(stringList(policy.getJSONArray("readOnlyRoles"), "read-only roles") == readOnlyRoles) { "Rift IR read-only role registry mismatch" }
        require(!policy.optBoolean("mutationAllowed", true)) { "Rift IR V1 mutation must remain disabled" }
        require(!policy.optBoolean("rawShellAllowed", true)) { "Rift IR V1 raw shell must remain disabled" }
        require(!policy.optBoolean("arbitraryProcessAllowed", true)) { "Rift IR V1 arbitrary process execution must remain disabled" }

        val execution = ir.getJSONObject("execution")
        require(execution.optString("mode") == "inspect-only") { "Rift IR V1 execution mode must be inspect-only" }
        require(execution.optString("scheduler") == "deterministic-topological-v1") { "Rift IR scheduler mismatch" }
        require(execution.optInt("defaultConcurrency", -1) == 1) { "Rift IR V1 defaultConcurrency must be 1" }
        require(!execution.optBoolean("backendInvocation", true)) { "Rift IR V1 backend invocation must be disabled" }
        require(!execution.optBoolean("toolInvocation", true)) { "Rift IR V1 tool invocation must be disabled" }
        require(!execution.optBoolean("localAgentInvocation", true)) { "Rift IR V1 Local Agent invocation must be disabled" }
        require(!execution.optBoolean("mutation", true)) { "Rift IR V1 mutation execution must be disabled" }

        val validation = ir.getJSONObject("validation")
        require(validation.optBoolean("compilerValidated", false)) { "Rift IR compilerValidated marker missing" }
        require(validation.optBoolean("graphAcyclic", false)) { "Rift IR graph validation marker missing" }
        require(validation.optBoolean("scheduleValidated", false)) { "Rift IR schedule validation marker missing" }
        require(validation.optBoolean("policyValidated", false)) { "Rift IR policy validation marker missing" }
        val expectedGates = gates.toList().sorted()
        require(stringList(validation.getJSONArray("requiredGates"), "required gates") == expectedGates) { "Rift IR required gate set mismatch" }

        val compatibility = ir.getJSONObject("compatibility")
        require(compatibility.optString("loweredFrom") == SOURCE_SWARM_SCHEMA) { "Rift IR compatibility source mismatch" }
        require(!compatibility.optBoolean("sourceExecutable", true)) { "Rift IR source must remain non-executable" }

        return Facts(source, sourceSha, brains.length() + agents.length(), swarms.length(), tasks.length(), declaredTotal, expectedGates)
    }

    private fun validateCapabilityPolicy(id: String, role: String, tools: List<String>, allow: List<String>, deny: List<String>) {
        listOf(tools, allow, deny).flatten().forEach {
            require(it in capabilitySet) { "Rift IR actor '$id' uses unknown capability '$it'" }
        }
        require(tools.toSet().size == tools.size && allow.toSet().size == allow.size && deny.toSet().size == deny.size) {
            "Rift IR actor '$id' contains duplicate capabilities"
        }
        require(allow.intersect(deny.toSet()).isEmpty()) { "Rift IR actor '$id' allows and denies the same capability" }
        require(tools.all { it in allow }) { "Rift IR actor '$id' tool capability is not allowed" }
        require(tools.none { it in deny }) { "Rift IR actor '$id' tool capability is denied" }
        if (role in readOnlyRoleSet) {
            require(allow.none { it in mutatingSet }) { "Rift IR read-only actor '$id' has mutation authority" }
        }
    }

    private fun deterministicSchedule(name: String, members: Set<String>, edges: List<Pair<String, String>>): List<String> {
        val outgoing = members.associateWith { mutableListOf<String>() }.toMutableMap()
        val indegree = members.associateWith { 0 }.toMutableMap()
        edges.forEach { (from, to) ->
            if (!outgoing.getValue(from).contains(to)) {
                outgoing.getValue(from).add(to)
                indegree[to] = indegree.getValue(to) + 1
            }
        }
        val ready = members.filter { indegree.getValue(it) == 0 }.sorted().toMutableList()
        val order = mutableListOf<String>()
        while (ready.isNotEmpty()) {
            val current = ready.removeAt(0)
            order += current
            outgoing.getValue(current).sorted().forEach { next ->
                indegree[next] = indegree.getValue(next) - 1
                if (indegree.getValue(next) == 0) {
                    ready += next
                    ready.sort()
                }
            }
        }
        require(order.size == members.size) { "Rift IR graph '$name' contains a cycle" }
        return order
    }

    private fun requiredId(obj: JSONObject, key: String, kind: String): String {
        val id = obj.optString(key).trim()
        require(id.isNotBlank()) { "Rift IR $kind id is blank" }
        return id
    }

    private fun stringIdSet(array: JSONArray, kind: String): LinkedHashSet<String> {
        val ids = LinkedHashSet<String>()
        for (i in 0 until array.length()) {
            val id = requiredId(array.getJSONObject(i), "id", kind)
            require(ids.add(id)) { "Rift IR duplicate $kind '$id'" }
        }
        return ids
    }

    private fun stringList(array: JSONArray, label: String): List<String> {
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val value = array.optString(i, "")
            require(value.isNotBlank()) { "Rift IR $label contains a blank value" }
            out += value
        }
        return out
    }
}
