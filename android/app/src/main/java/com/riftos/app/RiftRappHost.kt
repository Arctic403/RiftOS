package com.riftos.app

import android.app.Activity
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Generic RiftOS application host for installed .rapp programs.
 *
 * Window/lifecycle/input ownership stays in RiftOS. Language/runtime protocol
 * details live behind RiftAppRuntimeAdapter and must not leak into this host.
 */
class RiftRappHost(
    private val activity: Activity,
    private val desktop: RiftNativeDesktop,
    private val refreshLauncher: () -> Unit
) {
    companion object {
        // 1024 x 256 KiB binary host effects reaches the existing 256 MiB build ceiling
        // while keeping every hosted-app transaction finite.
        private const val MAX_EFFECT_DEPTH = 1024

        @Volatile
        private var active:
            WeakReference<RiftRappHost>? = null

        fun notifyProgramsChanged() {
            active
                ?.get()
                ?.refreshLauncherAsync()
        }

        fun launchInstalled(
            id: String
        ): Boolean =
            active
                ?.get()
                ?.launchAsync(id)
                ?: false
    }

    private data class Session(
        val payload: RiftAppAbi.RuntimePayload,
        val adapter: RiftAppRuntimeAdapter,
        val coreAttachment: RiftCoreAppSessions.Attachment
    ) {
        val id: String get() = payload.id
        val name: String get() = payload.name

        // Core owns the program bytes, event sequence and execution. Only
        // UI/effect callbacks remain on this disposable host attachment.
        // UI callbacks are disposable; Core owns the ordered event ticket queue.
        val pendingUiCompletions = LinkedHashMap<Long, (EventOutcome) -> Unit>()
    }

    private data class EventOutcome(
        val frame: RiftAppAbi.Frame? = null,
        val effects: List<RiftAppAbi.HostEffect> =
            emptyList(),
        val error: String? = null
    )

    private val manager by lazy(LazyThreadSafetyMode.NONE) {
        RiftCoreRuntime.packages(activity.applicationContext)
    }
    private val coreSessions = RiftCoreRuntime.sessions(activity.applicationContext)
    private val capabilityBroker =
        RiftRappCapabilityBroker(
            activity,
            desktop
        )
    private val coreExecutor = RiftCoreRuntime.appExecutor(activity.applicationContext)
    private val sessions =
        LinkedHashMap<String, Session>()

    init {
        active =
            WeakReference(this)
    }

    fun openFromLauncher(
        id: String
    ): Boolean {
        if (!manager.handlesInstalled(id)) {
            return false
        }
        runCatching {
            open(id)
        }.onFailure { error ->
            showLaunchFailure(
                id,
                error.message
                    ?: error.javaClass.simpleName
            )
        }
        return true
    }

    fun onDesktopClosed(
        id: String
    ): Boolean {
        val session = sessions.remove(id)
        if (session != null) {
            session.pendingUiCompletions.clear()
            coreSessions.close(session.coreAttachment)
        }
        return session != null || manager.handlesInstalled(id)
    }

    fun onResume() {
        broadcastLifecycle(
            RiftAppAbi.EventKind.HOST_RESUME
        )
    }

    fun onPause() {
        broadcastLifecycle(
            RiftAppAbi.EventKind.HOST_PAUSE
        )
    }

    private fun broadcastLifecycle(
        kind: Int
    ) {
        sessions.values
            .toList()
            .forEach {
                session ->
                if (
                    session.adapter
                        .supportsEventKind(
                            kind
                        )
                ) {
                    runEventAsync(
                        session,
                        RiftAppAbi.Event(
                            kind =
                                kind
                        )
                    ) {
                        // Lifecycle delivery updates opaque program state.
                        // The visible frame is refreshed by the next app event.
                    }
                }
            }
    }

    fun destroy() {
        // Activity loss detaches only UI; Core session identities/state remain.
        sessions.values.forEach {
            it.pendingUiCompletions.clear()
            coreSessions.detach(it.coreAttachment)
        }
        sessions.clear()
        capabilityBroker.destroy()
        val current =
            active
                ?.get()
        if (current === this) {
            active = null
        }
    }

    private fun open(
        id: String
    ) {
        val app =
            manager.loadInstalled(id)
        require(app.abi == RiftAppAbi.SCHEMA) {
            "Unsupported RiftOS app ABI: ${app.abi}"
        }
        val adapter = RiftAppAdapters.require(app.adapter)
        require(adapter.presentation == app.presentation) {
            "RiftOS app presentation does not match adapter"
        }

        val payload = RiftAppAbi.RuntimePayload(
            id = app.id,
            name = app.name,
            abi = app.abi,
            adapter = app.adapter,
            presentation = app.presentation,
            permissions = app.permissions,
            program = app.program,
            runtime = app.runtime
        )
        val attachment = coreSessions.attach(payload, adapter)
        val session = Session(payload, adapter, attachment)
        sessions[id] = session

        desktop.handle(
            "desktop.window.open",
            JSONObject()
                .put("id", id)
                .put("title", app.name)
                .put(
                    "kicker",
                    "RIFTOS APP · ${app.adapter}"
                )
        )

        desktop.attachContent(
            id,
            TextView(activity).apply {
                text = "Launching RiftOS app…"
                textSize = 17f
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
        )

        runEventAsync(
            session,
            RiftAppAbi.Event(
                kind = RiftAppAbi.EventKind.BOOT
            )
        ) { outcome ->
            if (sessions[id] !== session) {
                return@runEventAsync
            }
            val frame = outcome.frame
            desktop.attachContent(
                id,
                if (frame != null) {
                    render(session, frame)
                } else {
                    failureView(
                        outcome.error
                            ?: "RAPP launch failed"
                    )
                }
            )
        }
    }

    private fun runEventAsync(
        session: Session,
        event: RiftAppAbi.Event,
        complete: (EventOutcome) -> Unit
    ) {
        if (
            sessions[session.id] !== session ||
            !coreSessions.isAttached(session.coreAttachment)
        ) return

        val offered = coreSessions.offerEvent(session.coreAttachment, event)
        session.pendingUiCompletions[offered.ticket.id] = complete
        if (offered.startNow) dispatchCoreEvent(session, offered.ticket)
    }

    private fun dispatchCoreEvent(
        session: Session,
        ticket: RiftCoreAppSessions.EventTicket
    ) {
        if (
            sessions[session.id] !== session ||
            !coreSessions.isAttached(session.coreAttachment)
        ) return

        runEventStep(session, ticket.event, 0) { outcome ->
            if (
                sessions[session.id] !== session ||
                !coreSessions.isAttached(session.coreAttachment)
            ) return@runEventStep

            try {
                session.pendingUiCompletions.remove(ticket.id)?.invoke(outcome)
            } finally {
                val next = coreSessions.finishEvent(session.coreAttachment, ticket)
                if (next != null) dispatchCoreEvent(session, next)
            }
        }
    }

    private fun runEventStep(
        session: Session,
        event: RiftAppAbi.Event,
        effectDepth: Int,
        complete: (EventOutcome) -> Unit
    ) {
        if (effectDepth > MAX_EFFECT_DEPTH) {
            complete(EventOutcome(error = "RAPP host effect chain exceeded bound"))
            return
        }

        // Core owns runtime execution, deadlines, state persistence and adapter
        // output parsing; this disposable desktop client only resolves UI effects.
        coreExecutor.execute(
            session.coreAttachment,
            session.payload,
            session.adapter,
            event
        ) { result ->
            activity.runOnUiThread {
                if (
                    activity.isFinishing ||
                    activity.isDestroyed ||
                    sessions[session.id] !== session ||
                    !coreSessions.isAttached(session.coreAttachment)
                ) {
                    return@runOnUiThread
                }

                val outcome = EventOutcome(
                    frame = result.frame,
                    effects = result.effects,
                    error = result.error
                )
                val effect = outcome.effects.singleOrNull()
                if (outcome.error != null || effect == null) {
                    complete(outcome)
                } else {
                    resolveHostEffect(session, effect, complete, effectDepth)
                }
            }
        }
    }

    private fun resolveHostEffect(
        session: Session,
        effect: RiftAppAbi.HostEffect,
        complete: (EventOutcome) -> Unit,
        effectDepth: Int
    ) {
        if (
            effectDepth >=
                MAX_EFFECT_DEPTH
        ) {
            complete(
                EventOutcome(
                    error =
                        "RAPP host effect chain exceeded bound"
                )
            )
            return
        }

        capabilityBroker.execute(
            appId =
                session.id,
            appName =
                session.name,
            declared =
                session.payload
                    .permissions,
            effect =
                effect
        ) {
            result ->
            if (
                sessions[session.id] !==
                    session
            ) {
                return@execute
            }

            runEventStep(
                session =
                    session,
                event =
                    RiftAppAbi.Event(
                        kind =
                            RiftAppAbi.EventKind
                                .HOST_EFFECT_RESULT,
                        targetId =
                            effect.requestId,
                        arg0 =
                            if (
                                result.ok
                            ) {
                                1
                            } else {
                                0
                            },
                        arg1 =
                            result.token,
                        text =
                            if (
                                result.ok
                            ) {
                                result.text
                            } else {
                                result.error
                                    .orEmpty()
                            },
                        bytes =
                            result.bytes
                    ),
                effectDepth =
                    effectDepth + 1,
                complete =
                    complete
            )
        }
    }

    private fun render(
        session: Session,
        frame: RiftAppAbi.Frame
    ): View {
        if (
            frame.layout ==
                RiftAppAbi.Layout.ABSOLUTE
        ) {
            return renderAbsolute(
                session,
                frame
            )
        }

        require(
            frame.layout ==
                RiftAppAbi.Layout.FLOW_COLUMN
        ) {
            "RiftOS app layout is not supported by this renderer yet"
        }

        val root =
            LinearLayout(activity).apply {
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    dp(16),
                    dp(16),
                    dp(16),
                    dp(16)
                )
            }

        val scroll =
            ScrollView(activity).apply {
                isFillViewport =
                    true
                addView(
                    root,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams
                            .MATCH_PARENT,
                        ViewGroup.LayoutParams
                            .WRAP_CONTENT
                    )
                )
            }

        val textNodes =
            LinkedHashMap<Int, TextView>()
        val inputNodes =
            LinkedHashMap<Int, EditText>()
        val actionNodes =
            LinkedHashMap<Int, Button>()
        val suppressInput =
            HashSet<Int>()
        var currentFrame =
            frame

        fun applyFrame(
            next: RiftAppAbi.Frame
        ) {
            val structureChanged =
                next.layout !=
                    RiftAppAbi.Layout.FLOW_COLUMN ||
                    next.nodes.map {
                        it.id to it.kind
                    } !=
                    currentFrame.nodes.map {
                        it.id to it.kind
                    }

            if (
                structureChanged
            ) {
                desktop.attachContent(
                    session.id,
                    render(
                        session,
                        next
                    )
                )
                return
            }

            next.nodes.forEach {
                node ->
                when (
                    node.kind
                ) {
                    RiftAppAbi.NodeKind.TEXT ->
                        textNodes[
                            node.id
                        ]?.text =
                            node.text

                    RiftAppAbi.NodeKind.TEXT_INPUT ->
                        inputNodes[
                            node.id
                        ]?.let {
                            field ->
                            val canonical =
                                node.text

                            if (
                                !field.hasFocus() &&
                                field.text
                                    .toString() !=
                                    canonical
                            ) {
                                val selection =
                                    field.selectionStart
                                        .coerceAtLeast(
                                            0
                                        )

                                suppressInput.add(
                                    node.id
                                )
                                field.setText(
                                    canonical
                                )
                                field.setSelection(
                                    minOf(
                                        selection,
                                        canonical.length
                                    )
                                )
                                suppressInput.remove(
                                    node.id
                                )
                            }
                        }

                    RiftAppAbi.NodeKind.ACTION ->
                        actionNodes[
                            node.id
                        ]?.text =
                            node.text
                }
            }

            currentFrame =
                next
        }

        fun acceptOutcome(
            outcome: EventOutcome
        ) {
            val next =
                outcome.frame

            if (
                next !=
                    null
            ) {
                applyFrame(
                    next
                )
            } else {
                desktop.attachContent(
                    session.id,
                    failureView(
                        outcome.error
                            ?: "RAPP event failed"
                    )
                )
            }
        }

        if (
            session.adapter
                .supportsEventKind(
                    RiftAppAbi.EventKind
                        .DISPLAY_RESIZE
                )
        ) {
            root.addOnLayoutChangeListener {
                _,
                left,
                top,
                right,
                bottom,
                oldLeft,
                oldTop,
                oldRight,
                oldBottom ->

                val nextWidth =
                    right -
                        left
                val nextHeight =
                    bottom -
                        top
                val oldWidth =
                    oldRight -
                        oldLeft
                val oldHeight =
                    oldBottom -
                        oldTop

                if (
                    nextWidth >
                        0 &&
                    nextHeight >
                        0 &&
                    (
                        nextWidth !=
                            oldWidth ||
                        nextHeight !=
                            oldHeight
                        )
                ) {
                    runEventAsync(
                        session,
                        RiftAppAbi.Event(
                            kind =
                                RiftAppAbi.EventKind
                                    .DISPLAY_RESIZE,
                            arg0 =
                                nextWidth,
                            arg1 =
                                nextHeight
                        )
                    ) {
                        outcome ->
                        if (
                            sessions[
                                session.id
                            ] ===
                                session
                        ) {
                            acceptOutcome(
                                outcome
                            )
                        }
                    }
                }
            }
        }

        frame.nodes.forEach {
            node ->
            when (
                node.kind
            ) {
                RiftAppAbi.NodeKind.TEXT -> {
                    val label =
                        TextView(activity).apply {
                            text =
                                node.text
                            textSize =
                                24f
                            setPadding(
                                0,
                                0,
                                0,
                                dp(12)
                            )
                        }

                    textNodes[
                        node.id
                    ] =
                        label
                    root.addView(
                        label
                    )
                }

                RiftAppAbi.NodeKind.TEXT_INPUT -> {
                    val field =
                        EditText(activity).apply {
                            setText(
                                node.text
                            )
                            gravity =
                                Gravity.TOP or
                                    Gravity.START
                            inputType =
                                InputType
                                    .TYPE_CLASS_TEXT or
                                    InputType
                                        .TYPE_TEXT_FLAG_MULTI_LINE or
                                    InputType
                                        .TYPE_TEXT_FLAG_NO_SUGGESTIONS
                            minLines =
                                8
                            maxLines =
                                24
                            setPadding(
                                dp(10),
                                dp(10),
                                dp(10),
                                dp(10)
                            )
                        }

                    inputNodes[
                        node.id
                    ] =
                        field

                    field.setOnKeyListener {
                        _,
                        keyCode,
                        keyEvent ->
                        val kind =
                            when (
                                keyEvent.action
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
                            session.adapter
                                .supportsEventKind(
                                    kind
                                )
                        ) {
                            runEventAsync(
                                session,
                                RiftAppAbi.Event(
                                    kind =
                                        kind,
                                    targetId =
                                        node.id,
                                    arg0 =
                                        keyCode,
                                    arg1 =
                                        keyEvent.repeatCount,
                                    arg2 =
                                        keyEvent.metaState
                                )
                            ) {
                                outcome ->
                                if (
                                    sessions[
                                        session.id
                                    ] ===
                                        session
                                ) {
                                    acceptOutcome(
                                        outcome
                                    )
                                }
                            }
                        }

                        false
                    }

                    field.addTextChangedListener(
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
                                    node.id in
                                        suppressInput ||
                                    !session.adapter
                                        .supportsEventKind(
                                            RiftAppAbi.EventKind
                                                .TEXT_INPUT
                                        )
                                ) {
                                    return
                                }

                                runEventAsync(
                                    session,
                                    RiftAppAbi.Event(
                                        kind =
                                            RiftAppAbi.EventKind
                                                .TEXT_INPUT,
                                        targetId =
                                            node.id,
                                        arg0 =
                                            field.selectionStart
                                                .coerceAtLeast(
                                                    0
                                                ),
                                        arg1 =
                                            field.selectionEnd
                                                .coerceAtLeast(
                                                    0
                                                ),
                                        text =
                                            value
                                                ?.toString()
                                                .orEmpty()
                                    )
                                ) {
                                    outcome ->
                                    if (
                                        sessions[
                                            session.id
                                        ] ===
                                            session
                                    ) {
                                        acceptOutcome(
                                            outcome
                                        )
                                    }
                                }
                            }
                        }
                    )

                    root.addView(
                        field,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams
                                .MATCH_PARENT,
                            0,
                            1f
                        )
                    )
                }

                RiftAppAbi.NodeKind.ACTION -> {
                    val button =
                        Button(activity).apply {
                            text =
                                node.text
                            setOnClickListener {
                                isEnabled =
                                    false

                                runEventAsync(
                                    session,
                                    RiftAppAbi.Event(
                                        kind =
                                            RiftAppAbi.EventKind
                                                .ACTION,
                                        targetId =
                                            node.id
                                    )
                                ) {
                                    outcome ->
                                    isEnabled =
                                        true

                                    if (
                                        sessions[
                                            session.id
                                        ] ===
                                            session
                                    ) {
                                        acceptOutcome(
                                            outcome
                                        )
                                    }
                                }
                            }
                        }

                    actionNodes[
                        node.id
                    ] =
                        button

                    root.addView(
                        button,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams
                                .MATCH_PARENT,
                            ViewGroup.LayoutParams
                                .WRAP_CONTENT
                        )
                    )
                }

                RiftAppAbi.NodeKind.IMAGE -> {
                    root.addView(
                        TextView(activity).apply {
                            text =
                                node.text
                            textSize =
                                16f
                            gravity =
                                Gravity.CENTER
                        }
                    )
                }
            }
        }

        return scroll
    }

    private fun renderAbsolute(
        session: Session,
        frame: RiftAppAbi.Frame
    ): View {
        lateinit var view:
            RiftRappAbsoluteView

        view =
            RiftRappAbsoluteView(
                activity,
                frame,
                supportsEvent = {
                    kind ->
                    session.adapter
                        .supportsEventKind(
                            kind
                        )
                }
            ) { event ->
                runEventAsync(
                    session,
                    event
                ) { outcome ->
                    if (
                        sessions[session.id] !==
                            session
                    ) {
                        return@runEventAsync
                    }

                    val next =
                        outcome.frame
                    if (
                        next != null &&
                        next.layout ==
                            RiftAppAbi.Layout.ABSOLUTE
                    ) {
                        view.updateFrame(
                            next
                        )
                    } else if (
                        next != null
                    ) {
                        desktop.attachContent(
                            session.id,
                            render(
                                session,
                                next
                            )
                        )
                    } else {
                        desktop.attachContent(
                            session.id,
                            failureView(
                                outcome.error
                                    ?: "RAPP event failed"
                            )
                        )
                    }
                }
            }

        return view
    }

    private fun showLaunchFailure(
        id: String,
        message: String
    ) {
        runCatching {
            desktop.handle(
                "desktop.window.open",
                JSONObject()
                    .put("id", id)
                    .put("title", id)
                    .put("kicker", "RIFT APP ERROR")
            )
            desktop.attachContent(
                id,
                failureView(message)
            )
        }
    }

    private fun failureView(
        message: String
    ): View =
        TextView(activity).apply {
            text =
                "RiftOS app failed: $message"
            textSize = 17f
            setPadding(
                dp(18),
                dp(18),
                dp(18),
                dp(18)
            )
            setTextIsSelectable(true)
        }

    private fun refreshLauncherAsync() {
        activity.runOnUiThread {
            runCatching {
                refreshLauncher()
            }
        }
    }

    private fun launchAsync(
        id: String
    ): Boolean {
        if (!manager.handlesInstalled(id)) {
            return false
        }
        activity.runOnUiThread {
            runCatching {
                open(id)
            }.onFailure { error ->
                showLaunchFailure(
                    id,
                    error.message
                        ?: error.javaClass.simpleName
                )
            }
        }
        return true
    }

    private fun dp(
        value: Int
    ): Int =
        (
            value *
                activity.resources
                    .displayMetrics
                    .density
        ).toInt()
}
