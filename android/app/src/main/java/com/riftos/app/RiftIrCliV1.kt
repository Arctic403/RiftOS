package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Thin experimental CLI adapter for compiling Rift++ V0 into Rift IR V1. No execution authority. */
object RiftIrCliV1 {
    fun execute(context: Context, args: List<String>): JSONObject {
        val action = args.firstOrNull()?.trim()?.lowercase().orEmpty().ifBlank { "help" }
        return when (action) {
            "help" -> JSONObject()
                .put("schema", "rift.ir-command/1")
                .put("irSchema", RiftIrV1.SCHEMA)
                .put("profile", RiftIrV1.PROFILE)
                .put("experimental", true)
                .put("executable", false)
                .put("commands", JSONArray(listOf(
                    "rift-cli ir compile <workspace-script.riftpp>",
                    "rift-cli ir validate <workspace-script.riftpp>",
                    "rift-cli ir inspect <workspace-script.riftpp>"
                )))
                .put("note", "Rift IR V1 is an inspection-only core. This command does not invoke brains, tools, Local Agent, Git, builds or mutations.")
            "compile", "validate", "inspect" -> {
                require(args.size == 2) { "usage: rift-cli ir $action <workspace-script.riftpp>" }
                val swarmIr = RiftPlusPlusV0.compileWorkspace(context, args[1])
                val ir = RiftIrV1.lowerFromSwarmIr(swarmIr)
                when (action) {
                    "validate" -> RiftIrV1.validate(ir)
                    "inspect" -> RiftIrV1.inspect(ir)
                    else -> ir
                }
            }
            else -> throw IllegalArgumentException("unknown Rift IR V1 command: $action")
        }
    }
}
