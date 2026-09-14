package com.riftos.app

import org.json.JSONArray
import org.json.JSONObject

/** Shared Android-side resolver for RiftOS virtual C:/D: volumes. */
object RiftVolumePaths {
    data class Volume(val letter: String, val label: String, val backing: String, val roots: LinkedHashMap<String, String>)

    val volumes: LinkedHashMap<String, Volume> = linkedMapOf(
        "C:" to Volume(
            letter = "C:",
            label = "RiftOS System",
            backing = "system/volumes/C",
            roots = linkedMapOf(
                "RiftOS" to "system/riftos",
                "Programs" to "system/programs",
                "ProgramData" to "system/program-data",
                "Toolchains" to "system/toolchains"
            )
        ),
        "D:" to Volume(
            letter = "D:",
            label = "User Data",
            backing = "system/volumes/D",
            roots = linkedMapOf(
                "Users" to "home/users",
                "Workspace" to "workspace",
                "Projects" to "home/projects",
                "Packages" to "documents/packages",
                "Builds" to "documents/builds",
                "Documents" to "documents",
                "Downloads" to "downloads",
                "Vault" to "documents/vault",
                "Temp" to "home/temp"
            )
        )
    )

    fun normalizeDisplay(raw: String): String {
        val value = raw.trim().replace('\\', '/')
        val parts = ArrayList<String>()
        for (part in value.split('/')) {
            if (part.isBlank() || part == ".") continue
            require(part != ".." && !part.contains('\u0000')) { "Invalid RiftFS path" }
            parts += if (parts.isEmpty() && part.matches(Regex("[A-Za-z]:"))) part.uppercase() else part
        }
        return "/" + parts.joinToString("/")
    }

    fun volume(raw: String): Volume? {
        val parts = normalizeDisplay(raw).split('/').filter { it.isNotBlank() }
        return parts.firstOrNull()?.uppercase()?.let(volumes::get)
    }

    fun isVolumeRoot(raw: String): Boolean {
        val parts = normalizeDisplay(raw).split('/').filter { it.isNotBlank() }
        return parts.size == 1 && volumes.containsKey(parts[0].uppercase())
    }

    fun resolveRelative(raw: String): String {
        val normalized = normalizeDisplay(raw)
        val parts = normalized.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        val volume = volumes[parts[0].uppercase()] ?: return parts.joinToString("/")
        if (parts.size == 1) return volume.backing
        val requestedRoot = parts[1]
        val root = volume.roots.entries.firstOrNull { it.key.equals(requestedRoot, ignoreCase = true) }
        return if (root != null) {
            listOf(root.value, *parts.drop(2).toTypedArray()).filter { it.isNotBlank() }.joinToString("/")
        } else {
            listOf(volume.backing, *parts.drop(1).toTypedArray()).filter { it.isNotBlank() }.joinToString("/")
        }
    }

    fun describe(): JSONArray = JSONArray().apply {
        for (volume in volumes.values) put(JSONObject()
            .put("letter", volume.letter)
            .put("label", volume.label)
            .put("path", "/${volume.letter}")
            .put("roots", JSONArray(volume.roots.keys.toList())))
    }
}
