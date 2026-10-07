package com.riftos.app

import com.riftpp.editor.RiftppUiCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Compatibility adapter for the existing Rift++ RPA2/RPE2/RUI2 application
 * protocol. RiftRappHost must stay unaware of these protocol details.
 */
object RiftRappRiftppAdapter : RiftAppRuntimeAdapter {
    override val id: String = "riftpp-rpa2-v1"
    override val presentation: String = "rui2"

    override fun supportsEventKind(
        kind: Int
    ): Boolean =
        kind == RiftAppAbi.EventKind.BOOT ||
            kind == RiftAppAbi.EventKind.ACTION

    override fun encodeEvent(
        payload: RiftAppAbi.RuntimePayload,
        event: RiftAppAbi.Event,
        eventSequence: Int
    ): ByteArray {
        val legacyKind =
            when (event.kind) {
                RiftAppAbi.EventKind.BOOT -> 0
                RiftAppAbi.EventKind.ACTION -> 1
                else -> error(
                    "Rift++ RPA2 adapter does not support RiftOS event kind ${event.kind}"
                )
            }

        return ByteBuffer
            .allocate(16 + payload.program.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("RPE2".toByteArray(Charsets.US_ASCII))
            .putInt(payload.program.size)
            .putInt(legacyKind)
            .putInt(event.targetId)
            .put(payload.program)
            .array()
    }

    override fun decodeFrame(bytes: ByteArray): RiftAppAbi.Frame {
        val legacy = RiftppUiCodec.parse(bytes)
        val nodes =
            legacy.nodes.map { node ->
                RiftAppAbi.Node(
                    kind =
                        when (node.kind) {
                            1 -> RiftAppAbi.NodeKind.TEXT
                            2 -> RiftAppAbi.NodeKind.TEXT_INPUT
                            3 -> RiftAppAbi.NodeKind.ACTION
                            else -> error(
                                "Unsupported Rift++ RUI2 node kind ${node.kind}"
                            )
                        },
                    id = node.id,
                    text = node.text
                )
            }

        return RiftAppAbi.Frame(
            layout = RiftAppAbi.Layout.FLOW_COLUMN,
            nodes = nodes
        )
    }
}
