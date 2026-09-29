package com.riftpp.editor

class RiftppNativeBridge {
    companion object {
        init {
            System.loadLibrary("riftpp_editor_bridge")
        }
    }

    external fun run(program: ByteArray, input: ByteArray, outputCapacity: Int): ByteArray?
}
