package com.codynex.editorapp

import android.content.Context
import java.security.MessageDigest

data class BootstrapArtifacts(
    val vm1: ByteArray,
    val starterSource: String
)

object BootstrapArtifactLoader {
    private const val VM1_BYTES = 812
    private const val VM1_SHA256 =
        "7d7b33d2796ab2ddbca1519e00f254c2e6c8417af3ee9317ab45929a593b7df5"

    private val STARTER_SOURCE =
        """
        codynex 1;
        module app.main;

        fn main() -> u32 {
            return 0u32;
        }
        """.trimIndent() + "\n"

    fun load(context: Context): BootstrapArtifacts {
        val vmHex = readAsset(context, "vm1_seed.hex").trim()
        val vm = decodeCanonicalHex(vmHex)

        require(vm.size == VM1_BYTES) {
            "VM1 byte count drift: ${vm.size}"
        }
        require(sha256(vm) == VM1_SHA256) {
            "VM1 SHA-256 drift"
        }

        return BootstrapArtifacts(
            vm1 = vm,
            starterSource = STARTER_SOURCE
        )
    }

    private fun readAsset(context: Context, name: String): String =
        context.assets.open(name).bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }

    private fun decodeCanonicalHex(raw: String): ByteArray {
        require(raw.length % 2 == 0) { "hex artifact has odd length" }

        val output = ByteArray(raw.length / 2)
        var source = 0
        var target = 0

        while (source < raw.length) {
            val high = nibble(raw[source])
            val low = nibble(raw[source + 1])
            output[target] = ((high shl 4) or low).toByte()
            source += 2
            target += 1
        }

        return output
    }

    private fun nibble(value: Char): Int =
        when (value) {
            in '0'..'9' -> value.code - '0'.code
            in 'a'..'f' -> value.code - 'a'.code + 10
            else -> error("non-canonical hex character")
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
