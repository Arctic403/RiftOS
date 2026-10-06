package com.riftos.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Generic ABSOLUTE renderer for riftos-app-abi/1.
 *
 * It renders bounded primitive nodes and transports raw logical pointer input.
 * App/runtime semantics remain entirely behind the runtime adapter.
 */
class RiftRappAbsoluteView(
    context: Context,
    initialFrame: RiftAppAbi.Frame,
    private val eventSink:
        (RiftAppAbi.Event) -> Unit
) : View(context) {
    private var frame =
        requireAbsolute(
            initialFrame
        )

    private val surfacePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                Color.rgb(
                    49,
                    53,
                    61
                )
            style =
                Paint.Style.FILL
        }

    private val rootPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                Color.rgb(
                    20,
                    22,
                    26
                )
            style =
                Paint.Style.FILL
        }

    private val textPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                Color.WHITE
            textSize =
                30f
            typeface =
                Typeface.MONOSPACE
        }

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(
            Color.BLACK
        )
    }

    fun updateFrame(
        next: RiftAppAbi.Frame
    ) {
        frame =
            requireAbsolute(
                next
            )
        invalidate()
    }

    override fun onDraw(
        canvas: Canvas
    ) {
        super.onDraw(
            canvas
        )

        val root =
            rootNode()
        if (
            width <= 0 ||
            height <= 0 ||
            root.width <= 0 ||
            root.height <= 0
        ) {
            return
        }

        val sx =
            width.toFloat() /
                root.width.toFloat()
        val sy =
            height.toFloat() /
                root.height.toFloat()

        canvas.save()
        canvas.scale(
            sx,
            sy
        )

        frame.nodes
            .sortedBy {
                it.z
            }
            .forEach {
                node ->
                when (
                    node.kind
                ) {
                    RiftAppAbi.NodeKind.ROOT -> {
                        canvas.drawRect(
                            0f,
                            0f,
                            node.width.toFloat(),
                            node.height.toFloat(),
                            rootPaint
                        )
                    }

                    RiftAppAbi.NodeKind.SURFACE -> {
                        if (
                            node.width > 0 &&
                            node.height > 0
                        ) {
                            canvas.drawRect(
                                node.x.toFloat(),
                                node.y.toFloat(),
                                (
                                    node.x +
                                        node.width
                                ).toFloat(),
                                (
                                    node.y +
                                        node.height
                                ).toFloat(),
                                surfacePaint
                            )
                        }
                    }

                    RiftAppAbi.NodeKind.TEXT -> {
                        if (
                            node.text.isNotEmpty() &&
                            node.width > 0 &&
                            node.height > 0
                        ) {
                            textPaint.textSize =
                                max(
                                    18f,
                                    minOf(
                                        30f,
                                        node.height
                                            .toFloat() *
                                            0.72f
                                    )
                                )

                            canvas.drawText(
                                node.text,
                                node.x.toFloat(),
                                (
                                    node.y +
                                        textPaint.textSize
                                ),
                                textPaint
                            )
                        }
                    }
                }
            }

        canvas.restore()
    }

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {
        val root =
            rootNode()
        if (
            width <= 0 ||
            height <= 0 ||
            root.width <= 0 ||
            root.height <= 0
        ) {
            return false
        }

        val kind =
            when (
                event.actionMasked
            ) {
                MotionEvent.ACTION_DOWN ->
                    RiftAppAbi.EventKind.POINTER_DOWN

                MotionEvent.ACTION_MOVE ->
                    RiftAppAbi.EventKind.POINTER_MOVE

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL ->
                    RiftAppAbi.EventKind.POINTER_UP

                else ->
                    return true
            }

        val logicalX =
            (
                event.x /
                    width.toFloat() *
                    root.width.toFloat()
            )
                .roundToInt()
                .coerceIn(
                    0,
                    root.width
                )

        val logicalY =
            (
                event.y /
                    height.toFloat() *
                    root.height.toFloat()
            )
                .roundToInt()
                .coerceIn(
                    0,
                    root.height
                )

        eventSink(
            RiftAppAbi.Event(
                kind = kind,
                arg0 = logicalX,
                arg1 = logicalY
            )
        )

        if (
            event.actionMasked ==
                MotionEvent.ACTION_UP
        ) {
            performClick()
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun rootNode():
        RiftAppAbi.Node =
        frame.nodes
            .single {
                it.kind ==
                    RiftAppAbi.NodeKind.ROOT
            }

    private fun requireAbsolute(
        value: RiftAppAbi.Frame
    ): RiftAppAbi.Frame {
        require(
            value.layout ==
                RiftAppAbi.Layout.ABSOLUTE
        ) {
            "RAPP absolute view requires ABSOLUTE layout"
        }

        val roots =
            value.nodes.filter {
                it.kind ==
                    RiftAppAbi.NodeKind.ROOT
            }

        require(
            roots.size == 1 &&
                roots[0].width > 0 &&
                roots[0].height > 0
        ) {
            "RAPP absolute frame requires one bounded root"
        }

        return value
    }
}
