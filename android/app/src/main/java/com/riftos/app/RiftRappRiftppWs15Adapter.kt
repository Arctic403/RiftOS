package com.riftos.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stateful Rift++ desktop adapter for WS15-compatible RPE3/RWS2/RUI3.
 *
 * Rift-Os semantics remain inside the native runtime. This adapter only
 * translates the generic RiftOS app ABI to bounded transport bytes.
 */
object RiftRappRiftppWs15Adapter :
    RiftAppRuntimeAdapter {
    override val id: String =
        "riftpp-rws2-rui3-v1"

    override val presentation: String =
        "rui3"

    private const val RPE3_HEADER_BYTES =
        40
    private const val RWS2_HEADER_BYTES =
        20
    private const val RUI3_HEADER_BYTES =
        16
    private const val RUI3_NODE_HEADER_BYTES =
        44

    private const val MAX_PROGRAM_BYTES =
        1024 * 1024
    private const val MAX_STATE_BYTES =
        1024 * 1024
    private const val MAX_FRAME_BYTES =
        256 * 1024
    private const val MAX_TEXT_BYTES =
        4096
    private const val MAX_NODES =
        256
    private const val MAX_NODE_TEXT_BYTES =
        16 * 1024

    override fun encodeEvent(
        payload: RiftAppAbi.RuntimePayload,
        event: RiftAppAbi.Event,
        eventSequence: Int
    ): ByteArray {
        val eventKind =
            when (
                event.kind
            ) {
                RiftAppAbi.EventKind.BOOT ->
                    0

                RiftAppAbi.EventKind.POINTER_DOWN ->
                    1

                RiftAppAbi.EventKind.POINTER_MOVE ->
                    2

                RiftAppAbi.EventKind.POINTER_UP ->
                    3

                else ->
                    error(
                        "WS15 adapter does not support RiftOS event kind " +
                            event.kind
                    )
            }

        require(
            payload.program.size in
                1..MAX_PROGRAM_BYTES
        ) {
            "WS15 program state is out of bounds"
        }

        require(
            eventSequence > 0
        ) {
            "WS15 event sequence is invalid"
        }

        val textBytes =
            event.text.toByteArray(
                Charsets.UTF_8
            )
        require(
            textBytes.size <=
                MAX_TEXT_BYTES
        ) {
            "WS15 event text exceeds bound"
        }

        val paddedText =
            align4(
                textBytes.size
            )

        return ByteBuffer
            .allocate(
                RPE3_HEADER_BYTES +
                    paddedText +
                    payload.program.size
            )
            .order(
                ByteOrder.LITTLE_ENDIAN
            )
            .apply {
                put(
                    "RPE3".toByteArray(
                        Charsets.US_ASCII
                    )
                )
                putInt(
                    payload.program.size
                )
                putInt(
                    eventKind
                )
                putInt(
                    eventSequence
                )
                putInt(
                    event.arg0
                )
                putInt(
                    event.arg1
                )
                putInt(
                    event.arg2
                )
                putInt(
                    event.arg3
                )
                putInt(
                    textBytes.size
                )
                putInt(
                    paddedText
                )
                put(
                    textBytes
                )
                repeat(
                    paddedText -
                        textBytes.size
                ) {
                    put(0)
                }
                put(
                    payload.program
                )
            }
            .array()
    }

    override fun decodeFrame(
        bytes: ByteArray
    ): RiftAppAbi.Frame {
        val response =
            parseResponse(
                bytes
            )

        return parseFrame(
            response.frame
        )
    }

    override fun nextProgram(
        bytes: ByteArray
    ): ByteArray =
        parseResponse(
            bytes
        ).state

    private data class Response(
        val state: ByteArray,
        val frame: ByteArray
    )

    private fun parseResponse(
        bytes: ByteArray
    ): Response {
        require(
            bytes.size >=
                RWS2_HEADER_BYTES
        ) {
            "WS15 response is truncated"
        }
        require(
            readI32(
                bytes,
                0
            ) ==
                0x32535752
        ) {
            "WS15 response magic is invalid"
        }

        val totalBytes =
            readI32(
                bytes,
                4
            )
        val stateBytes =
            readI32(
                bytes,
                8
            )
        val frameBytes =
            readI32(
                bytes,
                12
            )

        require(
            totalBytes ==
                bytes.size
        ) {
            "WS15 response length mismatch"
        }
        require(
            stateBytes in
                1..MAX_STATE_BYTES
        ) {
            "WS15 state size is out of bounds"
        }
        require(
            frameBytes in
                RUI3_HEADER_BYTES..
                    MAX_FRAME_BYTES
        ) {
            "WS15 frame size is out of bounds"
        }
        require(
            RWS2_HEADER_BYTES +
                stateBytes +
                frameBytes ==
                bytes.size
        ) {
            "WS15 response payload mismatch"
        }

        val stateStart =
            RWS2_HEADER_BYTES
        val frameStart =
            stateStart +
                stateBytes

        return Response(
            state =
                bytes.copyOfRange(
                    stateStart,
                    frameStart
                ),
            frame =
                bytes.copyOfRange(
                    frameStart,
                    bytes.size
                )
        )
    }

    private fun parseFrame(
        bytes: ByteArray
    ): RiftAppAbi.Frame {
        require(
            bytes.size in
                RUI3_HEADER_BYTES..
                    MAX_FRAME_BYTES
        ) {
            "RUI3 frame is out of bounds"
        }
        require(
            readI32(
                bytes,
                0
            ) ==
                0x33495552
        ) {
            "RUI3 frame magic is invalid"
        }

        val totalBytes =
            readI32(
                bytes,
                4
            )
        val nodeCount =
            readI32(
                bytes,
                12
            )

        require(
            totalBytes ==
                bytes.size
        ) {
            "RUI3 frame length mismatch"
        }
        require(
            nodeCount in
                1..MAX_NODES
        ) {
            "RUI3 node count is out of bounds"
        }

        var cursor =
            RUI3_HEADER_BYTES
        val ids =
            HashSet<Int>()
        val nodes =
            ArrayList<RiftAppAbi.Node>(
                nodeCount
            )

        repeat(
            nodeCount
        ) {
            require(
                cursor +
                    RUI3_NODE_HEADER_BYTES <=
                    bytes.size
            ) {
                "RUI3 node is truncated"
            }

            val kind =
                readI32(
                    bytes,
                    cursor
                )
            val id =
                readI32(
                    bytes,
                    cursor + 4
                )
            val parentId =
                readI32(
                    bytes,
                    cursor + 8
                )
            val x =
                readI32(
                    bytes,
                    cursor + 12
                )
            val y =
                readI32(
                    bytes,
                    cursor + 16
                )
            val width =
                readI32(
                    bytes,
                    cursor + 20
                )
            val height =
                readI32(
                    bytes,
                    cursor + 24
                )
            val z =
                readI32(
                    bytes,
                    cursor + 28
                )
            val flags =
                readI32(
                    bytes,
                    cursor + 32
                )
            val textBytes =
                readI32(
                    bytes,
                    cursor + 36
                )
            val paddedText =
                readI32(
                    bytes,
                    cursor + 40
                )

            cursor +=
                RUI3_NODE_HEADER_BYTES

            require(
                kind in 1..3
            ) {
                "RUI3 primitive is unsupported"
            }
            require(
                id > 0 &&
                    ids.add(id)
            ) {
                "RUI3 node id is invalid"
            }
            require(
                parentId >= 0 &&
                    parentId != id
            ) {
                "RUI3 parent id is invalid"
            }
            require(
                width >= 0 &&
                    height >= 0
            ) {
                "RUI3 node geometry is invalid"
            }
            require(
                textBytes in
                    0..MAX_NODE_TEXT_BYTES
            ) {
                "RUI3 text length is out of bounds"
            }
            require(
                paddedText >=
                    textBytes &&
                    paddedText <=
                        MAX_NODE_TEXT_BYTES &&
                    paddedText %
                        4 ==
                        0
            ) {
                "RUI3 padded text is invalid"
            }
            require(
                cursor +
                    paddedText <=
                    bytes.size
            ) {
                "RUI3 text payload is truncated"
            }

            val text =
                bytes.copyOfRange(
                    cursor,
                    cursor +
                        textBytes
                )
                    .toString(
                        Charsets.UTF_8
                    )

            cursor +=
                paddedText

            nodes.add(
                RiftAppAbi.Node(
                    kind =
                        when (
                            kind
                        ) {
                            1 ->
                                RiftAppAbi.NodeKind.ROOT

                            2 ->
                                RiftAppAbi.NodeKind.SURFACE

                            else ->
                                RiftAppAbi.NodeKind.TEXT
                        },
                    id = id,
                    parentId =
                        parentId,
                    x = x,
                    y = y,
                    width =
                        width,
                    height =
                        height,
                    z = z,
                    flags =
                        flags,
                    text = text
                )
            )
        }

        require(
            cursor ==
                bytes.size
        ) {
            "RUI3 frame has trailing bytes"
        }

        val roots =
            nodes.filter {
                it.kind ==
                    RiftAppAbi.NodeKind.ROOT
            }

        require(
            roots.size == 1 &&
                roots[0].parentId ==
                    0
        ) {
            "RUI3 frame requires one root"
        }

        val allIds =
            nodes.mapTo(
                HashSet()
            ) {
                it.id
            }

        nodes.forEach {
            node ->
            require(
                node.parentId ==
                    0 ||
                    allIds.contains(
                        node.parentId
                    )
            ) {
                "RUI3 node parent is missing"
            }
        }

        return RiftAppAbi.Frame(
            layout =
                RiftAppAbi.Layout.ABSOLUTE,
            nodes =
                nodes
        )
    }

    private fun readI32(
        bytes: ByteArray,
        offset: Int
    ): Int {
        require(
            offset >= 0 &&
                offset + 4 <=
                    bytes.size
        ) {
            "transport int32 is truncated"
        }

        return (
            bytes[offset]
                .toInt() and
                0xff
            ) or
            (
                (
                    bytes[
                        offset + 1
                    ].toInt() and
                        0xff
                ) shl 8
            ) or
            (
                (
                    bytes[
                        offset + 2
                    ].toInt() and
                        0xff
                ) shl 16
            ) or
            (
                (
                    bytes[
                        offset + 3
                    ].toInt() and
                        0xff
                ) shl 24
            )
    }

    private fun align4(
        value: Int
    ): Int =
        (
            value +
                3
        ) and
            -4
}
