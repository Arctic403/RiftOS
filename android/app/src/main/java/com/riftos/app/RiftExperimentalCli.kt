package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * EXPERIMENTAL / MANUAL-ONLY RiftCLI brain scaffold.
 *
 * This subsystem is intentionally OFF on every process start. It must never auto-enable, never
 * create a new MCP tool, and never widen Local Agent authority. Until explicitly promoted by the
 * project owner, the only live execution path in experimental mode is a compatibility pass-through
 * to the existing fixed-scope RiftOS Local Agent.
 */
object RiftExperimentalCli {
    private enum class Mode { LEGACY, EXPERIMENTAL }

    data class CommandResult(val output: String, val result: JSONObject)

    private data class TeamRole(
        val id: String,
        val label: String,
        val responsibility: String
    ) {
        fun json(): JSONObject = JSONObject()
            .put("id", id)
            .put("label", label)
            .put("responsibility", responsibility)
    }

    private val roles = listOf(
        TeamRole("lead", "Lead Brain", "owns goal decomposition, task graph and final integration decision"),
        TeamRole("architect", "Architecture", "maps subsystem boundaries, invariants and dependency impact"),
        TeamRole("research", "Research", "collects bounded evidence and unresolved assumptions"),
        TeamRole("cpp", "C++ / Native", "native runtime, JNI, memory and performance-sensitive implementation"),
        TeamRole("android", "Android / Kotlin", "Android framework, lifecycle, services and native UI"),
        TeamRole("js", "JavaScript / Shell", "trusted shell, browser-side runtime and compatibility surfaces"),
        TeamRole("debug", "Debug", "failure isolation, reproduction and root-cause tracing"),
        TeamRole("build", "Build / CI", "Gradle, packaging, builder and release validation"),
        TeamRole("tests", "Tests", "regression coverage, acceptance criteria and negative tests"),
        TeamRole("security", "Security", "permission, boundary, secret and attack-surface review"),
        TeamRole("performance", "Performance", "profiling, memory, latency and thermal review"),
        TeamRole("docs", "Documentation", "ownership maps, README contracts and fix maps"),
        TeamRole("review", "Independent Reviewer", "challenges assumptions and rejects unsafe/incomplete plans")
    )

    @Volatile private var mode: Mode = Mode.LEGACY
    @Volatile private var lastRoute: String = "none"

    fun isEnabled(): Boolean = mode == Mode.EXPERIMENTAL

    fun executeShell(context: Context, args: List<String>): CommandResult {
        val sub = args.firstOrNull()?.trim()?.lowercase().orEmpty().ifBlank { "help" }
        val tail = args.drop(1)
        return when (sub) {
            "help" -> result(helpText(), statusJson())
            "status" -> result(statusJson().toString(2), statusJson())
            "team" -> {
                val value = JSONObject()
                    .put("experimental", true)
                    .put("mode", mode.name.lowercase())
                    .put("roles", JSONArray(roles.map { it.json() }))
                result(value.toString(2), value)
            }
            "architecture" -> {
                val value = JSONObject()
                    .put("experimental", true)
                    .put("productionReady", false)
                    .put("flow", "MCP/Relay -> RiftShell -> manual router -> RiftCLI brain -> specialist swarm -> existing Local Agent hands")
                    .put("brainBackend", "scaffold-rule-planner")
                    .put("modelBackendConnected", false)
                    .put("riftIrSchema", RiftIrV1.SCHEMA)
                    .put("riftIrProfile", RiftIrV1.PROFILE)
                    .put("riftIrExecutable", false)
                    .put("newMcpTools", 0)
                    .put("authorityWidened", false)
                    .put("persistentEnable", false)
                    .put("autoMutation", false)
                result(value.toString(2), value)
            }
            "enable" -> {
                require(tail.size == 1 && tail[0].equals("CONFIRM-EXPERIMENTAL", ignoreCase = true)) {
                    "EXPERIMENTAL RiftCLI stays OFF. To enable for this process only: rift-cli enable CONFIRM-EXPERIMENTAL"
                }
                mode = Mode.EXPERIMENTAL
                lastRoute = "none"
                val value = statusJson().put("notice", "EXPERIMENTAL MODE ENABLED FOR THIS PROCESS ONLY")
                result("EXPERIMENTAL RiftCLI enabled for this process only. Restarting RiftOS resets to legacy mode.", value)
            }
            "disable" -> {
                RiftTextEncoderTaskRunner.cancelActive("experimental CLI disabled")
                mode = Mode.LEGACY
                lastRoute = "none"
                val value = statusJson().put("notice", "legacy Local Agent routing restored; active tokenizer training cancellation requested")
                result("RiftCLI experimental routing disabled. Legacy Local Agent routing is active.", value)
            }
            "plan" -> {
                require(isEnabled()) { "EXPERIMENTAL RiftCLI is OFF. Planning is unavailable until explicitly enabled." }
                val goal = tail.joinToString(" ").trim()
                require(goal.isNotBlank()) { "usage: rift-cli plan <goal>" }
                val value = plan(goal)
                result(value.toString(2), value)
            }
            "riftpp" -> {
                val action = tail.firstOrNull()?.trim()?.lowercase().orEmpty().ifBlank { "help" }
                if (action !in setOf("help", "sample")) {
                    require(isEnabled()) { "EXPERIMENTAL RiftCLI is OFF. Rift++ compile/preview requires explicit process-local enable." }
                }
                val value = RiftPlusPlusV0.execute(context, tail)
                result(value.toString(2), value)
            }
            "ir" -> {
                val action = tail.firstOrNull()?.trim()?.lowercase().orEmpty().ifBlank { "help" }
                if (action != "help") {
                    require(isEnabled()) { "EXPERIMENTAL RiftCLI is OFF. Rift IR compile/validate/inspect requires explicit process-local enable." }
                }
                val value = RiftIrCliV1.execute(context, tail)
                result(value.toString(2), value)
            }
            "tokenizer" -> {
                require(isEnabled()) { "EXPERIMENTAL RiftCLI is OFF. Tokenizer tasks require explicit process-local enable." }
                require(tail.size <= 1) { "usage: rift-cli tokenizer status|self-test|train-a|train-b|train-a2|train-b2|train-status|train-cancel" }
                val action = tail.firstOrNull() ?: "status"
                val value = RiftTextEncoderTaskRunner.execute(context, action)
                result(value.toString(2), value)
            }
            "lifecycle" -> {
                val action = tail.firstOrNull()?.trim()?.lowercase().orEmpty().ifBlank { "help" }
                if (action !in setOf("help", "contract")) {
                    require(isEnabled()) {
                        "EXPERIMENTAL RiftCLI is OFF. Patch lifecycle sessions require explicit process-local enable."
                    }
                }
                val lifecycle = RiftCliPatchLifecycleV1.execute(context, tail)
                result(lifecycle.output, lifecycle.result)
            }
            else -> throw IllegalArgumentException("unknown rift-cli command: $sub")
        }
    }

    /**
     * The single switch directly above the existing RiftOS Local Agent.
     * Experimental mode currently observes/classifies then delegates to the exact legacy authority.
     */
    fun routeLocalAgent(context: Context, args: JSONObject): JSONObject {
        if (!isEnabled()) return RiftOsLocalAgent.execute(context, args)
        val op = args.optString("op").trim().lowercase().ifBlank { "unknown" }
        val worker = classifyAgentWorker(op)
        lastRoute = "$worker:$op"
        return RiftOsLocalAgent.execute(context, args)
    }

    private fun plan(goal: String): JSONObject {
        val selected = LinkedHashSet<String>()
        selected += "lead"
        selected += "architect"
        selected += "research"
        val lower = goal.lowercase()
        if (listOf("android", "kotlin", "activity", "service", "accessibility", "manifest").any { lower.contains(it) }) selected += "android"
        if (listOf("c++", "cpp", "jni", "native", "memory", "kernel").any { lower.contains(it) }) selected += "cpp"
        if (listOf("javascript", " js", "shell", "browser", "webview", "html", "css").any { lower.contains(it) }) selected += "js"
        if (listOf("build", "gradle", "ci", "apk", "release", "builder").any { lower.contains(it) }) selected += "build"
        if (listOf("crash", "fail", "bug", "broken", "debug", "regression").any { lower.contains(it) }) selected += "debug"
        if (listOf("slow", "performance", "latency", "memory", "thermal", "benchmark").any { lower.contains(it) }) selected += "performance"
        selected += "tests"
        selected += "security"
        selected += "docs"
        selected += "review"

        val taskGraph = JSONArray()
            .put(JSONObject().put("stage", 1).put("owner", "architect").put("task", "map boundaries, invariants and affected systems"))
            .put(JSONObject().put("stage", 2).put("owner", "research").put("task", "collect evidence and identify unresolved assumptions"))
            .put(JSONObject().put("stage", 3).put("owner", "specialists").put("task", "produce candidate implementation or diagnosis"))
            .put(JSONObject().put("stage", 4).put("owner", "tests/security/performance").put("task", "validate correctness, boundaries and regressions"))
            .put(JSONObject().put("stage", 5).put("owner", "review").put("task", "challenge the candidate independently"))
            .put(JSONObject().put("stage", 6).put("owner", "lead").put("task", "integrate evidence and decide next action"))

        return JSONObject()
            .put("schema", "rift.experimental-cli-plan/1")
            .put("experimental", true)
            .put("productionReady", false)
            .put("goal", goal)
            .put("brainBackend", "scaffold-rule-planner")
            .put("modelBackendConnected", false)
            .put("executionRequested", false)
            .put("autoMutation", false)
            .put("selectedRoles", JSONArray(selected.toList()))
            .put("taskGraph", taskGraph)
            .put("note", "This is a planning scaffold only; it does not execute shell, Git, workspace, build or Local Agent mutations.")
    }

    private fun classifyAgentWorker(op: String): String = when (op) {
        "browser-inspect" -> "js"
        "devlab" -> "android"
        "keyboard", "type-focused", "tap", "swipe", "click", "back", "open", "tree", "status" -> "android"
        else -> "lead"
    }

    private fun statusJson(): JSONObject = JSONObject()
        .put("schema", "rift.experimental-cli-status/1")
        .put("experimental", true)
        .put("productionReady", false)
        .put("mode", mode.name.lowercase())
        .put("enabled", isEnabled())
        .put("defaultMode", "legacy")
        .put("resetsOnProcessRestart", true)
        .put("modelBackendConnected", false)
        .put("brainBackend", "scaffold-rule-planner")
        .put("swarmRoles", roles.size)
        .put("riftPlusPlusV0", true)
        .put("swarmIrSchema", "rift.swarm-ir/0")
        .put("riftIrV1", true)
        .put("riftIrSchema", RiftIrV1.SCHEMA)
        .put("riftIrProfile", RiftIrV1.PROFILE)
        .put("riftIrExecutable", false)
        .put("brainBackendInterface", "RiftBrainBackend")
        .put("autoMutation", false)
        .put("manualTokenizerTasks", isEnabled())
        .put("tokenizerTaskExecution", "native-kotlin-fixed-paths-async")
        .put("patchLifecycleV1", true)
        .put("patchLifecycleMode", "OBSERVE")
        .put("patchLifecycleTrustedPromotion", false)
        .put("newMcpTools", 0)
        .put("authorityWidened", false)
        .put("lastLocalAgentRoute", lastRoute)

    private fun result(output: String, value: JSONObject) = CommandResult(output, value)

    private fun helpText(): String = """
        EXPERIMENTAL RiftCLI Brain / Development Swarm — DO NOT USE AS PRODUCTION
        Default: OFF / legacy Local Agent. Restarting RiftOS always resets this switch to OFF.

        rift-cli status
        rift-cli team
        rift-cli architecture
        rift-cli enable CONFIRM-EXPERIMENTAL
        rift-cli disable
        rift-cli plan <goal>     # planning scaffold only; never executes mutations
        rift-cli riftpp help|sample|validate|compile|preview
                                # Rift++ V0 declarative swarm DSL -> non-executable Swarm IR
        rift-cli ir help|compile|validate|inspect
                                # Rift IR V1 language-independent inspect-only core; no run/execution command
        rift-cli tokenizer status|self-test|train-a|train-b|train-a2|train-b2|train-status|train-cancel
                                # manual fixed-path RiftTokenizer V1/V2 tasks; training runs as one cancellable background job
        rift-cli lifecycle help|contract
        rift-cli lifecycle begin <project> <goal...>
        rift-cli lifecycle begin-sync <project> <goal...>
        rift-cli lifecycle status|request|evaluation|clear <sessionId>
        rift-cli lifecycle import <sessionId> <kind> <D:/Documents|D:/Temp json>
        rift-cli lifecycle verify <sessionId> <D:/Documents|D:/Temp evaluation.json>
                                # OBSERVE-only CLI -> AI patch lifecycle; no trust promotion or publication

        No new MCP tools. No raw Android shell. No generic Python/process runner. No wider package authority. No autonomous writes.
        The future model/swarm backend is intentionally not connected yet.
    """.trimIndent()
}

/** One routing seam above the existing RiftOS Local Agent; legacy is always the default. */
object RiftAgentRouter {
    fun execute(context: Context, args: JSONObject): JSONObject =
        RiftExperimentalCli.routeLocalAgent(context, args)
}
