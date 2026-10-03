package com.codynex.editorapp

object CodynexRuntimeBridge {
    init {
        System.loadLibrary("codynex_editor_vm")
    }

    external fun run(
        vm: ByteArray,
        program: ByteArray,
        source: ByteArray,
        output: ByteArray,
        stepBudget: Int
    ): IntArray
}
