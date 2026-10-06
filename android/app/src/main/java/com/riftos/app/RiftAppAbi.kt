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

    data class Event(
        val kind: Int,
        val targetId: Int = 0,
        val arg0: Int = 0,
        val arg1: Int = 0,
        val arg2: Int = 0,
        val arg3: Int = 0,
        val text: String = ""
    ) {
        init {
            require(kind in EventKind.BOOT..EventKind.SESSION_TERMINATE) {
                "Unsupported RiftOS app event kind"
            }
            require(targetId >= 0) {
                "RiftOS app event target is invalid"
            }
            require(text.toByteArray(Charsets.UTF_8).size <= 64 * 1024) {
                "RiftOS app event text exceeds bound"
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

    data class RuntimePayload(
        val id: String,
        val name: String,
        val abi: String,
        val adapter: String,
        val presentation: String,
        val program: ByteArray,
        val runtime: ByteArray
    )
}

interface RiftAppRuntimeAdapter {
    val id: String
    val presentation: String

    fun encodeEvent(
        payload: RiftAppAbi.RuntimePayload,
        event: RiftAppAbi.Event
    ): ByteArray

    fun decodeFrame(bytes: ByteArray): RiftAppAbi.Frame
}

object RiftAppAdapters {
    private val adapters: Map<String, RiftAppRuntimeAdapter> by lazy {
        listOf(RiftRappRiftppAdapter)
            .associateBy { it.id }
    }

    fun find(id: String): RiftAppRuntimeAdapter? = adapters[id]

    fun require(id: String): RiftAppRuntimeAdapter =
        find(id) ?: error("Unsupported RiftOS app adapter: $id")

    fun ids(): Set<String> = adapters.keys
}
