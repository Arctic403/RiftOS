package com.riftos.app

/**
 * Phase 5 transfer planning metadata.
 * Allows large operations to be measured before execution.
 */
internal class RiftTransferManifest {
    var files: Long = 0
    var directories: Long = 0
    var bytes: Long = 0

    fun addFile(size: Long) {
        files++
        bytes += size.coerceAtLeast(0)
    }

    fun addDirectory() {
        directories++
    }

    fun snapshot(): Map<String, Long> = mapOf(
        "files" to files,
        "directories" to directories,
        "bytes" to bytes
    )
}
