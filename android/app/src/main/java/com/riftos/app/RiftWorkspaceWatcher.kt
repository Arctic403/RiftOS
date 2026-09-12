package com.riftos.app

import android.os.FileObserver
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Recursive, workspace-only filesystem watcher used by the local Rift Workspace HTML surface.
 *
 * It observes filesDir/riftfs/workspace directly, so changes made by MCP tools, RiftFS UI,
 * git/process work, or any other local writer become visible to the HTML surface without
 * exposing a general filesystem bridge to that page.
 */
class RiftWorkspaceWatcher(
    activity: MainActivity,
    private val eventSink: (JSONObject) -> Unit
) {
    companion object {
        private const val WATCH_MASK =
            FileObserver.CREATE or
                FileObserver.DELETE or
                FileObserver.MODIFY or
                FileObserver.MOVED_FROM or
                FileObserver.MOVED_TO or
                FileObserver.CLOSE_WRITE or
                FileObserver.ATTRIB or
                FileObserver.DELETE_SELF or
                FileObserver.MOVE_SELF
    }

    private val workspaceRoot = File(activity.filesDir, "riftfs/workspace").apply { mkdirs() }.canonicalFile
    private val observers = ConcurrentHashMap<String, FileObserver>()
    private val sequence = AtomicLong(0L)
    @Volatile private var active = false

    @Synchronized
    fun start(): JSONObject {
        if (!active) {
            active = true
            installTree(workspaceRoot)
            emit("watch-start", workspaceRoot, true)
        }
        return state()
    }

    @Synchronized
    fun stop(): JSONObject {
        if (active) {
            active = false
            observers.values.forEach { runCatching { it.stopWatching() } }
            observers.clear()
        }
        return state()
    }

    fun shutdown() {
        stop()
    }

    fun state(): JSONObject = JSONObject()
        .put("active", active)
        .put("root", "workspace")
        .put("watchers", observers.size)
        .put("sequence", sequence.get())

    private fun installTree(directory: File) {
        if (!active || !directory.exists() || !directory.isDirectory || !isInsideRoot(directory)) return
        install(directory)
        directory.listFiles()?.filter { it.isDirectory }?.forEach { child -> installTree(child) }
    }

    @Suppress("DEPRECATION")
    private fun install(directory: File) {
        val canonical = runCatching { directory.canonicalFile }.getOrNull() ?: return
        if (!isInsideRoot(canonical)) return
        val key = canonical.absolutePath
        if (observers.containsKey(key)) return

        val observer = object : FileObserver(key, WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) {
                if (!active) return
                val baseEvent = event and FileObserver.ALL_EVENTS
                val target = if (path.isNullOrBlank()) canonical else File(canonical, path)
                val isDirectory = target.isDirectory
                if (!isInsideRoot(target)) return

                val type = eventName(baseEvent) ?: return
                if (isDirectory && (baseEvent == FileObserver.CREATE || baseEvent == FileObserver.MOVED_TO)) {
                    installTree(target)
                }
                if (baseEvent == FileObserver.DELETE_SELF || baseEvent == FileObserver.MOVE_SELF) {
                    observers.remove(key)?.let { runCatching { it.stopWatching() } }
                }
                emit(type, target, isDirectory)
            }
        }
        observers[key] = observer
        observer.startWatching()
    }

    private fun emit(type: String, file: File, directory: Boolean) {
        if (!active && type != "watch-start") return
        val relative = relativePath(file)
        eventSink(
            JSONObject()
                .put("sequence", sequence.incrementAndGet())
                .put("type", type)
                .put("path", relative)
                .put("directory", directory)
                .put("at", System.currentTimeMillis())
        )
    }

    private fun eventName(event: Int): String? = when (event) {
        FileObserver.CREATE -> "create"
        FileObserver.DELETE -> "delete"
        FileObserver.MODIFY -> "modify"
        FileObserver.CLOSE_WRITE -> "write"
        FileObserver.MOVED_FROM -> "move-from"
        FileObserver.MOVED_TO -> "move-to"
        FileObserver.ATTRIB -> "attrib"
        FileObserver.DELETE_SELF -> "delete-self"
        FileObserver.MOVE_SELF -> "move-self"
        else -> null
    }

    private fun relativePath(file: File): String {
        val target = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
        if (target == workspaceRoot) return ""
        return runCatching {
            workspaceRoot.toPath().relativize(target.toPath()).toString().replace(File.separatorChar, '/')
        }.getOrDefault("")
    }

    private fun isInsideRoot(file: File): Boolean {
        val target = runCatching { file.canonicalFile }.getOrElse { return false }
        return target == workspaceRoot || target.path.startsWith(workspaceRoot.path + File.separator)
    }
}
