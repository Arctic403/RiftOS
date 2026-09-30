package com.riftpp.editor

import android.content.Context
import java.io.ByteArrayOutputStream

object HexAssets {
    fun readContinuousHex(context: Context, path: String): ByteArray {
        val text = context.assets.open(path).bufferedReader().use { it.readText() }
        return decodeHex(text.filterNot { it.isWhitespace() })
    }

    fun readLineHex(context: Context, path: String): ByteArray {
        val out = ByteArrayOutputStream()
        context.assets.open(path).bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isNotEmpty()) out.write(decodeHex(line))
            }
        }
        return out.toByteArray()
    }

    private fun decodeHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val hi = hex[i].digitToIntOrNull(16) ?: error("invalid hex")
            val lo = hex[i + 1].digitToIntOrNull(16) ?: error("invalid hex")
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }
}
