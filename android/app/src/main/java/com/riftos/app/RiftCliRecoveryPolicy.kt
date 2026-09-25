package com.riftos.app

import org.json.JSONObject

/**
 * System-owned recovery policy for RiftCLI Batch V2.
 *
 * Callers may request recovery, but they cannot declare an operation retry-safe, idempotent or
 * rollback-capable. Retry remains deliberately conservative: only non-authoritative read/context
 * operations may replay. B2B separately marks the narrow shell mutation set that has an explicit
 * bounded pre-state rollback contract; rollback capability never makes a mutation retry-safe.
 */
internal object RiftCliRecoveryPolicy {
    const val SCHEMA = "rift.cli-recovery-policy/1"

    private val RETRY_SAFE_TOOLS = setOf(
        "rift_info",
        "rift_stat",
        "rift_hash",
        "rift_list",
        "rift_read_text",
        "rift_audit",
        "rift_scan",
        "rift_project_export",
        "rift_workspace_diff",
        "rift_debug"
    )

    private val MUTATING_TOOLS = setOf(
        "rift_write_text",
        "rift_mkdir",
        "rift_remove",
        "rift_move",
        "rift_copy",
        "rift_archive",
        "rift_extract"
    )

    private val ROLLBACK_CAPABLE_SHELL_COMMANDS = setOf("write", "touch", "mkdir")

    private val RETRY_SAFE_SHELL_COMMANDS = setOf(
        "help",
        "pwd",
        "cd",
        "home",
        "ps",
        "apps",
        "drives",
        "df",
        "sysinfo",
        "native",
        "uptime",
        "version",
        "ls",
        "tree",
        "stat",
        "cat",
        "head",
        "tail"
    )

    fun forTool(name: String, args: JSONObject): JSONObject {
        val operation = name.trim()
        val retrySafe = operation in RETRY_SAFE_TOOLS
        val mutation = operation in MUTATING_TOOLS || !retrySafe
        return result(
            kind = "tool",
            operation = operation,
            retrySafe = retrySafe,
            idempotent = retrySafe,
            authoritativeMutation = mutation,
            statefulExecutionContext = false,
            reason = when {
                retrySafe -> "RiftOS classifies this tool as read-only/non-authoritative for Batch recovery."
                operation in MUTATING_TOOLS -> "Mutation replay is not authorized by B2A; explicit B2B rollback/state proof is required."
                else -> "Tool is not present in the system-owned retry-safe registry."
            }
        ).put("normalizedArgsSha256", RiftCliRecoveryPolicyHashes.sha256(args.toString()))
    }

    fun forShell(command: String, args: List<String>): JSONObject {
        val operation = command.trim().lowercase()
        val retrySafe = operation in RETRY_SAFE_SHELL_COMMANDS
        return result(
            kind = "shell",
            operation = operation,
            retrySafe = retrySafe,
            idempotent = retrySafe,
            authoritativeMutation = !retrySafe,
            statefulExecutionContext = operation == "cd" || operation == "home",
            rollbackSupported = operation in ROLLBACK_CAPABLE_SHELL_COMMANDS,
            reason = when {
                retrySafe -> "RiftOS classifies this shell command as read/context-only for Batch recovery."
                operation in ROLLBACK_CAPABLE_SHELL_COMMANDS ->
                    "Mutation replay is forbidden; B2B permits rollback only with a persisted bounded pre-state contract."
                else -> "Shell command is neither retry-safe nor rollback-capable under the system-owned registry."
            }
        ).put("argumentCount", args.size)
    }

    fun queuedBeforeFirstStep(): JSONObject =
        result(
            kind = "batch",
            operation = "not-started",
            retrySafe = true,
            idempotent = true,
            authoritativeMutation = false,
            statefulExecutionContext = false,
            reason = "No Batch step started before process loss; resume starts at step 1 without replay."
        )

    fun betweenSteps(): JSONObject =
        result(
            kind = "batch",
            operation = "between-steps",
            retrySafe = true,
            idempotent = true,
            authoritativeMutation = false,
            statefulExecutionContext = false,
            reason = "All started steps are durably completed; resume begins at the next step without replay."
        )

    private fun result(
        kind: String,
        operation: String,
        retrySafe: Boolean,
        idempotent: Boolean,
        authoritativeMutation: Boolean,
        statefulExecutionContext: Boolean,
        rollbackSupported: Boolean = false,
        reason: String
    ): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("policyOwner", "riftos")
        .put("callerMayOverride", false)
        .put("kind", kind)
        .put("operation", operation)
        .put("retrySafe", retrySafe)
        .put("idempotent", idempotent)
        .put("authoritativeMutation", authoritativeMutation)
        .put("statefulExecutionContext", statefulExecutionContext)
        .put("rollbackSupported", rollbackSupported)
        .put("automaticRetryAllowed", false)
        .put("wholeJobReplayAllowed", false)
        .put("reason", reason)
}

private object RiftCliRecoveryPolicyHashes {
    fun sha256(value: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
