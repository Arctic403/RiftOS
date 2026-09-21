package com.codynex.editorapp

object Vm1Bridge {
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
