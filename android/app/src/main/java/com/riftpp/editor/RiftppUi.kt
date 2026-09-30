package com.riftpp.editor

data class RiftppUiNode(
    val kind: Int,
    val id: Int,
    val text: String
)

data class RiftppUiFrame(
    val nodes: List<RiftppUiNode>
)

object RiftppUiCodec {
    private const val MAX_FRAME_BYTES = 64 * 1024
    private const val MAX_NODES = 16
    private const val MAX_TEXT_BYTES = 4096
    private const val MAGIC = 0x32495552 // RUI2 little-endian

    fun parse(bytes: ByteArray): RiftppUiFrame {
        require(bytes.size in 8..MAX_FRAME_BYTES) {
            "RUI2 frame size is out of bounds"
        }
        require(readU32(bytes, 0) == MAGIC) {
            "unsupported Rift++ UI frame"
        }

        val count = readU32(bytes, 4)
        require(count in 1..MAX_NODES) {
            "RUI2 node count is out of bounds"
        }

        var cursor = 8
        val ids = HashSet<Int>()
        val nodes = ArrayList<RiftppUiNode>(count)

        repeat(count) {
            require(cursor + 16 <= bytes.size) {
                "truncated RUI2 node"
            }

            val kind = readU32(bytes, cursor)
            val id = readU32(bytes, cursor + 4)
            val length = readU32(bytes, cursor + 8)
            val padded = readU32(bytes, cursor + 12)
            cursor += 16

            require(kind in 1..3) {
                "unsupported RUI2 primitive"
            }
            require(id > 0 && ids.add(id)) {
                "invalid or duplicate RUI2 control id"
            }
            require(length in 0..MAX_TEXT_BYTES) {
                "RUI2 text length is out of bounds"
            }
            require(
                padded >= length &&
                    padded <= MAX_TEXT_BYTES &&
                    padded % 4 == 0
            ) {
                "RUI2 padded text length is invalid"
            }
            require(cursor + padded <= bytes.size) {
                "truncated RUI2 text"
            }

            val text =
                bytes.copyOfRange(cursor, cursor + length)
                    .toString(Charsets.UTF_8)
            cursor += padded

            nodes.add(
                RiftppUiNode(
                    kind = kind,
                    id = id,
                    text = text
                )
            )
        }

        require(cursor == bytes.size) {
            "RUI2 frame has trailing bytes"
        }
        require(nodes.count { it.kind == 2 } <= 1) {
            "App v2 supports at most one textarea"
        }

        return RiftppUiFrame(nodes)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 4 <= bytes.size) {
            "truncated RUI2 uint32"
        }

        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    }
}
