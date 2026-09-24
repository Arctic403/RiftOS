package com.riftos.app

import android.os.FileObserver
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Recursive, workspace-only filesystem watcher feeding Workspace Records plus an optional
 * bounded event sink.
 *
 * It observes filesDir/riftfs/workspace directly, so changes made by MCP tools, native Files,
 * Git/process work or another local writer become visible to the shared records layer. The
 * current MainActivity supplies a no-op external event sink; the retained HTML workspace surface
 * is not the live built-in consumer.
 */
class RiftWorkspaceWatcher(
    activity: MainActivity,
    private val eventSink: (JSONObject) -> Unit,
    private val records: RiftWorkspaceRecords = RiftWorkspaceRecords.get(activity)
) {
    companion object {
        private const val MAX_WATCHED_DIRECTORIES = 2_048
        private const val INSTALL_BUDGET_MS = 2_000L
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
    @Volatile private var shutdown = false
    @Volatile private var watcherLimitReached = false

    @Synchronized
    fun start(): JSONObject {
        if (shutdown) return state()
        records.start()
        if (!active) {
            active = true
            watcherLimitReached = false
            installTree(workspaceRoot)
            records.updateWatcherCoverage(true, !watcherLimitReached)
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
            watcherLimitReached = false
            records.updateWatcherCoverage(false, false)
        }
        return state()
    }

    fun shutdown() {
        shutdown = true
        stop()
    }

    fun state(): JSONObject = JSONObject()
        .put("active", active)
        .put("root", "workspace")
        .put("watchers", observers.size)
        .put("watcherLimitReached", watcherLimitReached)
        .put("sequence", sequence.get())

    private fun installTree(directory: File) {
        if (
            !active ||
            !directory.exists() ||
            !directory.isDirectory ||
            !isInsideRoot(directory) ||
            !records.shouldTrackDirectory(directory)
        ) return
        val deadline = SystemClock.elapsedRealtime() + INSTALL_BUDGET_MS
        val queue = ArrayDeque<File>()
        queue.add(directory)
        while (active && queue.isNotEmpty()) {
            if (observers.size >= MAX_WATCHED_DIRECTORIES || SystemClock.elapsedRealtime() >= deadline) {
                watcherLimitReached = true
                return
            }
            val current = queue.removeFirst()
            if (!current.exists() || !current.isDirectory || !isInsideRoot(current)) continue
            install(current)
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (
                    child.isDirectory &&
                    isInsideRoot(child) &&
                    records.shouldTrackDirectory(child)
                ) queue.addLast(child)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun install(directory: File) {
        val canonical = runCatching { directory.canonicalFile }.getOrNull() ?: return
        if (!isInsideRoot(canonical)) return
        val key = canonical.absolutePath
        if (observers.containsKey(key)) return
        if (observers.size >= MAX_WATCHED_DIRECTORIES) {
            watcherLimitReached = true
            return
        }

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
                    records.updateWatcherCoverage(true, !watcherLimitReached)
                }
                if (baseEvent == FileObserver.DELETE_SELF || baseEvent == FileObserver.MOVE_SELF) {
                    observers.remove(key)?.let { runCatching { it.stopWatching() } }
                }
                emit(type, target, isDirectory)
            }
        }
        if (observers.putIfAbsent(key, observer) != null) return
        try {
            observer.startWatching()
        } catch (error: Throwable) {
            observers.remove(key, observer)
            throw error
        }
    }

    private fun emit(type: String, file: File, directory: Boolean) {
        if (!active && type != "watch-start") return
        val relative = relativePath(file)
        if (type != "watch-start") records.observe(type, file, directory)
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
