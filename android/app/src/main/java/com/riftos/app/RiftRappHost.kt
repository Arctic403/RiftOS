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
    // UI-only presentation identity. No executable, Core Attachment or event queue.
    private class Session(
        val id: String,
        val name: String,
        val adapter: RiftAppRuntimeAdapter,
        val generation: Long
    ) {
        var onFrame: ((RiftAppAbi.Frame) -> Unit)? = null
    }

    private data class EventOutcome(
        val frame: RiftAppAbi.Frame? = null,
        val error: String? = null
    )

    private val manager by lazy(LazyThreadSafetyMode.NONE) {
        RiftCoreRuntime.packages(activity.applicationContext)
    }
    private val coreLifecycle = RiftCoreRuntime.lifecycle(activity.applicationContext)
    private val coreSurfaces = RiftCoreRuntime.surfaces(activity.applicationContext)
    private val shellCapabilities = RiftRappShellCapabilityClient(activity, desktop)
    private val sessions = LinkedHashMap<String, Session>()
    private val surfaceSubscription = coreSurfaces.subscribe { change ->
        if (change.operation == "removed") {
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                // Core stop/uninstall invalidated this surface: close only the
                // stale graphical presentation. Core has already decided stop.
                val presentation = sessions[change.appId] ?: return@runOnUiThread
                val stillCurrent = coreSurfaces.snapshot(change.appId)?.let {
                    it.attachmentGeneration == presentation.generation
                } == true
                // A queued removal of an older generation must not close a
                // newly attached window whose Core frame is already current.
                if (!stillCurrent) {
                    desktop.handle("desktop.window.close", JSONObject().put("id", change.appId))
                }
            }
        } else if (change.operation == "updated") {
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val session = sessions[change.appId] ?: return@runOnUiThread
                val current = coreSurfaces.snapshot(session.id)?.takeIf {
                    it.attachmentGeneration == session.generation
                } ?: return@runOnUiThread
                val renderer = session.onFrame
                if (renderer == null) {
                    desktop.attachContent(session.id, render(session, current.frame))
                } else {
                    renderer(current.frame)
                }
            }
        }
    }
    private val stateSubscription = coreLifecycle.subscribe { change ->
        if (change.error != null) {
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (sessions[change.appId] != null) {
                    desktop.attachContent(change.appId, failureView(change.error))
                }
            }
        }
    }
    private val packageSubscription = RiftCorePackageEvents.subscribe { change ->
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            if (change.operation != "installed" && sessions.containsKey(change.id)) {
                // Package was replaced or removed: close stale presentation.
                desktop.handle(
                    "desktop.window.close",
                    JSONObject().put("id", change.id)
                )
            }
            refreshLauncher()
        }
    }

    private val launchSubscription = RiftCoreAppLaunchRequests.subscribe { id ->
        launchAsync(id)
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

    fun onDesktopClosed(id: String): Boolean {
        val session = sessions.remove(id)
        if (session != null) {
            session.onFrame = null
            // Window close is an explicit Core stop request, not UI lifecycle.
            coreLifecycle.stop(id)
        }
        return session != null || manager.handlesInstalled(id)
    }

    fun destroy() {
        RiftCorePackageEvents.unsubscribe(packageSubscription)
        RiftCoreAppLaunchRequests.unsubscribe(launchSubscription)
        coreSurfaces.unsubscribe(surfaceSubscription)
        coreLifecycle.unsubscribe(stateSubscription)
        // Activity/window loss discards only presentation; Core keeps executing.
        sessions.values.forEach { it.onFrame = null }
        sessions.clear()
        shellCapabilities.destroy()
    }

    private fun open(id: String) {
        val app = manager.loadInstalled(id)
        require(app.abi == RiftAppAbi.SCHEMA) {
            "Unsupported RiftOS app ABI: ${app.abi}"
        }
        val adapter = RiftAppAdapters.require(app.adapter)
        require(adapter.presentation == app.presentation) {
            "RiftOS app presentation does not match adapter"
        }
        // Core owns executable identity, BOOT, FIFO dispatch and session.
        // This client requests only a view of the resulting Core surface.
        val state = coreLifecycle.openForShell(id)
        val session = Session(id, app.name, adapter, state.getLong("attachmentGeneration"))
        sessions[id]?.onFrame = null
        sessions[id] = session
        desktop.handle(
            "desktop.window.open",
            JSONObject().put("id", id).put("title", app.name)
                .put("kicker", "RIFTOS APP · ${app.adapter}")
        )
        val published = coreSurfaces.snapshot(id)?.takeIf {
            it.attachmentGeneration == session.generation
        }
        desktop.attachContent(
            id,
            if (published != null) render(session, published.frame)
            else if (state.optString("state") == "failed") {
                failureView(state.optString("error", "Core RAPP BOOT failed"))
            } else TextView(activity).apply {
                text = "Launching RiftOS app…"
                textSize = 17f
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
        )
    }

    private fun runEventAsync(
        session: Session,
        event: RiftAppAbi.Event,
        complete: (EventOutcome) -> Unit
    ) {
        if (sessions[session.id] !== session) return
        // An accepted input progresses entirely in Core, including when UI
        // disappears; changes arrive via the immutable surface subscription.
        val rejection = runCatching {
            coreLifecycle.offerEvent(session.id, session.generation, event)
        }.exceptionOrNull()
        if (rejection != null) {
            val current = coreSurfaces.snapshot(session.id)?.takeIf {
                it.attachmentGeneration == session.generation
            }
            complete(EventOutcome(
                frame = current?.frame,
                error = rejection.message ?: "Core rejected application input"
            ))
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

        session.onFrame = { next -> applyFrame(next) }
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

        session.onFrame = { next ->
            if (next.layout == RiftAppAbi.Layout.ABSOLUTE) view.updateFrame(next)
            else desktop.attachContent(session.id, render(session, next))
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
