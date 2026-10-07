package com.riftos.app

/**
 * Permanent language-neutral application boundary for RiftOS-native programs.
 *
 * RiftOS owns window/lifecycle/input/capability policy. A runtime adapter only
 * translates its language/runtime protocol to and from this contract.
 */
object RiftAppAbi {
    const val SCHEMA = "riftos-app-abi/1"

    object EventKind {
        const val BOOT = 0
        const val ACTION = 1
        const val POINTER_DOWN = 2
        const val POINTER_UP = 3
        const val POINTER_MOVE = 4
        const val KEY_DOWN = 5
        const val KEY_UP = 6
        const val TEXT_INPUT = 7
        const val DISPLAY_RESIZE = 8
        const val HOST_RESUME = 9
        const val HOST_PAUSE = 10
        const val APP_CRASH_REPORT = 11
        const val SESSION_TERMINATE = 12
        const val HOST_EFFECT_RESULT = 13
    }

    object NodeKind {
        const val ROOT = 1
        const val SURFACE = 2
        const val TEXT = 3
        const val TEXT_INPUT = 4
        const val ACTION = 5
        const val IMAGE = 6
    }

    object Layout {
        const val FLOW_COLUMN = 1
        const val ABSOLUTE = 2
    }

    object Capability {
        const val FS_READ = "fs.read"
        const val FS_WRITE = "fs.write"
        const val NETWORK = "network"
        const val CLIPBOARD_READ = "clipboard.read"
        const val CLIPBOARD_WRITE = "clipboard.write"
        const val SHARE = "share"
        const val BUILD_LOCAL = "build.local"
        const val SIGNING_IDENTITY = "signing.identity"
        const val WINDOW_TITLE = "window.title"

        val DECLARABLE: Set<String> =
            setOf(
                FS_READ,
                FS_WRITE,
                NETWORK,
                CLIPBOARD_READ,
                CLIPBOARD_WRITE,
                SHARE,
                BUILD_LOCAL,
                SIGNING_IDENTITY,
                WINDOW_TITLE
            )
    }

    data class Event(
        val kind: Int,
        val targetId: Int = 0,
        val arg0: Int = 0,
        val arg1: Int = 0,
        val arg2: Int = 0,
        val arg3: Int = 0,
        val text: String = "",
        val bytes: ByteArray = ByteArray(0)
    ) {
        init {
            require(kind in EventKind.BOOT..EventKind.HOST_EFFECT_RESULT) {
                "Unsupported RiftOS app event kind"
            }
            require(targetId >= 0) {
                "RiftOS app event target is invalid"
            }
            require(text.toByteArray(Charsets.UTF_8).size <= 64 * 1024) {
                "RiftOS app event text exceeds bound"
            }
            require(bytes.size <= 256 * 1024) {
                "RiftOS app event byte payload exceeds bound"
            }
        }
    }

    data class Node(
        val kind: Int,
        val id: Int,
        val parentId: Int = 0,
        val x: Int = 0,
        val y: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        val z: Int = 0,
        val flags: Int = 0,
        val text: String = ""
    ) {
        init {
            require(kind in NodeKind.ROOT..NodeKind.IMAGE) {
                "Unsupported RiftOS app node kind"
            }
            require(id > 0) {
                "RiftOS app node id must be positive"
            }
            require(parentId >= 0) {
                "RiftOS app parent id is invalid"
            }
            require(text.toByteArray(Charsets.UTF_8).size <= 16 * 1024) {
                "RiftOS app node text exceeds bound"
            }
        }
    }

    data class Frame(
        val layout: Int = Layout.FLOW_COLUMN,
        val nodes: List<Node>
    ) {
        init {
            require(layout == Layout.FLOW_COLUMN || layout == Layout.ABSOLUTE) {
                "Unsupported RiftOS app layout"
            }
            require(nodes.size in 1..256) {
                "RiftOS app node count is out of bounds"
            }
            val ids = HashSet<Int>()
            nodes.forEach { node ->
                require(ids.add(node.id)) {
                    "Duplicate RiftOS app node id"
                }
            }
        }
    }

    /**
     * Generic runtime -> host request. The ABI stays stable as new platform
     * operations are implemented behind declared capability names.
     */
    data class HostEffect(
        val requestId: Int,
        val capability: String,
        val operation: String,
        val token: Int = 0,
        val text: String = "",
        val bytes: ByteArray = ByteArray(0)
    ) {
        init {
            require(requestId > 0) {
                "RiftOS host effect request id is invalid"
            }
            require(capability in Capability.DECLARABLE) {
                "RiftOS host effect capability is unsupported"
            }
            require(
                operation.isNotBlank() &&
                    operation.length <= 64 &&
                    operation.all {
                        it.isLetterOrDigit() ||
                            it == '.' ||
                            it == '_' ||
                            it == '-'
                    }
            ) {
                "RiftOS host effect operation is invalid"
            }
            require(text.toByteArray(Charsets.UTF_8).size <= 256 * 1024) {
                "RiftOS host effect text exceeds bound"
            }
            require(bytes.size <= 256 * 1024) {
                "RiftOS host effect byte payload exceeds bound"
            }
        }
    }

    data class RuntimeOutput(
        val frame: Frame,
        val nextProgram: ByteArray? = null,
        val effects: List<HostEffect> = emptyList()
    ) {
        init {
            require(effects.size <= 1) {
                "RiftOS runtime may request at most one host effect per response"
            }
        }
    }

    data class RuntimePayload(
        val id: String,
        val name: String,
        val abi: String,
        val adapter: String,
        val presentation: String,
        val permissions: Set<String> = emptySet(),
        val program: ByteArray,
        val runtime: ByteArray
    ) {
        init {
            require(permissions.all { it in Capability.DECLARABLE }) {
                "RiftOS app declares unsupported capability"
            }
        }
    }
}

interface RiftAppRuntimeAdapter {
    val id: String
    val presentation: String

    fun supportsEventKind(
        kind: Int
    ): Boolean =
        kind == RiftAppAbi.EventKind.BOOT

    fun encodeEvent(
        payload: RiftAppAbi.RuntimePayload,
        event: RiftAppAbi.Event,
        eventSequence: Int
    ): ByteArray

    fun decodeFrame(bytes: ByteArray): RiftAppAbi.Frame

    fun nextProgram(
        bytes: ByteArray
    ): ByteArray? = null

    /**
     * New generic output lane. Older adapters inherit the legacy frame/state
     * behavior automatically; newer adapters may additionally expose effects.
     */
    fun decodeOutput(
        bytes: ByteArray
    ): RiftAppAbi.RuntimeOutput =
        RiftAppAbi.RuntimeOutput(
            frame = decodeFrame(bytes),
            nextProgram = nextProgram(bytes)
        )
}

object RiftAppAdapters {
    private val adapters: Map<String, RiftAppRuntimeAdapter> by lazy {
        listOf(
            RiftRappRiftppAdapter,
            RiftRappRiftppWs15Adapter,
            RiftRappRiftppGenericAdapter
        ).associateBy { it.id }
    }

    fun find(id: String): RiftAppRuntimeAdapter? = adapters[id]

    fun require(id: String): RiftAppRuntimeAdapter =
        find(id) ?: error("Unsupported RiftOS app adapter: $id")

    fun ids(): Set<String> = adapters.keys
}
