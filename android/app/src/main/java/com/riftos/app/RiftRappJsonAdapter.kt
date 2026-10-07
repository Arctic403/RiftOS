package com.riftos.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON transport adapter for runtimes that prefer a structured envelope over
 * the raw Rift++ binary protocols. It is intentionally language-neutral.
 */
object RiftRappJsonAdapter :
    RiftAppRuntimeAdapter {
    override val id: String =
        "json-generic-v1"

    override val presentation: String =
        "json-frame-v1"

    override val executorKind: String =
        RiftAppExecutionKind.QUICKJS

    private const val EVENT_SCHEMA =
        "riftos-app-event-json/1"
    private const val OUTPUT_SCHEMA =
        "riftos-app-output-json/1"

    override fun supportsEventKind(
        kind: Int
    ): Boolean =
        kind in
            RiftAppAbi.EventKind.BOOT..
                RiftAppAbi.EventKind.HOST_EFFECT_RESULT

    override fun encodeEvent(
        payload: RiftAppAbi.RuntimePayload,
        event: RiftAppAbi.Event,
        eventSequence: Int
    ): ByteArray {
        require(eventSequence > 0) {
            "JSON RAPP event sequence is invalid"
        }

        return JSONObject()
            .put(
                "schema",
                EVENT_SCHEMA
            )
            .put(
                "sequence",
                eventSequence
            )
            .put(
                "stateBase64",
                encode(
                    payload.program
                )
            )
            .put(
                "event",
                JSONObject()
                    .put(
                        "kind",
                        event.kind
                    )
                    .put(
                        "targetId",
                        event.targetId
                    )
                    .put(
                        "arg0",
                        event.arg0
                    )
                    .put(
                        "arg1",
                        event.arg1
                    )
                    .put(
                        "arg2",
                        event.arg2
                    )
                    .put(
                        "arg3",
                        event.arg3
                    )
                    .put(
                        "text",
                        event.text
                    )
                    .put(
                        "bytesBase64",
                        encode(
                            event.bytes
                        )
                    )
            )
            .toString()
            .toByteArray(
                Charsets.UTF_8
            )
    }

    override fun decodeFrame(
        bytes: ByteArray
    ): RiftAppAbi.Frame =
        decodeOutput(
            bytes
        )
            .frame

    override fun nextProgram(
        bytes: ByteArray
    ): ByteArray? =
        decodeOutput(
            bytes
        )
            .nextProgram

    override fun decodeOutput(
        bytes: ByteArray
    ): RiftAppAbi.RuntimeOutput {
        val root =
            JSONObject(
                bytes.toString(
                    Charsets.UTF_8
                )
            )

        require(
            root.optString(
                "schema"
            ) ==
                OUTPUT_SCHEMA
        ) {
            "JSON RAPP output schema is invalid"
        }

        val state =
            root
                .optString(
                    "stateBase64"
                )
                .takeIf {
                    it.isNotBlank()
                }
                ?.let(
                    ::decode
                )

        val frame =
            parseFrame(
                root.getJSONObject(
                    "frame"
                )
            )

        val effect =
            root
                .optJSONObject(
                    "effect"
                )
                ?.let(
                    ::parseEffect
                )

        return RiftAppAbi.RuntimeOutput(
            frame =
                frame,
            nextProgram =
                state,
            effects =
                effect
                    ?.let {
                        listOf(it)
                    }
                    .orEmpty()
        )
    }

    private fun parseFrame(
        value: JSONObject
    ): RiftAppAbi.Frame {
        val nodesJson =
            value.getJSONArray(
                "nodes"
            )
        require(
            nodesJson.length() in
                1..256
        ) {
            "JSON RAPP node count is out of bounds"
        }

        val nodes =
            ArrayList<RiftAppAbi.Node>(
                nodesJson.length()
            )

        for (
            index in
                0 until
                    nodesJson.length()
        ) {
            val node =
                nodesJson
                    .getJSONObject(
                        index
                    )

            nodes +=
                RiftAppAbi.Node(
                    kind =
                        node.getInt(
                            "kind"
                        ),
                    id =
                        node.getInt(
                            "id"
                        ),
                    parentId =
                        node.optInt(
                            "parentId",
                            0
                        ),
                    x =
                        node.optInt(
                            "x",
                            0
                        ),
                    y =
                        node.optInt(
                            "y",
                            0
                        ),
                    width =
                        node.optInt(
                            "width",
                            0
                        ),
                    height =
                        node.optInt(
                            "height",
                            0
                        ),
                    z =
                        node.optInt(
                            "z",
                            0
                        ),
                    flags =
                        node.optInt(
                            "flags",
                            0
                        ),
                    text =
                        node.optString(
                            "text"
                        )
                )
        }

        return RiftAppAbi.Frame(
            layout =
                value.optInt(
                    "layout",
                    RiftAppAbi.Layout.FLOW_COLUMN
                ),
            nodes =
                nodes
        )
    }

    private fun parseEffect(
        value: JSONObject
    ): RiftAppAbi.HostEffect =
        RiftAppAbi.HostEffect(
            requestId =
                value.getInt(
                    "requestId"
                ),
            capability =
                value.getString(
                    "capability"
                ),
            operation =
                value.getString(
                    "operation"
                ),
            token =
                value.optInt(
                    "token",
                    0
                ),
            text =
                value.optString(
                    "text"
                ),
            bytes =
                value
                    .optString(
                        "bytesBase64"
                    )
                    .takeIf {
                        it.isNotBlank()
                    }
                    ?.let(
                        ::decode
                    )
                    ?: ByteArray(0)
        )

    private fun encode(
        bytes: ByteArray
    ): String =
        if (bytes.isEmpty()) {
            ""
        } else {
            Base64.encodeToString(
                bytes,
                Base64.NO_WRAP
            )
        }

    private fun decode(
        value: String
    ): ByteArray =
        Base64.decode(
            value,
            Base64.DEFAULT
        )
}
