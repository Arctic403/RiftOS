package com.riftos.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Generic ABSOLUTE renderer for riftos-app-abi/1.
 *
 * Primitive painting, editable fields, actions and raw input are all expressed
 * only in RiftAppAbi terms. App/runtime semantics remain behind the adapter.
 */
class RiftRappAbsoluteView(
    context: Context,
    initialFrame: RiftAppAbi.Frame,
    private val supportsEvent:
        (Int) -> Boolean,
    private val eventSink:
        (RiftAppAbi.Event) -> Unit
) : ViewGroup(context) {
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

    private val imagePaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color =
                Color.rgb(
                    37,
                    42,
                    50
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

    private val inputViews =
        LinkedHashMap<Int, EditText>()

    private val actionViews =
        LinkedHashMap<Int, Button>()

    private val suppressInput =
        HashSet<Int>()

    init {
        setWillNotDraw(
            false
        )
        isClickable =
            true
        isFocusable =
            true
        isFocusableInTouchMode =
            true
        setBackgroundColor(
            Color.BLACK
        )
        reconcileWidgets()
    }

    fun updateFrame(
        next: RiftAppAbi.Frame
    ) {
        frame =
            requireAbsolute(
                next
            )
        reconcileWidgets()
        requestLayout()
        invalidate()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int
    ) {
        val measuredWidth =
            resolveSize(
                suggestedMinimumWidth,
                widthMeasureSpec
            )
        val measuredHeight =
            resolveSize(
                suggestedMinimumHeight,
                heightMeasureSpec
            )

        setMeasuredDimension(
            measuredWidth,
            measuredHeight
        )

        val root =
            rootNode()
        val sx =
            if (
                root.width >
                    0
            ) {
                measuredWidth.toFloat() /
                    root.width.toFloat()
            } else {
                1f
            }
        val sy =
            if (
                root.height >
                    0
            ) {
                measuredHeight.toFloat() /
                    root.height.toFloat()
            } else {
                1f
            }

        for (
            index in
                0 until childCount
        ) {
            val child =
                getChildAt(
                    index
                )
            val id =
                child.tag as? Int
                    ?: continue
            val node =
                frame.nodes
                    .firstOrNull {
                        it.id ==
                            id
                    }
                    ?: continue

            child.measure(
                MeasureSpec.makeMeasureSpec(
                    max(
                        0,
                        (
                            node.width *
                                sx
                            )
                            .roundToInt()
                    ),
                    MeasureSpec.EXACTLY
                ),
                MeasureSpec.makeMeasureSpec(
                    max(
                        0,
                        (
                            node.height *
                                sy
                            )
                            .roundToInt()
                    ),
                    MeasureSpec.EXACTLY
                )
            )
        }
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val root =
            rootNode()
        if (
            root.width <=
                0 ||
            root.height <=
                0
        ) {
            return
        }

        val sx =
            width.toFloat() /
                root.width.toFloat()
        val sy =
            height.toFloat() /
                root.height.toFloat()

        for (
            index in
                0 until childCount
        ) {
            val child =
                getChildAt(
                    index
                )
            val id =
                child.tag as? Int
                    ?: continue
            val node =
                frame.nodes
                    .firstOrNull {
                        it.id ==
                            id
                    }
                    ?: continue

            val childLeft =
                (
                    node.x *
                        sx
                    )
                    .roundToInt()
            val childTop =
                (
                    node.y *
                        sy
                    )
                    .roundToInt()
            val childRight =
                childLeft +
                    child.measuredWidth
            val childBottom =
                childTop +
                    child.measuredHeight

            child.layout(
                childLeft,
                childTop,
                childRight,
                childBottom
            )
        }
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
            width <=
                0 ||
            height <=
                0 ||
            root.width <=
                0 ||
            root.height <=
                0
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
                            node.width
                                .toFloat(),
                            node.height
                                .toFloat(),
                            rootPaint
                        )
                    }

                    RiftAppAbi.NodeKind.SURFACE -> {
                        if (
                            node.width >
                                0 &&
                            node.height >
                                0
                        ) {
                            canvas.drawRect(
                                node.x
                                    .toFloat(),
                                node.y
                                    .toFloat(),
                                (
                                    node.x +
                                        node.width
                                    )
                                    .toFloat(),
                                (
                                    node.y +
                                        node.height
                                    )
                                    .toFloat(),
                                surfacePaint
                            )
                        }
                    }

                    RiftAppAbi.NodeKind.TEXT -> {
                        drawTextNode(
                            canvas,
                            node
                        )
                    }

                    RiftAppAbi.NodeKind.IMAGE -> {
                        if (
                            node.width >
                                0 &&
                            node.height >
                                0
                        ) {
                            canvas.drawRect(
                                node.x
                                    .toFloat(),
                                node.y
                                    .toFloat(),
                                (
                                    node.x +
                                        node.width
                                    )
                                    .toFloat(),
                                (
                                    node.y +
                                        node.height
                                    )
                                    .toFloat(),
                                imagePaint
                            )
                            drawTextNode(
                                canvas,
                                node
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
            width <=
                0 ||
            height <=
                0 ||
            root.width <=
                0 ||
            root.height <=
                0
        ) {
            return false
        }

        val kind =
            when (
                event.actionMasked
            ) {
                MotionEvent.ACTION_DOWN ->
                    RiftAppAbi.EventKind
                        .POINTER_DOWN

                MotionEvent.ACTION_MOVE ->
                    RiftAppAbi.EventKind
                        .POINTER_MOVE

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL ->
                    RiftAppAbi.EventKind
                        .POINTER_UP

                else ->
                    return true
            }

        if (
            supportsEvent(
                kind
            )
        ) {
            val logicalX =
                (
                    event.x /
                        width.toFloat() *
                        root.width
                            .toFloat()
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
                        root.height
                            .toFloat()
                    )
                    .roundToInt()
                    .coerceIn(
                        0,
                        root.height
                    )

            eventSink(
                RiftAppAbi.Event(
                    kind =
                        kind,
                    arg0 =
                        logicalX,
                    arg1 =
                        logicalY
                )
            )
        }

        if (
            event.actionMasked ==
                MotionEvent.ACTION_UP
        ) {
            performClick()
        }

        return true
    }

    override fun dispatchKeyEvent(
        event: KeyEvent
    ): Boolean {
        val kind =
            when (
                event.action
            ) {
                KeyEvent.ACTION_DOWN ->
                    RiftAppAbi.EventKind
                        .KEY_DOWN

                KeyEvent.ACTION_UP ->
                    RiftAppAbi.EventKind
                        .KEY_UP

                else ->
                    null
            }

        if (
            kind !=
                null &&
            supportsEvent(
                kind
            )
        ) {
            val targetId =
                findFocus()
                    ?.tag as? Int
                    ?: 0

            eventSink(
                RiftAppAbi.Event(
                    kind =
                        kind,
                    targetId =
                        targetId,
                    arg0 =
                        event.keyCode,
                    arg1 =
                        event.repeatCount,
                    arg2 =
                        event.metaState
                )
            )
        }

        return super.dispatchKeyEvent(
            event
        )
    }

    override fun onSizeChanged(
        width: Int,
        height: Int,
        oldWidth: Int,
        oldHeight: Int
    ) {
        super.onSizeChanged(
            width,
            height,
            oldWidth,
            oldHeight
        )

        if (
            width >
                0 &&
            height >
                0 &&
            (
                width !=
                    oldWidth ||
                height !=
                    oldHeight
                ) &&
            supportsEvent(
                RiftAppAbi.EventKind
                    .DISPLAY_RESIZE
            )
        ) {
            eventSink(
                RiftAppAbi.Event(
                    kind =
                        RiftAppAbi.EventKind
                            .DISPLAY_RESIZE,
                    arg0 =
                        width,
                    arg1 =
                        height
                )
            )
        }
    }

    override fun performClick():
        Boolean {
        super.performClick()
        return true
    }

    private fun reconcileWidgets() {
        val desiredInputs =
            frame.nodes
                .filter {
                    it.kind ==
                        RiftAppAbi.NodeKind
                            .TEXT_INPUT
                }
                .associateBy {
                    it.id
                }

        val desiredActions =
            frame.nodes
                .filter {
                    it.kind ==
                        RiftAppAbi.NodeKind
                            .ACTION
                }
                .associateBy {
                    it.id
                }

        inputViews.keys
            .filter {
                it !in
                    desiredInputs
            }
            .toList()
            .forEach {
                id ->
                inputViews
                    .remove(
                        id
                    )
                    ?.let(
                        ::removeView
                    )
            }

        actionViews.keys
            .filter {
                it !in
                    desiredActions
            }
            .toList()
            .forEach {
                id ->
                actionViews
                    .remove(
                        id
                    )
                    ?.let(
                        ::removeView
                    )
            }

        desiredInputs.forEach {
            (
                id,
                node
            ) ->
            val field =
                inputViews[
                    id
                ]
                    ?: createInput(
                        id
                    ).also {
                        inputViews[
                            id
                        ] =
                            it
                        addView(
                            it
                        )
                    }

            if (
                field.text
                    .toString() !=
                node.text
            ) {
                val selection =
                    field.selectionStart
                        .coerceAtLeast(
                            0
                        )

                suppressInput.add(
                    id
                )
                field.setText(
                    node.text
                )
                field.setSelection(
                    minOf(
                        selection,
                        node.text.length
                    )
                )
                suppressInput.remove(
                    id
                )
            }
        }

        desiredActions.forEach {
            (
                id,
                node
            ) ->
            val button =
                actionViews[
                    id
                ]
                    ?: createAction(
                        id
                    ).also {
                        actionViews[
                            id
                        ] =
                            it
                        addView(
                            it
                        )
                    }

            button.text =
                node.text
        }
    }

    private fun createInput(
        id: Int
    ): EditText =
        EditText(
            context
        ).apply {
            tag =
                id
            gravity =
                Gravity.TOP or
                    Gravity.START
            inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType
                        .TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType
                        .TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface =
                Typeface.MONOSPACE
            textSize =
                16f
            setTextColor(
                Color.WHITE
            )
            setHintTextColor(
                Color.LTGRAY
            )
            setPadding(
                12,
                10,
                12,
                10
            )

            addTextChangedListener(
                object :
                    TextWatcher {
                    override fun beforeTextChanged(
                        value: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) = Unit

                    override fun onTextChanged(
                        value: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) = Unit

                    override fun afterTextChanged(
                        value: Editable?
                    ) {
                        if (
                            id in
                                suppressInput ||
                            !supportsEvent(
                                RiftAppAbi.EventKind
                                    .TEXT_INPUT
                            )
                        ) {
                            return
                        }

                        eventSink(
                            RiftAppAbi.Event(
                                kind =
                                    RiftAppAbi.EventKind
                                        .TEXT_INPUT,
                                targetId =
                                    id,
                                arg0 =
                                    selectionStart
                                        .coerceAtLeast(
                                            0
                                        ),
                                arg1 =
                                    selectionEnd
                                        .coerceAtLeast(
                                            0
                                        ),
                                text =
                                    value
                                        ?.toString()
                                        .orEmpty()
                            )
                        )
                    }
                }
            )
        }

    private fun createAction(
        id: Int
    ): Button =
        Button(
            context
        ).apply {
            tag =
                id

            setOnClickListener {
                if (
                    supportsEvent(
                        RiftAppAbi.EventKind
                            .ACTION
                    )
                ) {
                    eventSink(
                        RiftAppAbi.Event(
                            kind =
                                RiftAppAbi.EventKind
                                    .ACTION,
                            targetId =
                                id
                        )
                    )
                }
            }
        }

    private fun drawTextNode(
        canvas: Canvas,
        node: RiftAppAbi.Node
    ) {
        if (
            node.text.isEmpty() ||
            node.width <=
                0 ||
            node.height <=
                0
        ) {
            return
        }

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
            node.x
                .toFloat(),
            (
                node.y +
                    textPaint.textSize
                ),
            textPaint
        )
    }

    private fun rootNode():
        RiftAppAbi.Node =
        frame.nodes
            .single {
                it.kind ==
                    RiftAppAbi.NodeKind
                        .ROOT
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
                    RiftAppAbi.NodeKind
                        .ROOT
            }

        require(
            roots.size ==
                1 &&
                roots[0].width >
                    0 &&
                roots[0].height >
                    0
        ) {
            "RAPP absolute frame requires one bounded root"
        }

        return value
    }
}
