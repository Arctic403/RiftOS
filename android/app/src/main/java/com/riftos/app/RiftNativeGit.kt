package com.riftos.app

import android.content.Context
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

class RiftNativeGit(context: Context) {
    companion object {
        private const val META_NAME = ".riftgit.json"
        private const val CURRENT_PATH = "/home/.riftgit-current"
        private const val TOKEN_KEY = "github.token"
        private const val MAX_FILE = 48L * 1024L * 1024L
        private const val MAX_TOTAL = 256L * 1024L * 1024L
        private const val MAX_FILES = 10000
        private const val MAX_META_BYTES = 8L * 1024L * 1024L
        private const val MAX_POINTER_BYTES = 4L * 1024L
        private const val MAX_API_RESPONSE_BYTES = 80L * 1024L * 1024L
        private const val MAX_COMMIT_MESSAGE_BYTES = 16 * 1024
        private const val WORKSPACE_PROJECT = "/workspace/RiftOS-main"
        private const val WORKSPACE_OWNER = "Arctic403"
        private const val WORKSPACE_REPO = "RiftOS"
        private const val WORKSPACE_BRANCH = "main"
    }

    data class CommandResult(val output: String, val result: JSONObject?)

    private data class RepoStatus(
        val meta: JSONObject,
        val modified: List<String>,
        val deleted: List<String>,
        val untracked: List<String>,
        val localManagedCount: Int
    )

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "riftfs").apply { mkdirs() }.canonicalFile
    private val secrets = RiftSecretStore(appContext)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    fun storeToken(raw: String): JSONObject {
        val token = raw.trim()
        require(token.length in 20..512) { "GitHub token is invalid" }
        val me = apiObject("/user", tokenOverride = token)
        secrets.set(TOKEN_KEY, token)
        return JSONObject().put("authenticated", true).put("login", me.optString("login"))
    }

    fun clearToken(): JSONObject =
        JSONObject().put("authenticated", false).put("cleared", secrets.remove(TOKEN_KEY))

    fun authStatus(): JSONObject {
        val token = secrets.get(TOKEN_KEY).orEmpty()
        if (token.isBlank()) return JSONObject().put("authenticated", false)
        return try {
            val me = apiObject("/user", tokenOverride = token)
            JSONObject().put("authenticated", true).put("login", me.optString("login"))
        } catch (error: Throwable) {
            JSONObject().put("authenticated", false).put("error", error.message)
        }
    }

    fun execute(input: List<String>, initialCwd: String): CommandResult {
        val args = input.toMutableList()
        var cwd = normalizeDisplay(initialCwd)
        if (args.firstOrNull() == "-C") {
            require(args.size >= 2) { "usage: git -C <folder> <command>" }
            cwd = resolveDisplay(cwd, args[1])
            args.removeAt(0)
            args.removeAt(0)
        }

        val cmd = args.removeFirstOrNull()?.lowercase() ?: "help"
        val lines = ArrayList<String>()
        val printer: (Any?) -> Unit = { lines += it?.toString().orEmpty() }
        val result = when (cmd) {
            "help" -> JSONObject().put("help", help(printer))
            "auth", "auth-status" -> {
                require(args.isEmpty()) { "usage: git auth-status" }
                val value = authStatus()
                printer(if (value.optBoolean("authenticated")) "Authenticated as " + value.optString("login") + "." else "GitHub auth is not configured. Open RiftOS Settings.")
                value
            }
            "logout" -> {
                require(args.isEmpty()) { "usage: git logout" }
                clearToken().also { printer("GitHub credential cleared.") }
            }
            "workspace" -> workspaceCommand(args, printer)
            "status" -> {
                require(args.isEmpty()) { "usage: git status" }
                statusResult(status(cwd), printer)
            }
            "repo" -> {
                require(args.isEmpty()) { "usage: git repo" }
                loadMeta(cwd).also { printer(it.optString("full")) }
            }
            "root" -> {
                require(args.isEmpty()) { "usage: git root" }
                loadMeta(cwd).also { printer(it.optString("root")) }
            }
            "commit" -> saveCommitMessage(cwd, args, printer)
            "push" -> atomicPush(cwd, args.joinToString(" ").trim(), null, printer)
            "pull" -> {
                require(args.isEmpty()) { "usage: git pull" }
                pull(cwd, printer)
            }
            "sync" -> sync(cwd, args.joinToString(" ").trim(), printer)
            "branches", "branch" -> {
                require(args.isEmpty()) { "usage: git branches" }
                listBranches(cwd, printer)
            }
            "switch", "checkout" -> {
                require(args.size == 1) { "usage: git switch <branch>" }
                switchBranch(cwd, args.single(), printer)
            }
            "init", "attach" -> attach(cwd, args, printer)
            "clone" -> cloneRepo(cwd, args, printer)
            "use" -> {
                require(args.size == 1) { "usage: git use <folder|owner/repo>" }
                useRepo(cwd, args.single(), printer)
            }
            else -> throw IllegalArgumentException("unknown git command: " + cmd)
        }
        return CommandResult(lines.joinToString("\n"), result)
    }

    private fun help(print: (Any?) -> Unit): Boolean {
        print(
            "RiftGit / Android-native full RiftFS sync\n" +
                "git auth | auth-status | logout\n" +
                "git workspace status | workspace push [message]\n" +
                "git clone owner/repo [branch] [destination]\n" +
                "git init owner/repo [branch] [folder]\n" +
                "git use <folder|owner/repo>\n" +
                "git root | repo | status | pull\n" +
                "git commit -m <message>\n" +
                "git push [message] | sync [message]\n" +
                "git branches | switch <branch>\n" +
                "GitHub tokens are entered only in native Settings and stored with Android Keystore."
        )
        return true
    }

    private fun workspaceCommand(args: MutableList<String>, print: (Any?) -> Unit): JSONObject {
        val sub = args.removeFirstOrNull()?.lowercase() ?: "status"
        return when (sub) {
            "status" -> {
                require(args.isEmpty()) { "usage: git workspace status" }
                statusResult(statusFor(workspaceMeta()), print)
            }
            "push" -> atomicPush(
                WORKSPACE_PROJECT,
                args.joinToString(" ").trim().ifBlank { "Update RiftOS workspace" },
                workspaceMeta(),
                print
            )
            else -> throw IllegalArgumentException("usage: git workspace [status|push [message]]")
        }
    }

    private fun saveCommitMessage(cwd: String, args: MutableList<String>, print: (Any?) -> Unit): JSONObject {
        require(args.removeFirstOrNull() == "-m" && args.isNotEmpty()) { "usage: git commit -m <message>" }
        val message = checkedCommitMessage(args.joinToString(" ").trim())
        val meta = loadMeta(cwd)
        meta.put("pendingMessage", message)
        saveMeta(meta)
        print("Saved commit message for the next atomic push.")
        return JSONObject().put("message", message)
    }

    private fun status(cwd: String): RepoStatus = statusFor(loadMeta(cwd))

    private fun statusFor(meta: JSONObject): RepoStatus {
        val repoRoot = resolveFile(meta.getString("root"), "/")
        require(repoRoot.isDirectory && repoRoot != root) { "Repository root is missing or unsafe: " + meta.getString("root") }
        val local = LinkedHashMap<String, File>()
        var localBytes = 0L
        repoRoot.walkTopDown().onEnter { directory ->
            val canonical = directory.canonicalFile
            require(canonical == repoRoot || canonical.path.startsWith(repoRoot.canonicalPath + File.separator)) {
                "Repository directory escaped root"
            }
            directory == repoRoot || directory.name != ".git"
        }.forEach { file ->
            if (!file.isFile) return@forEach
            val canonical = file.canonicalFile
            require(canonical.path.startsWith(repoRoot.canonicalPath + File.separator)) { "Repository entry escaped root" }
            val relative = file.relativeTo(repoRoot).invariantSeparatorsPath
            if (!ignored(relative)) {
                require(file.length() <= MAX_FILE) { "$relative exceeds per-file Git limit" }
                localBytes += file.length()
                require(localBytes <= MAX_TOTAL) { "Local repository exceeds total Git limit" }
                require(local.size < MAX_FILES) { "Local repository exceeds Git file-count limit" }
                local[relative] = file
            }
        }
        val modified = ArrayList<String>()
        val deleted = ArrayList<String>()
        val tracked = meta.optJSONObject("tracked") ?: JSONObject()
        tracked.keys().asSequence().toList().sorted().forEach { path ->
            val file = local.remove(path)
            if (file == null) deleted += path
            else {
                val base = tracked.optJSONObject(path)?.optString("blobSha").orEmpty()
                if (blobSha(file) != base) modified += path
            }
        }
        return RepoStatus(meta, modified.sorted(), deleted.sorted(), local.keys.sorted(), local.size + modified.size)
    }

    private fun statusResult(state: RepoStatus, print: (Any?) -> Unit): JSONObject {
        val meta = state.meta
        print("On " + meta.optString("full") + " / " + meta.optString("branch") + "\nroot " + meta.optString("root"))
        if (state.modified.isEmpty() && state.deleted.isEmpty() && state.untracked.isEmpty()) print("working tree clean")
        state.modified.forEach { print(" M " + it) }
        state.deleted.forEach { print(" D " + it) }
        state.untracked.forEach { print("?? " + it) }
        return JSONObject()
            .put("backend", "android-native-git")
            .put("meta", JSONObject(meta.toString()))
            .put("modified", JSONArray(state.modified))
            .put("deleted", JSONArray(state.deleted))
            .put("untracked", JSONArray(state.untracked))
            .put("localManagedCount", state.localManagedCount)
    }

    private fun atomicPush(
        cwd: String,
        rawMessage: String,
        suppliedMeta: JSONObject?,
        print: (Any?) -> Unit
    ): JSONObject {
        requireToken()
        val initial = statusFor(suppliedMeta ?: loadMeta(cwd))
        val meta = initial.meta
        val changes = initial.modified + initial.deleted + initial.untracked
        if (changes.isEmpty()) {
            print("nothing to push")
            return JSONObject().put("pushed", false).put("reason", "clean")
        }
        require(changes.size <= MAX_FILES) { "Change set exceeds file limit" }
        val owner = meta.getString("owner")
        val repo = meta.getString("repo")
        val branch = meta.getString("branch")
        val remote = branchInfo(owner, repo, branch)
        val remoteSha = remote.getJSONObject("commit").getString("sha")
        val recorded = meta.optString("headSha")
        require(recorded.isBlank() || recorded == remoteSha) {
            "Remote branch changed since the last clone/pull. Run: git pull"
        }
        val headCommit = apiObject(
            "/repos/" + enc(owner) + "/" + enc(repo) + "/git/commits/" + enc(remoteSha)
        )
        val baseTree = headCommit.getJSONObject("tree").getString("sha")
        val entries = JSONArray()
        val uploaded = LinkedHashMap<String, String>()
        val uploadedSizes = LinkedHashMap<String, Long>()
        val uploadPaths = initial.modified + initial.untracked
        var total = 0L
        var done = 0
        print("Uploading " + changes.size + " change(s) as one atomic commit...")
        for (path in uploadPaths) {
            val file = repoFile(meta, path)
            require(file.length() <= MAX_FILE) { path + " exceeds per-file limit" }
            total += file.length()
            require(total <= MAX_TOTAL) { "Change set exceeds total sync limit" }
            val bytes = file.readBytes()
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val blob = apiObject(
                "/repos/" + enc(owner) + "/" + enc(repo) + "/git/blobs",
                "POST",
                JSONObject().put("content", encoded).put("encoding", "base64")
            )
            val localSha = blobSha(bytes)
            require(blob.optString("sha") == localSha) { "GitHub blob hash mismatch: " + path }
            uploaded[path] = localSha
            uploadedSizes[path] = bytes.size.toLong()
            val oldMode = meta.optJSONObject("tracked")?.optJSONObject(path)?.optString("mode", "100644") ?: "100644"
            entries.put(JSONObject().put("path", path).put("mode", oldMode).put("type", "blob").put("sha", localSha))
            done++
            if (done % 25 == 0 || done == uploadPaths.size) print("  " + done + "/" + uploadPaths.size + " files uploaded")
        }
        initial.deleted.forEach {
            entries.put(JSONObject().put("path", it).put("mode", "100644").put("type", "blob").put("sha", JSONObject.NULL))
        }

        verifyWorkspaceStable(initial, uploaded)

        val tree = apiObject(
            "/repos/" + enc(owner) + "/" + enc(repo) + "/git/trees",
            "POST",
            JSONObject().put("base_tree", baseTree).put("tree", entries)
        )
        val message = checkedCommitMessage(rawMessage.ifBlank {
            meta.optString("pendingMessage").ifBlank { "RiftOS workspace update" }
        })
        val commit = apiObject(
            "/repos/" + enc(owner) + "/" + enc(repo) + "/git/commits",
            "POST",
            JSONObject()
                .put("message", message)
                .put("tree", tree.getString("sha"))
                .put("parents", JSONArray().put(remoteSha))
        )
        val commitSha = commit.getString("sha")
        verifyWorkspaceStable(initial, uploaded)
        apiObject(
            "/repos/" + enc(owner) + "/" + enc(repo) + "/git/refs/heads/" +
                branch.split('/').joinToString("/") { enc(it) },
            "PATCH",
            JSONObject().put("sha", commitSha).put("force", false)
        )

        val tracked = meta.optJSONObject("tracked") ?: JSONObject().also { meta.put("tracked", it) }
        initial.deleted.forEach { tracked.remove(it) }
        uploadPaths.forEach { path ->
            val oldMode = tracked.optJSONObject(path)?.optString("mode", "100644") ?: "100644"
            tracked.put(
                path,
                JSONObject()
                    .put("blobSha", uploaded.getValue(path))
                    .put("size", uploadedSizes.getValue(path))
                    .put("mode", oldMode)
            )
        }
        meta.put("headSha", commitSha)
        meta.remove("pendingMessage")
        if (suppliedMeta != null) refreshAttachedMeta(meta) else saveMeta(meta)
        checkpoint(meta.optString("root"), "git:push", commitSha)
        print("Push complete: " + commitSha.take(12) + " · one Git commit.")
        return JSONObject().put("pushed", true).put("commitSha", commitSha).put("changes", changes.size)
    }

    private fun verifyWorkspaceStable(initial: RepoStatus, uploaded: Map<String, String>) {
        val current = statusFor(initial.meta)
        require(
            current.modified == initial.modified &&
                current.deleted == initial.deleted &&
                current.untracked == initial.untracked
        ) { "Workspace changed during upload. Nothing was committed; review and retry." }
        uploaded.forEach { (path, sha) ->
            require(blobSha(repoFile(initial.meta, path)) == sha) {
                "Workspace changed during upload: " + path
            }
        }
    }

    private fun pull(cwd: String, print: (Any?) -> Unit): JSONObject {
        val state = status(cwd)
        val trackedCount = state.meta.optJSONObject("tracked")?.length() ?: 0
        val emptyCheckout =
            trackedCount > 0 && state.localManagedCount == 0 &&
                state.modified.isEmpty() && state.untracked.isEmpty() &&
                state.deleted.size == trackedCount
        require(
            (state.modified.isEmpty() && state.deleted.isEmpty() && state.untracked.isEmpty()) ||
                emptyCheckout
        ) { "Working tree has local changes. Push or discard them before pulling." }
        val meta = state.meta
        val remote = branchInfo(meta.getString("owner"), meta.getString("repo"), meta.getString("branch"))
        val sha = remote.getJSONObject("commit").getString("sha")
        if (sha == meta.optString("headSha") && !emptyCheckout) {
            print("Already up to date.")
            return JSONObject().put("updated", false).put("headSha", sha)
        }
        val result = importBranch(meta, meta.getString("branch"), print)
        print("Pull complete at " + result.getString("headSha").take(12) + ".")
        return result
    }

    private fun sync(cwd: String, message: String, print: (Any?) -> Unit): JSONObject {
        val state = status(cwd)
        val meta = state.meta
        val remote = branchInfo(meta.getString("owner"), meta.getString("repo"), meta.getString("branch"))
        val remoteSha = remote.getJSONObject("commit").getString("sha")
        val dirty = state.modified.size + state.deleted.size + state.untracked.size
        if (remoteSha != meta.optString("headSha")) {
            require(dirty == 0) { "Remote and local files both changed. Resolve local changes before sync." }
            return pull(cwd, print)
        }
        if (dirty == 0) {
            print("Already synchronized.")
            return JSONObject().put("synchronized", true).put("changed", false)
        }
        return atomicPush(cwd, message, null, print)
    }

    private fun listBranches(cwd: String, print: (Any?) -> Unit): JSONObject {
        val meta = loadMeta(cwd)
        val rows = apiArray(
            "/repos/" + enc(meta.getString("owner")) + "/" + enc(meta.getString("repo")) +
                "/branches?per_page=100"
        )
        val names = JSONArray()
        for (i in 0 until rows.length()) {
            val name = rows.getJSONObject(i).getString("name")
            names.put(name)
            print((if (name == meta.getString("branch")) "*" else " ") + " " + name)
        }
        return JSONObject().put("branches", names).put("current", meta.getString("branch"))
    }

    private fun switchBranch(cwd: String, branch: String?, print: (Any?) -> Unit): JSONObject {
        val target = branch ?: throw IllegalArgumentException("usage: git switch <branch>")
        val state = status(cwd)
        require(state.modified.isEmpty() && state.deleted.isEmpty() && state.untracked.isEmpty()) {
            "Working tree has local changes. Push/discard them before switching branches."
        }
        if (target == state.meta.getString("branch")) {
            print("Already on " + target)
            return JSONObject().put("branch", target).put("changed", false)
        }
        val result = importBranch(state.meta, target, print)
        print("Now on " + target + " at " + result.getString("headSha").take(12) + ".")
        return result.put("branch", target)
    }

    private fun attach(cwd: String, args: MutableList<String>, print: (Any?) -> Unit): JSONObject {
        val repoArg = args.removeFirstOrNull() ?: throw IllegalArgumentException(
            "usage: git init owner/repo [branch] [folder]"
        )
        val repo = parseRepo(repoArg)
        val info = apiObject("/repos/" + enc(repo.first) + "/" + enc(repo.second))
        val branch = args.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: info.optString("default_branch", "main")
        require(args.size <= 2) { "usage: git init owner/repo [branch] [folder]" }
        val rootDisplay = args.getOrNull(1)?.let { resolveDisplay(cwd, it) } ?: cwd
        val dir = resolveFile(rootDisplay, "/")
        require(dir.isDirectory && dir != root && !RiftVolumePaths.isVolumeRoot(rootDisplay)) {
            "Project folder is missing or unsafe: " + rootDisplay
        }
        val branchState = branchInfo(repo.first, repo.second, branch)
        val tree = treeFor(repo.first, repo.second, branch)
        val tracked = JSONObject()
        for (i in 0 until tree.length()) {
            val item = tree.getJSONObject(i)
            tracked.put(
                item.getString("path"),
                JSONObject()
                    .put("blobSha", item.getString("sha"))
                    .put("size", item.optLong("size"))
                    .put("mode", item.optString("mode", "100644"))
            )
        }
        val meta = JSONObject()
            .put("format", "riftgit-v3")
            .put("owner", repo.first)
            .put("repo", repo.second)
            .put("full", repo.first + "/" + repo.second)
            .put("branch", branch)
            .put("root", rootDisplay)
            .put("headSha", branchState.getJSONObject("commit").getString("sha"))
            .put("tracked", tracked)
            .put("attachedAt", System.currentTimeMillis())
        saveMeta(meta)
        print(
            "Attached " + rootDisplay + "\nto " + repo.first + "/" + repo.second + "#" +
                branch + " at " + meta.getString("headSha").take(12) + "."
        )
        statusResult(statusFor(meta), print)
        return meta
    }

    private fun cloneRepo(cwd: String, args: MutableList<String>, print: (Any?) -> Unit): JSONObject {
        val repoArg = args.removeFirstOrNull() ?: throw IllegalArgumentException(
            "usage: git clone owner/repo [branch] [destination]"
        )
        val repo = parseRepo(repoArg)
        val info = apiObject("/repos/" + enc(repo.first) + "/" + enc(repo.second))
        val branch = args.getOrNull(0)?.takeIf { it.isNotBlank() }
            ?: info.optString("default_branch", "main")
        require(args.size <= 2) { "usage: git clone owner/repo [branch] [destination]" }
        val rootDisplay = args.getOrNull(1)?.let { resolveDisplay(cwd, it) }
            ?: resolveDisplay(cwd, repo.second)
        val destination = resolveFile(rootDisplay, "/")
        require(destination != root && !RiftVolumePaths.isVolumeRoot(rootDisplay)) { "Clone destination is unsafe: $rootDisplay" }
        require(!destination.exists()) { "Destination already exists: " + rootDisplay }
        val meta = JSONObject()
            .put("format", "riftgit-v3")
            .put("owner", repo.first)
            .put("repo", repo.second)
            .put("full", repo.first + "/" + repo.second)
            .put("branch", branch)
            .put("root", rootDisplay)
            .put("headSha", JSONObject.NULL)
            .put("tracked", JSONObject())
            .put("clonedAt", System.currentTimeMillis())
        val result = importBranch(meta, branch, print)
        print(
            "Done. " + result.optInt("imported") + " files at " +
                result.getString("headSha").take(12) + "."
        )
        return result.put("root", rootDisplay)
    }

    private fun useRepo(cwd: String, raw: String?, print: (Any?) -> Unit): JSONObject {
        val value = raw ?: throw IllegalArgumentException("usage: git use <folder|owner/repo>")
        val meta = if (
            value.contains("/") && !value.startsWith("/") && !value.startsWith(".") &&
            value.split('/').size == 2
        ) {
            val repo = parseRepo(value)
            listOf(
                "/home/repos/" + repo.first + "/" + repo.second,
                "/home/repos/" + repo.second
            ).firstNotNullOfOrNull { findMeta(resolveFile(it, "/")) }
        } else {
            findMeta(resolveFile(resolveDisplay(cwd, value), "/"))
        } ?: throw IllegalArgumentException("No attached RiftGit repository found for " + value)
        saveMeta(meta)
        print(
            "Current repo: " + meta.getString("full") + "#" + meta.getString("branch") +
                "\n" + meta.getString("root")
        )
        return meta
    }

    private fun importBranch(meta: JSONObject, branch: String, print: (Any?) -> Unit): JSONObject {
        val owner = meta.getString("owner")
        val repo = meta.getString("repo")
        val branchState = branchInfo(owner, repo, branch)
        val headSha = branchState.getJSONObject("commit").getString("sha")
        val tree = treeFor(owner, repo, headSha)
        val rootDisplay = meta.getString("root")
        val repoRoot = resolveFile(rootDisplay, "/")
        require(repoRoot != root && !RiftVolumePaths.isVolumeRoot(rootDisplay)) {
            "Repository root cannot be the RiftFS root or a drive root"
        }
        val parent = repoRoot.parentFile ?: throw IllegalStateException("Repository has no parent")
        require(parent == root || parent.path.startsWith(root.path + File.separator)) {
            "Repository parent escaped RiftFS"
        }

        val suffix = System.currentTimeMillis().toString() + "-" + UUID.randomUUID().toString()
        val stage = File(parent, "." + repoRoot.name + ".riftgit-stage-" + suffix)
        val backup = File(parent, "." + repoRoot.name + ".riftgit-backup-" + suffix)
        require(stage.mkdirs()) { "Could not create Git staging folder" }

        val tracked = JSONObject()
        var retainRecovery = false
        try {
            for (i in 0 until tree.length()) {
                val item = tree.getJSONObject(i)
                val path = safeRelative(item.getString("path"))
                val bytes = blobBytes(owner, repo, item.getString("sha"))
                val target = File(stage, path).canonicalFile
                require(target.path.startsWith(stage.canonicalPath + File.separator)) {
                    "Unsafe repository path: " + path
                }
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                tracked.put(
                    path,
                    JSONObject()
                        .put("blobSha", item.getString("sha"))
                        .put("size", bytes.size)
                        .put("mode", item.optString("mode", "100644"))
                )
                if ((i + 1) % 25 == 0 || i + 1 == tree.length()) {
                    print("  " + (i + 1) + "/" + tree.length() + " files")
                }
            }

            val next = JSONObject(meta.toString())
                .put("format", "riftgit-v3")
                .put("branch", branch)
                .put("headSha", headSha)
                .put("tracked", tracked)
                .put("root", rootDisplay)
                .put("updatedAt", System.currentTimeMillis())
            val metaBytes = next.toString(2).toByteArray(Charsets.UTF_8)
            require(metaBytes.size.toLong() <= MAX_META_BYTES) { "Git metadata exceeds $MAX_META_BYTES bytes" }
            atomicWrite(File(stage, META_NAME), metaBytes)

            var backedUp = false
            var published = false
            try {
                if (repoRoot.exists()) {
                    require(repoRoot.renameTo(backup)) {
                        "Could not move current project into recovery backup"
                    }
                    backedUp = true
                }
                require(stage.renameTo(repoRoot)) { "Could not publish staged project" }
                published = true
                writeCurrentRoot(rootDisplay)

                val backupCleanupPending =
                    backedUp && backup.exists() && !runCatching { backup.deleteRecursively() }.getOrDefault(false)

                checkpoint(rootDisplay, "git:pull", headSha)
                return JSONObject()
                    .put("imported", tree.length())
                    .put("headSha", headSha)
                    .put("backupCleanupPending", backupCleanupPending)
                    .put("backupPath", if (backupCleanupPending) backup.absolutePath else JSONObject.NULL)
            } catch (error: Throwable) {
                val rollbackIssues = ArrayList<String>()

                if (published && repoRoot.exists()) {
                    if (stage.exists()) {
                        rollbackIssues += "staging path already exists while rolling back published project"
                    } else if (!repoRoot.renameTo(stage)) {
                        rollbackIssues += "could not move failed published project back to staging"
                    }
                }

                if (backedUp) {
                    if (repoRoot.exists()) {
                        rollbackIssues += "repository destination is occupied; original backup could not be restored"
                    } else if (!backup.renameTo(repoRoot)) {
                        rollbackIssues += "could not restore original project backup"
                    }
                }

                if (rollbackIssues.isNotEmpty()) {
                    retainRecovery = true
                    throw IllegalStateException(
                        (error.message ?: "Git replacement failed") +
                            "; rollback incomplete: " + rollbackIssues.joinToString("; ") +
                            "; backup=" + backup.absolutePath +
                            "; stage=" + stage.absolutePath,
                        error
                    )
                }

                throw IllegalStateException(
                    (error.message ?: "Git replacement failed") +
                        ". Previous destination state restored.",
                    error
                )
            }
        } catch (error: Throwable) {
            if (!retainRecovery && stage.exists()) {
                val removed = runCatching { stage.deleteRecursively() }.getOrDefault(false)
                if (!removed) {
                    throw IllegalStateException(
                        (error.message ?: "Git import failed") +
                            "; staging cleanup failed: " + stage.absolutePath,
                        error
                    )
                }
            }
            throw error
        }
    }

    private fun workspaceMeta(): JSONObject {
        val remote = branchInfo(WORKSPACE_OWNER, WORKSPACE_REPO, WORKSPACE_BRANCH)
        val sha = remote.getJSONObject("commit").getString("sha")
        val tree = treeFor(WORKSPACE_OWNER, WORKSPACE_REPO, sha)
        val tracked = JSONObject()
        for (i in 0 until tree.length()) {
            val item = tree.getJSONObject(i)
            val path = item.getString("path")
            if (!ignored(path)) {
                tracked.put(
                    path,
                    JSONObject()
                        .put("blobSha", item.getString("sha"))
                        .put("size", item.optLong("size"))
                        .put("mode", item.optString("mode", "100644"))
                )
            }
        }
        return JSONObject()
            .put("format", "riftgit-v3")
            .put("owner", WORKSPACE_OWNER)
            .put("repo", WORKSPACE_REPO)
            .put("full", WORKSPACE_OWNER + "/" + WORKSPACE_REPO)
            .put("branch", WORKSPACE_BRANCH)
            .put("root", WORKSPACE_PROJECT)
            .put("headSha", sha)
            .put("tracked", tracked)
    }

    private fun loadMeta(cwd: String): JSONObject {
        findMeta(resolveFile(cwd, "/"))?.let { return it }
        val current = resolveFile(CURRENT_PATH, "/")
        if (current.isFile) {
            val pointed = readBoundedText(current, MAX_POINTER_BYTES, "saved repo pointer").trim()
            findMeta(resolveFile(pointed, "/"))?.let { return it }
            throw IllegalStateException("Saved repo path no longer exists: " + pointed)
        }
        throw IllegalStateException(
            "No repo is attached here. cd into the project and run: git init owner/repo [branch]"
        )
    }

    private fun findMeta(start: File): JSONObject? {
        var current: File? = if (start.isFile) start.parentFile else start
        while (
            current != null &&
            (current == root || current.path.startsWith(root.path + File.separator))
        ) {
            val metaFile = File(current, META_NAME)
            if (metaFile.isFile) {
                val meta = JSONObject(readBoundedText(metaFile, MAX_META_BYTES, "RiftGit metadata"))
                meta.put("root", displayPath(current))
                return validateMeta(meta)
            }
            if (current == root) break
            current = current.parentFile
        }
        return null
    }

    private fun saveMeta(meta: JSONObject) {
        writeMeta(meta)
        writeCurrentRoot(meta.getString("root"))
    }

    private fun writeMeta(meta: JSONObject) {
        meta.put("format", "riftgit-v3")
        meta.put("updatedAt", System.currentTimeMillis())
        val dir = resolveFile(meta.getString("root"), "/")
        require(dir.isDirectory && dir != root) { "RiftGit repository root is missing or unsafe" }
        val bytes = meta.toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= MAX_META_BYTES) { "Git metadata exceeds $MAX_META_BYTES bytes" }
        atomicWrite(File(dir, META_NAME), bytes)
    }

    private fun writeCurrentRoot(display: String) {
        val bytes = display.toByteArray(Charsets.UTF_8)
        require(bytes.size.toLong() <= MAX_POINTER_BYTES) { "saved repo pointer exceeds $MAX_POINTER_BYTES bytes" }
        val file = resolveFile(CURRENT_PATH, "/")
        atomicWrite(file, bytes)
    }

    private fun refreshAttachedMeta(meta: JSONObject) {
        val file = File(resolveFile(meta.getString("root"), "/"), META_NAME)
        if (!file.isFile) return
        val old = runCatching {
            JSONObject(readBoundedText(file, MAX_META_BYTES, "RiftGit metadata"))
                .put("root", meta.getString("root"))
                .let(::validateMeta)
        }.getOrNull() ?: return
        if (
            old.optString("owner") != meta.optString("owner") ||
            old.optString("repo") != meta.optString("repo") ||
            old.optString("branch") != meta.optString("branch")
        ) return
        meta.keys().forEach { key -> old.put(key, meta.opt(key)) }
        writeMeta(old)
    }

    private fun branchInfo(owner: String, repo: String, branch: String): JSONObject =
        apiObject(
            "/repos/" + enc(owner) + "/" + enc(repo) + "/branches/" + enc(checkedBranch(branch))
        )

    private fun treeFor(owner: String, repo: String, ref: String): JSONArray {
        val result = apiObject(
            "/repos/" + enc(owner) + "/" + enc(repo) +
                "/git/trees/" + enc(ref) + "?recursive=1"
        )
        require(!result.optBoolean("truncated")) {
            "GitHub returned a truncated tree; sync stopped."
        }
        val input = result.getJSONArray("tree")
        val output = JSONArray()
        var total = 0L
        for (i in 0 until input.length()) {
            val item = input.getJSONObject(i)
            if (item.optString("type") != "blob") continue
            val path = safeRelative(item.getString("path"))
            if (ignored(path)) continue
            val size = item.optLong("size", -1L)
            require(size in 0..MAX_FILE) { path + " has invalid or oversized Git size" }
            total += size
            require(total <= MAX_TOTAL) { "Repository exceeds total Git limit" }
            output.put(item)
            require(output.length() <= MAX_FILES) { "Repository exceeds Git file-count limit" }
        }
        return output
    }

    private fun blobBytes(owner: String, repo: String, sha: String): ByteArray {
        val value = apiObject(
            "/repos/" + enc(owner) + "/" + enc(repo) + "/git/blobs/" + enc(sha)
        )
        require(value.optString("encoding") == "base64") {
            "Unsupported GitHub blob encoding"
        }
        val bytes = Base64.decode(
            value.optString("content").replace("\n", "").replace("\r", ""),
            Base64.DEFAULT
        )
        require(bytes.size.toLong() <= MAX_FILE) { "GitHub blob exceeds per-file limit" }
        require(blobSha(bytes) == sha) { "GitHub blob SHA mismatch" }
        return bytes
    }

    private fun apiObject(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        tokenOverride: String? = null
    ): JSONObject {
        val value = api(path, method, body, tokenOverride)
        return value as? JSONObject
            ?: throw IllegalStateException("GitHub returned a non-object response")
    }

    private fun apiArray(path: String): JSONArray {
        val value = api(path, "GET", null, null)
        return value as? JSONArray
            ?: throw IllegalStateException("GitHub returned a non-array response")
    }

    private fun api(
        path: String,
        method: String,
        body: JSONObject?,
        tokenOverride: String?
    ): Any? {
        val builder = Request.Builder()
            .url("https://api.github.com" + path)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "RiftOS-NativeGit/" + BuildConfig.VERSION_NAME)
        val token = tokenOverride ?: secrets.get(TOKEN_KEY).orEmpty()
        if (token.isNotBlank()) builder.header("Authorization", "Bearer " + token)
        val request = when (method.uppercase()) {
            "GET" -> builder.get().build()
            "POST" -> builder.post(
                (body ?: JSONObject()).toString()
                    .toRequestBody("application/json".toMediaType())
            ).build()
            "PATCH" -> builder.patch(
                (body ?: JSONObject()).toString()
                    .toRequestBody("application/json".toMediaType())
            ).build()
            else -> throw IllegalArgumentException("Unsupported HTTP method: " + method)
        }
        http.newCall(request).execute().use { response ->
            val text = readApiBody(response.body)
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(text).optString("message")
                }.getOrDefault("")
                throw IllegalStateException(
                    "GitHub " + response.code +
                        if (detail.isNotBlank()) ": " + detail else ""
                )
            }
            if (response.code == 204 || text.isBlank()) return null
            return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
        }
    }

    private fun readApiBody(body: okhttp3.ResponseBody?): String {
        if (body == null) return ""
        val declared = body.contentLength()
        require(declared < 0L || declared <= MAX_API_RESPONSE_BYTES) {
            "GitHub response exceeds $MAX_API_RESPONSE_BYTES bytes"
        }
        val output = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                require(total <= MAX_API_RESPONSE_BYTES) {
                    "GitHub response exceeds $MAX_API_RESPONSE_BYTES bytes"
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private fun checkedCommitMessage(raw: String): String {
        val message = raw.trim()
        require(message.isNotBlank()) { "commit message is required" }
        require(message.toByteArray(Charsets.UTF_8).size <= MAX_COMMIT_MESSAGE_BYTES) {
            "commit message exceeds $MAX_COMMIT_MESSAGE_BYTES UTF-8 bytes"
        }
        return message
    }

    private fun readBoundedText(file: File, maxBytes: Long, label: String): String {
        require(file.isFile) { "$label is not a file" }
        require(file.length() <= maxBytes) { "$label exceeds $maxBytes bytes" }
        val bytes = file.readBytes()
        require(bytes.size.toLong() <= maxBytes) { "$label exceeds $maxBytes bytes" }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun requireToken(): String =
        secrets.get(TOKEN_KEY)?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "Push needs GitHub auth. Open RiftOS Settings."
            )

    private fun repoFile(meta: JSONObject, raw: String): File {
        val repoRoot = resolveFile(meta.getString("root"), "/")
        val path = safeRelative(raw)
        val file = File(repoRoot, path).canonicalFile
        require(file.path.startsWith(repoRoot.canonicalPath + File.separator)) {
            "Repository path escaped root"
        }
        return file
    }

    private fun ignored(path: String): Boolean =
        path == META_NAME || path == ".git" || path.startsWith(".git/")

    private fun safeRelative(raw: String): String {
        val path = raw.replace('\\', '/')
        require(path.isNotBlank() && !path.startsWith("/") && !path.contains('\u0000')) {
            "Unsafe repository path: " + raw
        }
        val parts = path.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Unsafe repository path: " + raw
        }
        return parts.joinToString("/")
    }

    private fun blobSha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(
            ("blob " + file.length() + "\u0000").toByteArray(Charsets.UTF_8)
        )
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun blobSha(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(
            ("blob " + bytes.size + "\u0000").toByteArray(Charsets.UTF_8)
        )
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateMeta(meta: JSONObject): JSONObject {
        require(meta.optString("format") == "riftgit-v3") { "Unsupported RiftGit metadata format" }
        val owner = meta.optString("owner")
        val repo = meta.optString("repo")
        require(owner.length in 1..100 && owner.matches(Regex("^[A-Za-z0-9_.-]+$"))) { "Invalid RiftGit owner" }
        require(repo.length in 1..100 && repo.matches(Regex("^[A-Za-z0-9_.-]+$"))) { "Invalid RiftGit repository" }
        require(meta.optString("full") == "$owner/$repo") { "RiftGit full-name metadata mismatch" }
        checkedBranch(meta.optString("branch"))
        val head = if (meta.isNull("headSha")) "" else meta.optString("headSha")
        require(head.isBlank() || head.matches(Regex("^[A-Fa-f0-9]{40}$|^[A-Fa-f0-9]{64}$"))) { "Invalid RiftGit head SHA" }
        if (meta.has("pendingMessage") && !meta.isNull("pendingMessage")) checkedCommitMessage(meta.optString("pendingMessage"))
        val tracked = meta.optJSONObject("tracked") ?: throw IllegalArgumentException("RiftGit tracked map is missing")
        require(tracked.length() <= MAX_FILES) { "RiftGit metadata exceeds tracked-file limit" }
        var trackedBytes = 0L
        tracked.keys().forEach { rawPath ->
            val path = safeRelative(rawPath)
            require(path == rawPath.replace('\\', '/')) { "RiftGit tracked path is not normalized" }
            val row = tracked.optJSONObject(rawPath) ?: throw IllegalArgumentException("Invalid RiftGit tracked entry: $rawPath")
            val sha = row.optString("blobSha")
            require(sha.matches(Regex("^[A-Fa-f0-9]{40}$|^[A-Fa-f0-9]{64}$"))) { "Invalid RiftGit blob SHA: $rawPath" }
            val size = row.optLong("size", -1L)
            require(size in 0..MAX_FILE) { "Invalid RiftGit tracked size: $rawPath" }
            trackedBytes += size
            require(trackedBytes <= MAX_TOTAL) { "RiftGit metadata exceeds tracked-byte limit" }
            require(row.optString("mode", "100644").matches(Regex("^[0-7]{6}$"))) { "Invalid RiftGit mode: $rawPath" }
        }
        return meta
    }

    private fun checkedBranch(raw: String): String {
        val branch = raw.trim()
        require(branch.length in 1..255 && !branch.contains('\u0000') && !branch.contains('\n') && !branch.contains('\r')) {
            "Invalid Git branch name"
        }
        require(!branch.startsWith('/') && !branch.endsWith('/') && !branch.contains("..") && !branch.contains("//")) { "Invalid Git branch name" }
        require(branch != "@" && !branch.contains("@{") && !branch.endsWith('.') && !branch.endsWith(".lock")) { "Invalid Git branch name" }
        require(branch.none { it.code < 0x20 || it == ' ' || it in charArrayOf('~','^',':','?','*','[','\\') }) { "Invalid Git branch name" }
        require(branch.split('/').none { it.startsWith('.') || it.isBlank() }) { "Invalid Git branch name" }
        return branch
    }

    private fun parseRepo(raw: String): Pair<String, String> {
        val clean = raw.trim()
            .removeSuffix(".git")
            .removePrefix("https://github.com/")
        val parts = clean.split('/')
        require(
            parts.size == 2 &&
                parts.all { it.length in 1..100 && it.matches(Regex("^[A-Za-z0-9_.-]+$")) }
        ) { "Use owner/repo or https://github.com/owner/repo" }
        return parts[0] to parts[1]
    }

    private fun resolveDisplay(cwd: String, raw: String): String {
        var value = raw.trim().replace('\\', '/')
        if (value == "~" || value.startsWith("~/")) {
            value = "/D:/Users/Default" + value.drop(1)
        }
        if (Regex("^[A-Za-z]:($|/)").containsMatchIn(value)) value = "/" + value
        if (!value.startsWith("/")) value = cwd.trimEnd('/') + "/" + value
        return normalizeDisplay(value)
    }

    private fun normalizeDisplay(raw: String): String =
        RiftVolumePaths.normalizeDisplay(raw)

    private fun resolveFile(raw: String, cwd: String): File {
        val display = resolveDisplay(cwd, raw)
        val relative =
            if (
                display.startsWith("/C:", true) ||
                display.startsWith("/D:", true)
            ) RiftVolumePaths.resolveRelative(display)
            else display.trimStart('/')
        val file =
            if (relative.isBlank()) root
            else File(root, relative).canonicalFile
        require(
            file == root ||
                file.path.startsWith(root.path + File.separator)
        ) { "Path escaped RiftFS" }
        return file
    }

    private fun displayPath(file: File): String {
        val relative =
            file.canonicalFile.relativeTo(root).invariantSeparatorsPath
        return if (relative.isBlank()) "/" else "/" + relative
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun checkpoint(display: String, reason: String, headSha: String) {
        if (display != "/workspace" && !display.startsWith("/workspace/")) return
        val gitRoot = display.removePrefix("/workspace/").takeIf { it.isNotBlank() }
        runCatching {
            RiftWorkspaceRecords.get(appContext).checkpoint(
                JSONObject()
                    .put("reason", reason)
                    .put("gitRoot", gitRoot ?: "")
                    .put("gitHeadSha", headSha)
            )
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temp = File(
            target.parentFile,
            "." + target.name + ".tmp-" + UUID.randomUUID().toString()
        )
        val backup = File(
            target.parentFile,
            "." + target.name + ".backup-" + UUID.randomUUID().toString()
        )
        temp.writeBytes(bytes)
        var backedUp = false
        try {
            if (target.exists()) {
                require(target.isFile) { "Git metadata target is not a file" }
                require(target.renameTo(backup)) {
                    "Could not stage existing file for replacement"
                }
                backedUp = true
            }
            require(temp.renameTo(target)) { "Atomic Git metadata write failed" }
            if (backedUp) backup.delete()
        } catch (error: Throwable) {
            temp.delete()
            if (backedUp && !target.exists() && !backup.renameTo(target)) {
                throw IllegalStateException(
                    "Git metadata write failed and previous file could not be restored: ${backup.absolutePath}",
                    error
                )
            }
            throw error
        }
    }
}
