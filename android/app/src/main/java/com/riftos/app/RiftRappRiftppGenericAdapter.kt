package com.riftos.app

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Generic Rift++ adapter for the complete riftos-app-abi/1 surface.
 *
 * RPE4 carries the ABI event without lossy remapping:
 * kind + target + arg0..arg3 + UTF-8 text + opaque bytes + program state.
 *
 * RWS4 carries next state + RUI3 frame + at most one generic host effect.
 * The host effect vocabulary belongs to RiftAppAbi, not to Rift++ editor/app
 * semantics.
 */
object RiftRappRiftppGenericAdapter :
    RiftAppRuntimeAdapter {
    override val id: String =
        "riftpp-generic-v1"

    override val presentation: String =
        "rui3"

    override fun supportsEventKind(
        kind: Int
    ): Boolean =
        kind in
            RiftAppAbi.EventKind.BOOT..
                RiftAppAbi.EventKind.HOST_EFFECT_RESULT

    private const val RPE4_HEADER_BYTES =
        52
    private const val RWS4_HEADER_BYTES =
        48
    private const val RUI3_HEADER_BYTES =
        16
    private const val RUI3_NODE_HEADER_BYTES =
        44

    private const val RPE4_MAGIC =
        0x34455052 // RPE4
    private const val RWS4_MAGIC =
        0x34535752 // RWS4
    private const val RUI3_MAGIC =
        0x33495552 // RUI3

    private const val MAX_PROGRAM_BYTES =
        1024 * 1024
    private const val MAX_STATE_BYTES =
        1024 * 1024
    private const val MAX_FRAME_BYTES =
        256 * 1024
    private const val MAX_EVENT_TEXT_BYTES =
        64 * 1024
    private const val MAX_EVENT_PAYLOAD_BYTES =
        256 * 1024
    private const val MAX_EFFECT_FIELD_BYTES =
        256 * 1024
    private const val MAX_NODES =
        256
    private const val MAX_NODE_TEXT_BYTES =
        16 * 1024

    override fun encodeEvent(
        payload: RiftAppAbi.RuntimePayload,
        event: RiftAppAbi.Event,
        eventSequence: Int
    ): ByteArray {
        require(
            payload.program.size in
                1..MAX_PROGRAM_BYTES
        ) {
            "Generic Rift++ program state is out of bounds"
        }
        require(eventSequence > 0) {
            "Generic Rift++ event sequence is invalid"
        }

        val textBytes =
            event.text.toByteArray(
                Charsets.UTF_8
            )
        require(
            textBytes.size <=
                MAX_EVENT_TEXT_BYTES
        ) {
            "Generic Rift++ event text exceeds bound"
        }
        require(
            event.bytes.size <=
                MAX_EVENT_PAYLOAD_BYTES
        ) {
            "Generic Rift++ event payload exceeds bound"
        }

        val paddedText =
            align4(
                textBytes.size
            )
        val paddedPayload =
            align4(
                event.bytes.size
            )

        return ByteBuffer
            .allocate(
                RPE4_HEADER_BYTES +
                    paddedText +
                    paddedPayload +
                    payload.program.size
            )
            .order(
                ByteOrder.LITTLE_ENDIAN
            )
            .apply {
                putInt(RPE4_MAGIC)
                putInt(payload.program.size)
                putInt(event.kind)
                putInt(eventSequence)
                putInt(event.targetId)
                putInt(event.arg0)
                putInt(event.arg1)
                putInt(event.arg2)
                putInt(event.arg3)
                putInt(textBytes.size)
                putInt(paddedText)
                putInt(event.bytes.size)
                putInt(paddedPayload)
                put(textBytes)
                repeat(
                    paddedText -
                        textBytes.size
                ) {
                    put(0)
                }
                put(event.bytes)
                repeat(
                    paddedPayload -
                        event.bytes.size
                ) {
                    put(0)
                }
                put(payload.program)
            }
            .array()
    }

    override fun decodeFrame(
        bytes: ByteArray
    ): RiftAppAbi.Frame =
        decodeOutput(
            bytes
        ).frame

    override fun nextProgram(
        bytes: ByteArray
    ): ByteArray =
        parseResponse(
            bytes
        ).state

    override fun decodeOutput(
        bytes: ByteArray
    ): RiftAppAbi.RuntimeOutput {
        val response =
            parseResponse(
                bytes
            )

        return RiftAppAbi.RuntimeOutput(
            frame =
                parseFrame(
                    response.frame
                ),
            nextProgram =
                response.state,
            effects =
                response.effect
                    ?.let {
                        listOf(it)
                    }
                    .orEmpty()
        )
    }

    private data class Response(
        val state: ByteArray,
        val frame: ByteArray,
        val effect: RiftAppAbi.HostEffect?
    )

    private fun parseResponse(
        bytes: ByteArray
    ): Response {
        require(
            bytes.size >=
                RWS4_HEADER_BYTES
        ) {
            "Generic Rift++ response is truncated"
        }
        require(
            readI32(
                bytes,
                0
            ) ==
                RWS4_MAGIC
        ) {
            "Generic Rift++ response magic is invalid"
        }

        val totalBytes =
            readI32(bytes, 4)
        val stateBytes =
            readI32(bytes, 8)
        val frameBytes =
            readI32(bytes, 12)
        val effectCount =
            readI32(bytes, 16)
        val effectRequestId =
            readI32(bytes, 20)
        val effectToken =
            readI32(bytes, 24)
        val capabilityBytes =
            readI32(bytes, 28)
        val operationBytes =
            readI32(bytes, 32)
        val textBytes =
            readI32(bytes, 36)
        val payloadBytes =
            readI32(bytes, 40)
        val reserved =
            readI32(bytes, 44)

        require(totalBytes == bytes.size) {
            "Generic Rift++ response length mismatch"
        }
        require(
            stateBytes in
                1..MAX_STATE_BYTES
        ) {
            "Generic Rift++ state size is out of bounds"
        }
        require(
            frameBytes in
                RUI3_HEADER_BYTES..
                    MAX_FRAME_BYTES
        ) {
            "Generic Rift++ frame size is out of bounds"
        }
        require(
            effectCount in
                0..1
        ) {
            "Generic Rift++ effect count is invalid"
        }
        require(
            capabilityBytes in
                0..128 &&
                operationBytes in
                    0..128 &&
                textBytes in
                    0..MAX_EFFECT_FIELD_BYTES &&
                payloadBytes in
                    0..MAX_EFFECT_FIELD_BYTES
        ) {
            "Generic Rift++ effect field is out of bounds"
        }
        require(reserved == 0) {
            "Generic Rift++ response reserved field is non-zero"
        }

        val capabilityPadded =
            align4(
                capabilityBytes
            )
        val operationPadded =
            align4(
                operationBytes
            )
        val textPadded =
            align4(
                textBytes
            )
        val payloadPadded =
            align4(
                payloadBytes
            )

        val expected =
            RWS4_HEADER_BYTES +
                stateBytes +
                frameBytes +
                capabilityPadded +
                operationPadded +
                textPadded +
                payloadPadded

        require(expected == bytes.size) {
            "Generic Rift++ response payload mismatch"
        }

        val stateStart =
            RWS4_HEADER_BYTES
        val frameStart =
            stateStart +
                stateBytes
        var cursor =
            frameStart +
                frameBytes

        val capability =
            utf8(
                bytes,
                cursor,
                capabilityBytes
            )
        cursor +=
            capabilityPadded

        val operation =
            utf8(
                bytes,
                cursor,
                operationBytes
            )
        cursor +=
            operationPadded

        val text =
            utf8(
                bytes,
                cursor,
                textBytes
            )
        cursor +=
            textPadded

        val payload =
            bytes.copyOfRange(
                cursor,
                cursor +
                    payloadBytes
            )

        val effect =
            if (
                effectCount ==
                    0
            ) {
                require(
                    effectRequestId == 0 &&
                        effectToken == 0 &&
                        capabilityBytes == 0 &&
                        operationBytes == 0 &&
                        textBytes == 0 &&
                        payloadBytes == 0
                ) {
                    "Generic Rift++ empty effect is malformed"
                }
                null
            } else {
                require(
                    effectRequestId >
                        0
                ) {
                    "Generic Rift++ effect request id is invalid"
                }
                RiftAppAbi.HostEffect(
                    requestId =
                        effectRequestId,
                    capability =
                        capability,
                    operation =
                        operation,
                    token =
                        effectToken,
                    text =
                        text,
                    bytes =
                        payload
                )
            }

        return Response(
            state =
                bytes.copyOfRange(
                    stateStart,
                    frameStart
                ),
            frame =
                bytes.copyOfRange(
                    frameStart,
                    frameStart +
                        frameBytes
                ),
            effect =
                effect
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
                RUI3_MAGIC
        ) {
            "RUI3 frame magic is invalid"
        }

        val totalBytes =
            readI32(
                bytes,
                4
            )
        val layout =
            readI32(
                bytes,
                8
            )
        val nodeCount =
            readI32(
                bytes,
                12
            )

        require(totalBytes == bytes.size) {
            "RUI3 frame length mismatch"
        }
        require(
            layout ==
                RiftAppAbi.Layout.FLOW_COLUMN ||
                layout ==
                    RiftAppAbi.Layout.ABSOLUTE
        ) {
            "RUI3 layout is unsupported"
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
                kind in
                    RiftAppAbi.NodeKind.ROOT..
                        RiftAppAbi.NodeKind.IMAGE
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
                utf8(
                    bytes,
                    cursor,
                    textBytes
                )

            cursor +=
                paddedText

            nodes.add(
                RiftAppAbi.Node(
                    kind = kind,
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
                    text =
                        text
                )
            )
        }

        require(cursor == bytes.size) {
            "RUI3 frame has trailing bytes"
        }

        return RiftAppAbi.Frame(
            layout =
                layout,
            nodes =
                nodes
        )
    }

    private fun utf8(
        bytes: ByteArray,
        offset: Int,
        length: Int
    ): String {
        require(
            offset >= 0 &&
                length >= 0 &&
                offset +
                    length <=
                    bytes.size
        ) {
            "Generic Rift++ UTF-8 field is truncated"
        }

        val field =
            bytes.copyOfRange(
                offset,
                offset +
                    length
            )
        val value =
            field.toString(
                Charsets.UTF_8
            )

        require(
            value.toByteArray(
                Charsets.UTF_8
            ).contentEquals(
                field
            )
        ) {
            "Generic Rift++ UTF-8 field is invalid"
        }

        return value
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
            "Generic Rift++ int32 is truncated"
        }

        return (
            bytes[offset]
                .toInt() and 0xff
            ) or
            (
                (
                    bytes[offset + 1]
                        .toInt() and 0xff
                    ) shl 8
                ) or
            (
                (
                    bytes[offset + 2]
                        .toInt() and 0xff
                    ) shl 16
                ) or
            (
                (
                    bytes[offset + 3]
                        .toInt() and 0xff
                    ) shl 24
                )
    }

    private fun align4(
        value: Int
    ): Int {
        require(value >= 0) {
            "Generic Rift++ alignment input is invalid"
        }
        return (
            value +
                3
            ) and
            3.inv()
    }
}
