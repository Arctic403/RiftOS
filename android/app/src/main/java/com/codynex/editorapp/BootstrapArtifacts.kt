package com.codynex.editorapp

import android.content.Context
import java.security.MessageDigest

data class BootstrapArtifacts(
    val vm1: ByteArray,
    val compilerA: ByteArray,
    val starterSource: String
)

object BootstrapArtifactLoader {
    private const val VM1_BYTES = 812
    private const val VM1_SHA256 =
        "7d7b33d2796ab2ddbca1519e00f254c2e6c8417af3ee9317ab45929a593b7df5"

    private const val COMPILER_BYTES = 292
    private const val COMPILER_SHA256 =
        "b00cc99ef0cf122d47cff54123e1e1ec19f83a44dfe949f5358428e45f47fb2e"

    private const val SOURCE_BYTES = 584
    private const val SOURCE_SHA256 =
        "a30e68e38600e25fc394c184b03c3e24f2775ffc2572c19a22426b3a0714581c"

    fun load(context: Context): BootstrapArtifacts {
        val vmHex = readAsset(context, "vm1_seed.hex").trim()
        val compilerHex =
            readAsset(context, "selfhost_compiler.hex").trim()
        val starterSource =
            readAsset(context, "selfhost_compiler.cx0")

        val vm = decodeCanonicalHex(vmHex)
        val compiler = decodeCanonicalHex(compilerHex)
        val sourceBytes = starterSource.toByteArray(Charsets.UTF_8)

        require(vm.size == VM1_BYTES) {
            "VM1 byte count drift: ${vm.size}"
        }
        require(sha256(vm) == VM1_SHA256) {
            "VM1 SHA-256 drift"
        }

        require(compiler.size == COMPILER_BYTES) {
            "compiler A byte count drift: ${compiler.size}"
        }
        require(sha256(compiler) == COMPILER_SHA256) {
            "compiler A SHA-256 drift"
        }

        require(sourceBytes.size == SOURCE_BYTES) {
            "Source0 byte count drift: ${sourceBytes.size}"
        }
        require(sha256(sourceBytes) == SOURCE_SHA256) {
            "Source0 SHA-256 drift"
        }

        return BootstrapArtifacts(
            vm1 = vm,
            compilerA = compiler,
            starterSource = starterSource
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
