package com.codynex.editorapp

import android.content.Context
import java.security.MessageDigest

data class BootstrapArtifacts(
    val vm1: ByteArray,
    val starterSource: String,
    val notepadSource: String
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

    private val NOTEPAD_SOURCE =
        """
        codynex 1;
        module app.notepad;

        fn write_title(cursor: u32) -> u32 {
            var out: u32 = cursor;
            out = sink_write(out, 67u8);
            out = sink_write(out, 111u8);
            out = sink_write(out, 100u8);
            out = sink_write(out, 121u8);
            out = sink_write(out, 110u8);
            out = sink_write(out, 101u8);
            out = sink_write(out, 120u8);
            out = sink_write(out, 32u8);
            out = sink_write(out, 78u8);
            out = sink_write(out, 111u8);
            out = sink_write(out, 116u8);
            out = sink_write(out, 101u8);
            out = sink_write(out, 112u8);
            out = sink_write(out, 97u8);
            out = sink_write(out, 100u8);
            return out;
        }

        fn write_clear(cursor: u32) -> u32 {
            var out: u32 = cursor;
            out = sink_write(out, 67u8);
            out = sink_write(out, 108u8);
            out = sink_write(out, 101u8);
            out = sink_write(out, 97u8);
            out = sink_write(out, 114u8);
            return out;
        }

        fn main() -> u32 {
            var out: u32 = 0u32;

            # CXUI v1 header + three generic controls.
            out = sink_write(out, 67u8);
            out = sink_write(out, 88u8);
            out = sink_write(out, 85u8);
            out = sink_write(out, 49u8);
            out = sink_write(out, 3u8);

            # Label: "Codynex Notepad".
            out = sink_write(out, 1u8);
            out = sink_write(out, 1u8);
            out = sink_write(out, 15u8);
            out = write_title(out);

            var text_length: u32 = 0u32;
            var text_start: u32 = 0u32;

            if source_len() < 3u32 {
                text_length = 0u32;
                text_start = 0u32;
            } else {
                let event: u8 = source_read(0u32);
                let control: u8 = source_read(1u32);
                let requested: u32 = source_read(2u32) as u32;
                let available: u32 = source_len() - 3u32;

                if event == 1u8 {
                    if control == 3u8 {
                        text_length = 0u32;
                        text_start = 0u32;
                    } else {
                        if available < requested {
                            text_length = available;
                        } else {
                            text_length = requested;
                        }
                        text_start = 3u32;
                    }
                } else {
                    if available < requested {
                        text_length = available;
                    } else {
                        text_length = requested;
                    }
                    text_start = 3u32;
                }
            }

            if 240u32 < text_length {
                text_length = 240u32;
            } else {
                text_length = text_length;
            }

            # Editable text-area node.
            out = sink_write(out, 2u8);
            out = sink_write(out, 2u8);
            out = sink_write(out, text_length as u8);

            var index: u32 = 0u32;
            while index < text_length {
                out = sink_write(out, source_read(text_start + index));
                index = index + 1u32;
            }

            # Clear action node.
            out = sink_write(out, 3u8);
            out = sink_write(out, 3u8);
            out = sink_write(out, 5u8);
            out = write_clear(out);

            return out;
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
            starterSource = STARTER_SOURCE,
            notepadSource = NOTEPAD_SOURCE
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
