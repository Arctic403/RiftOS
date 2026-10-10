package com.riftos.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors

/**
 * C1.4-B trusted NATIVE system-window consent, not a RAPP/terminal command.
 * Core checks actual Binder PID + installed signer + exact operation/target;
 * UI merely shows the Core-issued scope and forwards explicit user decisions.
 * No actual high-privilege effects are available until C1.4-C.
 */
internal class RiftNativeAdminApprovals(
    private val activity: Activity,
    private val desktop: RiftNativeDesktop,
    private val core: RiftShellCoreClient?
) {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rift-admin-consent-ui").apply { isDaemon = true }
    }
    private var operation = "system.fs.read"
    private var target = "/C:/System"
    private var scopeText: TextView? = null
    private var visible = false
    private var currentTicket: String? = null
    private var statusView: TextView? = null
    private var generation = 0L
    private var selectedProbeUri: Uri? = null
    private var selectedRiftFsProbeName: String? = null
    private var stagedProbeSha: String? = null
    private data class ModuleChoice(val manifestText: String, val manifest: RiftCoreModuleManifest)
    private var selectedModule: ModuleChoice? = null
    private var stagedModule: RiftCoreModuleManifest? = null
    companion object {
        const val PROBE_PICK_REQUEST = 0x6A41
        private const val PROBE_RIFTFS_PATH = "/D:/Builds/Modules"
        private const val PROBE_RIFTFS_RELATIVE = "documents/builds/Modules"
        private const val PROBE_MAX_BYTES = 32L * 1024 * 1024
        private val PROBE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}\\.dex$")
    }


    fun open() {
        desktop.handle("desktop.window.open", JSONObject()
            .put("id", "admin-permissions").put("title", "Admin Approvals")
            .put("kicker", "CORE AUTHORITY / NO SYSTEM EFFECTS"))
        visible = true
        generation++
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(0xff101923.toInt())
        }
        fun addText(message: String, size: Float = 14f): TextView =
            TextView(activity).apply {
                text = message
                textSize = size
                setTextColor(0xffe7eef5.toInt())
                setPadding(4, 10, 4, 12)
                root.addView(this, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        addText("RiftOS administrator approvals", 19f).typeface = Typeface.DEFAULT_BOLD
        addText("C1.4-B/C1/C2-A proofs: fixed temporary C: canary or an isolated " +
            "EMPTY runtime registry proof; both always roll back. No provider install, " +
            "real registration, production package change or process termination.")
        scopeText = addText("Test scope: $operation on $target\n" +
            "Core controls the caller, signer, scope, 45-second expiry and one-time use.")
        statusView = addText("No pending administrator request.")
        fun button(label: String, clicked: () -> Unit) {
            root.addView(Button(activity).apply {
                text = label
                isAllCaps = false
                setOnClickListener { clicked() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        button("Toggle isolated rollback proof scope") {
            if (currentTicket != null) {
                show("Revoke or consume the prior approval before switching scope.")
                return@button
            }
            if (operation == "system.fs.read") {
                operation = RiftCoreAdminRollbackProof.OPERATION
                target = RiftCoreAdminRollbackProof.TARGET
            } else {
                operation = "system.fs.read"
                target = "/C:/System"
            }
            scopeText?.text = "Test scope: $operation on $target\n" +
                "Only the fixed write proof can mutate a temporary C: canary, " +
                "with mandatory journalled rollback."
            show("Selected fixed test operation $operation; no ticket issued yet.")
        }
        button("Select isolated runtime registry proof scope") {
            if (currentTicket != null) {
                show("Revoke or consume the prior approval before switching scope.")
                return@button
            }
            operation = RiftCoreAdminRegistryProof.OPERATION
            target = RiftCoreAdminRegistryProof.TARGET
            scopeText?.text = "Test scope: $operation on $target\n" +
                "Core may briefly create a signer-stamped EMPTY registry, " +
                "then must restore the absent registry. No provider is registered."
            show("Selected isolated empty runtime registry proof. No ticket issued.")
        }
        button("Request scoped administrator test") { request() }
        button("Consume once — no privileged effect") { consume() }
        button("Execute Core write and rollback once") { executeRollbackProof() }
        button("Execute Core empty registry and rollback once") { executeRegistryProof() }
        button("Revoke current approval") { revoke() }
        button("Discover installed runtime candidates (read-only)") { discoverProviders() }
        button("Select trusted module manifest from RiftOS Files") { pickGenericModule() }
        button("Stage selected generic module once") { stageGenericModule() }
        button("Prove Core consumed stage ticket replay") { proveCoreStageReplay() }
        button("Select digest-bound generic activation") { selectGenericActivation() }
        button("Activate selected generic module once") { activateGenericModule() }
        button("Select bounded module recovery proof") { selectGenericRecoveryProof() }
        button("Prove interrupted startup recovery once") { proveGenericRecovery() }
        button("Select ProbeV1 DEX from RiftOS Files") { pickRiftFsProbeDex() }
        button("Select external ProbeV1 DEX (Android picker)") { pickProbeDex() }
        button("Stage selected DEX using one-use approval") { stageProbe() }
        button("Select digest-bound probe activation scope") { selectProbeActivation() }
        button("Activate and start isolated probe once") { activateProbe() }
                button("Refresh Core ticket status") { refresh() }
        // More distinct test modes must remain reachable on small phones.
        desktop.attachContent("admin-permissions", ScrollView(activity).apply {
            isFillViewport = true
            addView(root)
        })
        refresh()
    }

    private fun show(message: String) {
        if (!visible) return
        statusView?.text = message.take(800)
    }

    private fun perform(block: () -> String) {
        val serial = generation
        worker.execute {
            val result = runCatching(block).getOrElse { "Core denied: ${it.message}" }
            activity.runOnUiThread {
                if (visible && serial == generation && !activity.isDestroyed) show(result)
            }
        }
    }

    /** Android owns document selection; UI never trusts caller-supplied paths. */
    @Suppress("DEPRECATION")
    private fun pickProbeDex() {
        if (currentTicket != null) {
            show("Revoke or consume the earlier approval before choosing another DEX.")
            return
        }
        selectedProbeUri = null
        selectedRiftFsProbeName = null
        stagedProbeSha = null
        operation = RiftCoreAdminConsent.PROBE_STAGE
        target = RiftCoreAdminConsent.PROBE_TARGET
        scopeText?.text = "Fixed external probe: select a separate ProbeV1 DEX. " +
            "Staging needs approval and cannot install a runtime provider."
        try {
            activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, PROBE_PICK_REQUEST)
            show("Choose the separately compiled ProbeV1 .dex file.")
        } catch (error: Exception) {
            show("System picker unavailable: " + (error.message ?: "unknown"))
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != PROBE_PICK_REQUEST) return false
        if (!visible || currentTicket != null) return true
        val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
        if (uri == null || uri.scheme != "content") {
            show("DEX selection cancelled or unsupported; choose a document provider.")
            return true
        }
        selectedProbeUri = uri
        selectedRiftFsProbeName = null
        stagedProbeSha = null
        operation = RiftCoreAdminConsent.PROBE_STAGE
        target = RiftCoreAdminConsent.PROBE_TARGET
        scopeText?.text = "External ProbeV1 DEX selected. Request scoped administrator test " +
            "and Allow once before staging. No activation yet."
        show("Selected a document-provided DEX. Approval is still required.")
        return true
    }


    /**
     * Trusted native source picker for a fixed user-build directory. No RAPP,
     * shell command or arbitrary path is accepted as an administrator input.
     * Core still receives only a read-only FD and consumes a separate ticket.
     */
    private fun probeRiftFsDirectory(): File {
        val relative = RiftVolumePaths.resolveRelative(PROBE_RIFTFS_PATH)
        require(relative == PROBE_RIFTFS_RELATIVE) {
            "Unexpected RiftFS build-directory mapping"
        }
        val rawRoot = File(activity.filesDir, "riftfs")
        require(!Files.isSymbolicLink(rawRoot.toPath())) { "Symlinked RiftFS root" }
        val riftfs = rawRoot.canonicalFile
        var cursor = riftfs
        for (name in relative.split('/')) {
            cursor = File(cursor, name)
            require(!Files.isSymbolicLink(cursor.toPath())) {
                "Symlinked RiftFS build-directory segment"
            }
        }
        require(cursor.isDirectory && cursor.canonicalFile.toPath()
            .startsWith(riftfs.toPath())) { "RiftFS module directory is unavailable" }
        return cursor
    }

    private fun verifiedProbeRiftFsFile(name: String): File {
        require(name.matches(PROBE_NAME)) { "Invalid DEX filename" }
        val directory = probeRiftFsDirectory()
        val file = File(directory, name)
        require(!Files.isSymbolicLink(file.toPath()) &&
            file.isFile && file.canonicalFile.parentFile == directory.canonicalFile &&
            file.length() in 1L..PROBE_MAX_BYTES) {
            "External DEX must be a nonempty regular file under $PROBE_RIFTFS_PATH (max 32 MiB)"
        }
        return file
    }

    private fun pickRiftFsProbeDex() {
        if (currentTicket != null) {
            show("Revoke or consume the earlier approval before choosing another DEX.")
            return
        }
        selectedProbeUri = null
        selectedRiftFsProbeName = null
        stagedProbeSha = null
        operation = RiftCoreAdminConsent.PROBE_STAGE
        target = RiftCoreAdminConsent.PROBE_TARGET
        scopeText?.text = "Fixed external ProbeV1 staged from $PROBE_RIFTFS_PATH. " +
            "Core reads a verified DEX descriptor only after separate one-use approval."
        val files = runCatching {
            val directory = probeRiftFsDirectory()
            val children = directory.listFiles() ?: error("Could not list RiftFS modules")
            require(children.size <= 256) { "Too many entries in module folder" }
            children.filter { entry ->
                entry.name.matches(PROBE_NAME) &&
                    runCatching { verifiedProbeRiftFsFile(entry.name) }.isSuccess
            }.sortedBy { it.name }.take(32)
        }.getOrElse { error ->
            show("RiftFS module folder unavailable: ${error.message}")
            return
        }
        if (files.isEmpty()) {
            show("No verified .dex files in $PROBE_RIFTFS_PATH. Build ProbeV1 first.")
            return
        }
        val serial = generation
        val names = files.map { it.name }
        AlertDialog.Builder(activity)
            .setTitle("Choose RiftOS module from $PROBE_RIFTFS_PATH")
            .setItems(files.map { "${it.name} · ${it.length()} bytes" }.toTypedArray()) { _, which ->
                if (!visible || generation != serial || currentTicket != null) return@setItems
                val chosen = names.getOrNull(which) ?: return@setItems
                runCatching { verifiedProbeRiftFsFile(chosen) }
                    .onSuccess {
                        selectedProbeUri = null
                        selectedRiftFsProbeName = chosen
                        show("Selected $PROBE_RIFTFS_PATH/$chosen. Request one-use staging approval.")
                    }
                    .onFailure { show("DEX selection denied: ${it.message}") }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun readGenericModuleChoice(directory: File): ModuleChoice {
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()) &&
            directory.canonicalFile.parentFile == probeRiftFsDirectory().canonicalFile) {
            "Generic module must live directly under the RiftOS Modules directory"
        }
        val file = File(directory, "module.json")
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) &&
            file.length() in 1L..RiftCoreModuleManifest.MAX_MANIFEST_BYTES.toLong()) {
            "Invalid generic module manifest"
        }
        val data = file.readBytes()
        val manifest = RiftCoreModuleManifest.parse(data)
        require(manifest.id == directory.name) { "Module folder and manifest identity differ" }
        val payload = File(directory, manifest.payload)
        require(payload.isFile && !Files.isSymbolicLink(payload.toPath()) &&
            payload.canonicalFile.parentFile == directory.canonicalFile &&
            payload.length() in 1L..RiftCoreModuleManifest.MAX_MODULE_BYTES) {
            "Module DEX is missing or outside build folder"
        }
        return ModuleChoice(String(data, Charsets.UTF_8), manifest)
    }

    /** Generic UI discovers manifests; no app-specific names live in Core. */
    private fun pickGenericModule() {
        if (currentTicket != null) {
            show("Revoke or consume earlier approval before switching module scope.")
            return
        }
        selectedModule = null
        stagedModule = null
        val choices = runCatching {
            val root = probeRiftFsDirectory()
            val files = root.listFiles() ?: error("RiftOS Modules folder cannot be listed")
            require(files.size <= 256) { "Too many build-folder entries" }
            files.filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
                .mapNotNull { dir -> runCatching { readGenericModuleChoice(dir) }.getOrNull() }
                .sortedBy { it.manifest.id }
                .take(32)
        }.getOrElse { failure ->
            show("Cannot discover generic modules: " + failure.message)
            return
        }
        if (choices.isEmpty()) {
            show("No valid module.json + DEX pairs in /D:/Builds/Modules/<module-id>/")
            return
        }
        val serial = generation
        AlertDialog.Builder(activity)
            .setTitle("Trusted modules · same Android UID as RiftOS")
            .setItems(choices.map {
                it.manifest.name + " [" + it.manifest.id + "] v" + it.manifest.version
            }.toTypedArray()) { _, position ->
                if (!visible || generation != serial || currentTicket != null) return@setItems
                val chosen = choices.getOrNull(position) ?: return@setItems
                runCatching {
                    val fresh = readGenericModuleChoice(File(
                        probeRiftFsDirectory(), chosen.manifest.id))
                    require(fresh.manifest.identityDigest() ==
                        chosen.manifest.identityDigest()) {
                        "Module changed during selection"
                    }
                    fresh
                }.onSuccess { choice ->
                    selectedModule = choice
                    operation = RiftCoreAdminConsent.MODULE_STAGE
                    target = "module://stage/" + choice.manifest.identityDigest()
                    scopeText?.text = "Generic module " + choice.manifest.id +
                        " v" + choice.manifest.version +
                        "\nDEX SHA-256 " + choice.manifest.sha256 +
                        "\nEntry: " + choice.manifest.entrypoint +
                        "\nStaging imports immutable bytes only; activation requires " +
                        "a DIFFERENT approval. DEX service code shares RiftOS Android UID."
                    show("Selected " + choice.manifest.id + ". " +
                        "Request one-use approval to stage; no execution yet.")
                }.onFailure { failure ->
                    show("Generic module selection rejected: " + failure.message)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun stageGenericModule() {
        val choice = selectedModule ?: run {
            show("Select a valid generic module first."); return
        }
        if (operation != RiftCoreAdminConsent.MODULE_STAGE ||
            target != "module://stage/" + choice.manifest.identityDigest()) {
            show("Select generic stage scope before requesting approval."); return
        }
        if (!activity.hasWindowFocus()) {
            show("Generic stage requires trusted foreground Admin Approvals."); return
        }
        val ticket = currentTicket ?: run { show("No module stage approval ticket."); return }
        val client = core ?: run { show("Core IPC unavailable."); return }
        perform {
            val fresh = readGenericModuleChoice(File(
                probeRiftFsDirectory(), choice.manifest.id))
            require(fresh.manifest.identityDigest() ==
                choice.manifest.identityDigest()) {
                "Module changed since user-approved stage request"
            }
            val dexFile = File(File(probeRiftFsDirectory(), fresh.manifest.id),
                fresh.manifest.payload)
            val descriptor = ParcelFileDescriptor.open(
                dexFile, ParcelFileDescriptor.MODE_READ_ONLY)
            descriptor.use { fd ->
                val result = client.adminConsent("execute-module-stage", ticket,
                    RiftCoreAdminConsent.MODULE_STAGE,
                    "module://stage/" + choice.manifest.identityDigest(),
                    dexFd = fd, manifestText = fresh.manifestText)
                require(result.getBoolean("staged") &&
                    !result.optBoolean("activated", true) &&
                    result.getString("manifestDigest") ==
                        choice.manifest.identityDigest()) {
                    "Core did not confirm exact generic module stage"
                }
                activity.runOnUiThread {
                    if (currentTicket == ticket) currentTicket = null
                    selectedModule = null
                    stagedModule = choice.manifest
                }
                "Core staged generic module " + choice.manifest.id +
                    " SHA " + choice.manifest.sha256.take(16) +
                    "… WITHOUT execution. Request separate activation approval."
            }
        }
    }

    /**
     * Trusted-UI-only direct Core bearer replay regression. The SAME ticket,
     * scope, validated manifest and read-only DEX are sent twice over Binder.
     * The first call stages, the second MUST be rejected inside Core.
     */
    private fun proveCoreStageReplay() {
        val choice = selectedModule ?: run {
            show("Select a generic module before Core replay proof."); return
        }
        val digest = choice.manifest.identityDigest()
        val expected = "module://stage/" + digest
        if (operation != RiftCoreAdminConsent.MODULE_STAGE || target != expected) {
            show("Select exact module.stage scope before Core replay proof."); return
        }
        if (!activity.hasWindowFocus()) {
            show("Core ticket replay proof requires trusted foreground window."); return
        }
        val ticket = currentTicket ?: run {
            show("Core replay proof requires a fresh one-use stage approval."); return
        }
        val client = core ?: run { show("Core IPC unavailable."); return }
        perform {
            val fresh = readGenericModuleChoice(
                File(probeRiftFsDirectory(), choice.manifest.id))
            require(fresh.manifest.identityDigest() == digest) {
                "Replay proof manifest changed after administrator approval"
            }
            val dexFile = File(File(probeRiftFsDirectory(), fresh.manifest.id),
                fresh.manifest.payload)
            fun stageWithSameBearer(): JSONObject {
                ParcelFileDescriptor.open(dexFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    return client.adminConsent("execute-module-stage", ticket,
                        RiftCoreAdminConsent.MODULE_STAGE, expected,
                        dexFd = fd, manifestText = fresh.manifestText)
                }
            }
            val first = stageWithSameBearer()
            require(first.optBoolean("staged") &&
                first.optString("manifestDigest") == digest &&
                !first.optBoolean("activated", true)) {
                "First Core stage did not attest exact revision"
            }
            // Core has already consumed this bearer; UI has deliberately
            // not cleared the local reference before the second Binder call.
            val replayFailure = runCatching { stageWithSameBearer() }.exceptionOrNull()
            activity.runOnUiThread {
                if (currentTicket == ticket) currentTicket = null
                selectedModule = null
                stagedModule = choice.manifest
            }
            require(replayFailure != null) {
                "SECURITY FAILURE: Core accepted the same approved stage bearer twice"
            }
            require(replayFailure.message.orEmpty().contains(
                "Admin ticket absent or used")) {
                "Replay failed, but not with expected Core consumed-bearer rejection: " +
                    replayFailure.javaClass.simpleName
            }
            "Core direct replay PASS: first digest-bound stage accepted; " +
                "second protected Binder call with SAME consumed bearer denied. " +
                "Module remains staged, not activated."
        }
    }

    private fun selectGenericActivation() {
        if (currentTicket != null) {
            show("Revoke or consume previous approval before changing scope.")
            return
        }
        val module = stagedModule ?: run {
            show("Stage a user-approved generic module before activation.")
            return
        }
        operation = RiftCoreAdminConsent.MODULE_ACTIVATE
        target = "module://activate/" + module.id + "/" + module.identityDigest()
        scopeText?.text = "Activate trusted module " + module.id +
            " v" + module.version + "\nDEX SHA " + module.sha256 +
            "\nEntry " + module.entrypoint +
            "\nThis code executes in nonexported :riftModuleHost with the SAME " +
            "RiftOS app UID; it is not a sandbox. Fresh one-use user approval required."
        show("Request a NEW approval for this exact staged module identity.")
    }

    private fun activateGenericModule() {
        val module = stagedModule ?: run {
            show("Stage generic module first."); return
        }
        val expected = "module://activate/" + module.id + "/" + module.identityDigest()
        if (operation != RiftCoreAdminConsent.MODULE_ACTIVATE || target != expected) {
            show("Select exact staged generic module activation scope first."); return
        }
        if (!activity.hasWindowFocus()) {
            show("Generic activation requires trusted foreground window."); return
        }
        val ticket = currentTicket ?: run {
            show("No fresh generic activation approval ticket."); return
        }
        val client = core ?: run { show("Core IPC unavailable."); return }
        perform {
            val result = client.adminConsent("execute-module-activate", ticket,
                RiftCoreAdminConsent.MODULE_ACTIVATE, expected)
            activity.runOnUiThread {
                if (currentTicket == ticket) currentTicket = null
            }
            require(result.optBoolean("activated") &&
                result.optBoolean("serviceStartRequested") &&
                result.optString("id") == module.id &&
                result.optString("manifestDigest") == module.identityDigest()) {
                "Core did not confirm matching module activation request"
            }
            "Core requested trusted generic module " + module.id + " v" +
                module.version + " in separate :riftModuleHost process. " +
                "Check core status moduleHost.proofPresent for actual execution."
        }
    }

    private fun selectGenericRecoveryProof() {
        if (currentTicket != null) {
            show("Revoke or consume prior approval before changing recovery scope.")
            return
        }
        val module = stagedModule ?: run {
            show("Stage the already active known-good module before recovery proof.")
            return
        }
        operation = RiftCoreAdminConsent.MODULE_RECOVERY_PROOF
        target = "module://recover/" + module.id + "/" + module.identityDigest()
        scopeText?.text = "Bounded Core interrupted-start recovery proof\n" +
            "Active module must ALREADY be: " + module.id +
            " v" + module.version + "\nSHA " + module.sha256 +
            "\nNo process kill, no DEX execution, no new module activation. " +
            "Core simulates its pending startup journal and restores exact prior " +
            "active revision and its authenticated historical execution receipt."
        show("Request a NEW scoped approval to prove Core journal recovery.")
    }

    private fun proveGenericRecovery() {
        val module = stagedModule ?: run {
            show("Stage known-good active module before recovery proof."); return
        }
        val expected = "module://recover/" + module.id + "/" + module.identityDigest()
        if (operation != RiftCoreAdminConsent.MODULE_RECOVERY_PROOF || target != expected) {
            show("Select bounded module recovery proof scope first."); return
        }
        if (!activity.hasWindowFocus()) {
            show("Recovery proof requires trusted foreground Admin Approvals."); return
        }
        val ticket = currentTicket ?: run {
            show("No one-use Core recovery proof ticket."); return
        }
        val client = core ?: run { show("Core IPC unavailable."); return }
        perform {
            val result = client.adminConsent("execute-module-recovery-proof", ticket,
                RiftCoreAdminConsent.MODULE_RECOVERY_PROOF, expected)
            activity.runOnUiThread {
                if (currentTicket == ticket) currentTicket = null
            }
            require(result.optBoolean("recovered") &&
                result.optBoolean("simulatedPendingStartup") &&
                result.optBoolean("priorProofRestored") &&
                !result.optBoolean("pendingStartup", true) &&
                !result.optBoolean("coreProcessTerminated", true) &&
                !result.optBoolean("moduleExecuted", true) &&
                result.optString("id") == module.id &&
                result.optString("manifestDigest") == module.identityDigest() &&
                result.optString("previousNonce") ==
                    result.optString("restoredNonce")) {
                "Core recovery proof did not restore exact previous module and receipt"
            }
            "Core interrupted startup journal/rollback PASS: prior active " +
                module.id + " and original execution proof nonce restored. " +
                "No process killed or external code executed."
        }
    }

    private fun selectProbeActivation() {
        if (currentTicket != null) {
            show("Revoke or consume the previous approval first.")
            return
        }
        val sha = stagedProbeSha ?: run {
            show("Stage the approved external DEX first.")
            return
        }
        operation = RiftCoreAdminConsent.PROBE_ACTIVATE
        target = "bootstrap://probe/" + sha
        scopeText?.text = "Activation is bound to SHA-256 " + sha.take(16) +
            "… and launches only the nonexported isolated probe Service."
        show("Request a new scoped approval for this exact staged SHA-256.")
    }

    private fun stageProbe() {
        if (operation != RiftCoreAdminConsent.PROBE_STAGE ||
            target != RiftCoreAdminConsent.PROBE_TARGET) {
            show("Select the external ProbeV1 DEX stage scope first.")
            return
        }
        if (!activity.hasWindowFocus()) {
            show("Probe staging requires the trusted window foreground.")
            return
        }
        val uri = selectedProbeUri
        val riftFsName = selectedRiftFsProbeName
        if ((uri == null) == (riftFsName == null)) {
            show("Choose exactly one DEX source first (RiftOS Files or Android picker).")
            return
        }
        val ticket = currentTicket ?: run { show("No stage approval ticket."); return }
        val client = core ?: run { show("Core IPC unavailable."); return }
        perform {
            // Core sees a read-only Android FD, never a RAPP-controlled path.
            val descriptor = if (uri != null) {
                activity.contentResolver.openFileDescriptor(uri, "r")
                    ?: error("Cannot open the selected Android DEX read-only")
            } else {
                ParcelFileDescriptor.open(
                    verifiedProbeRiftFsFile(requireNotNull(riftFsName)),
                    ParcelFileDescriptor.MODE_READ_ONLY
                )
            }
            descriptor.use { fd ->
                val result = client.adminConsent("execute-probe-stage", ticket,
                    RiftCoreAdminConsent.PROBE_STAGE,
                    RiftCoreAdminConsent.PROBE_TARGET, dexFd = fd)
                val sha = result.getString("sha256")
                require(result.getBoolean("staged") &&
                    sha.matches(Regex("^[0-9a-f]{64}$"))) {
                    "Core did not verify a staged DEX"
                }
                activity.runOnUiThread {
                    if (currentTicket == ticket) currentTicket = null
                    stagedProbeSha = sha
                    selectedProbeUri = null
                    selectedRiftFsProbeName = null
                }
                "Core staged immutable external DEX SHA-256 " + sha.take(16) +
                    "… No execution. Select digest-bound activation, request a NEW approval."
            }
        }
    }

    private fun activateProbe() {
        if (operation != RiftCoreAdminConsent.PROBE_ACTIVATE ||
            !target.matches(Regex("^bootstrap://probe/[0-9a-f]{64}$"))) {
            show("Select digest-bound probe activation after staging first.")
            return
        }
        if (!activity.hasWindowFocus()) {
            show("Probe activation requires the trusted window foreground.")
            return
        }
        val ticket = currentTicket ?: run { show("No activation approval ticket."); return }
        val expectedTarget = target
        val client = core ?: run { show("Core IPC unavailable."); return }
        perform {
            val result = client.adminConsent("execute-probe-activate", ticket,
                RiftCoreAdminConsent.PROBE_ACTIVATE, expectedTarget)
            activity.runOnUiThread { if (currentTicket == ticket) currentTicket = null }
            if (result.optBoolean("activated") &&
                result.optBoolean("probeProcessRequested")) {
                "Core activated SHA " + result.getString("sha256").take(16) +
                    "… and requested the separate probe service. Verify fresh process proof in Core."
            } else "Core did not confirm probe activation."
        }
    }

    private fun request() {
        if (!activity.hasWindowFocus()) {
            show("Admin test requires the trusted graphical window in foreground.")
            return
        }
        val client = core ?: run {
            show("No authenticated production Core IPC client.")
            return
        }
        val serial = generation
        val requestedOperation = operation
        val requestedTarget = target
        // Resolve any earlier approval before issuing a fresh test request;
        // a consumed/expired ticket is already absent in Core.
        val previous = currentTicket
        currentTicket = null
        worker.execute {
            if (previous != null) {
                runCatching { client.adminConsent("revoke", ticket = previous) }
            }
            val response = runCatching {
                client.adminConsent("request", operation = requestedOperation, target = requestedTarget)
            }
            activity.runOnUiThread {
                if (!visible || serial != generation || activity.isFinishing ||
                    operation != requestedOperation || target != requestedTarget) {
                    response.getOrNull()?.optString("ticket")?.let { ticket ->
                        worker.execute {
                            runCatching { client.adminConsent("revoke", ticket = ticket) }
                        }
                    }
                    return@runOnUiThread
                }
                val value = response.getOrElse { error ->
                    show("Core refused administrator request: ${error.message}")
                    return@runOnUiThread
                }
                val ticket = value.getString("ticket")
                currentTicket = ticket
                show("Core issued a pending test ticket. Confirm the exact scope below.")
                val rollback = requestedOperation == RiftCoreAdminRollbackProof.OPERATION
                val explanation = if (rollback) {
                    "Allow ONE isolated Core C: canary write, verification and " +
                        "mandatory immediate rollback? No production file is changed."
                } else if (requestedOperation == RiftCoreAdminRegistryProof.OPERATION) {
                    "Allow ONE Core-only EMPTY runtime registry creation, signer " +
                        "verification and mandatory rollback? This does NOT " +
                        "register, enable or install any runtime provider."
                } else if (requestedOperation == RiftCoreAdminConsent.MODULE_STAGE) {
                    "Allow ONE content-addressed import of this exact trusted module " +
                        "manifest and matching DEX? STAGE ONLY, no execution or provider " +
                        "registration. A later activation would execute code with the " +
                        "RiftOS APK Android UID, not an isolated security sandbox."
                } else if (requestedOperation == RiftCoreAdminConsent.MODULE_RECOVERY_PROOF) {
                    "Allow ONE exact Core module interrupted-start JOURNAL recovery " +
                        "simulation? The selected module must already be active. " +
                        "No process is killed, no DEX is executed and no module " +
                        "ID is changed. Core must restore its earlier proof nonce."
                } else if (requestedOperation == RiftCoreAdminConsent.MODULE_ACTIVATE) {
                    "Allow ONE activation of the exact SHA-bound trusted module in " +
                        "nonexported :riftModuleHost? It executes with the RiftOS " +
                        "Android UID. This is NOT an untrusted-code sandbox; Core " +
                        "and Shell remain embedded. Rollback applies to startup failure."
                } else if (requestedOperation == RiftCoreAdminConsent.PROBE_STAGE) {
                    "Allow ONE fixed external ProbeV1 DEX to be staged read-only " +
                        "in Core app storage? No execution, provider registration or app installation."
                } else if (requestedOperation == RiftCoreAdminConsent.PROBE_ACTIVATE) {
                    "Allow ONE activation of the displayed exact SHA-256 revision " +
                        "in a separate nonexported probe Service? Core and Shell remain embedded."
                } else {
                    "Allow ONE no-effect authorization proof? " +
                        "This grants NO system-file access."
                }
                AlertDialog.Builder(activity)
                    .setTitle("RiftOS administrator consent — fixed-scope only")
                    .setMessage(explanation + "\n" +
                        "Operation: ${value.optString("operation")}\n" +
                        "Target: ${value.optString("target")}\n" +
                        "Expires in 45 seconds. Other privileged operations stay blocked.")
                    .setPositiveButton("Allow once") { _, _ -> decide(ticket, true) }
                    .setNegativeButton("Deny") { _, _ -> decide(ticket, false) }
                    .setNeutralButton("Cancel") { _, _ -> decide(ticket, false) }
                    .setOnCancelListener { decide(ticket, false) }
                    .show()
            }
        }
    }

    private fun decide(ticket: String, approved: Boolean) {
        if (!visible || currentTicket != ticket) return
        val client = core ?: return
        perform {
            val result = client.adminConsent("decide", ticket = ticket, approved = approved)
            if (!approved) activity.runOnUiThread {
                if (currentTicket == ticket) currentTicket = null
            }
            if (result.optBoolean("approved")) {
                if (operation == RiftCoreAdminRollbackProof.OPERATION) {
                    "Core approved ONE fixed C: canary write-and-rollback test. " +
                        "Execute it once before the 45-second expiry, or revoke."
                } else if (operation == RiftCoreAdminRegistryProof.OPERATION) {
                    "Core approved ONE empty registry/rollback proof; no provider installation. " +
                        "Execute before 45-second expiry or revoke."
                } else if (operation == RiftCoreAdminConsent.MODULE_STAGE) {
                    "Core approved ONE exact manifest-bound generic module stage. " +
                        "Execute before 45-second expiry; no code runs during staging."
                } else if (operation == RiftCoreAdminConsent.MODULE_RECOVERY_PROOF) {
                    "Core approved ONE bounded startup-journal recovery proof. " +
                        "Execute within 45 seconds; verify same prior nonce in Core status."
                } else if (operation == RiftCoreAdminConsent.MODULE_ACTIVATE) {
                    "Core approved ONE exact staged module activation. Start within 45 " +
                        "seconds; confirm actual execution using core status.moduleHost."
                } else if (operation == RiftCoreAdminConsent.PROBE_STAGE) {
                    "Core approved ONE selected ProbeV1 DEX staging. Execute staging within 45 seconds."
                } else if (operation == RiftCoreAdminConsent.PROBE_ACTIVATE) {
                    "Core approved ONE SHA-256-bound isolated probe activation. Execute within 45 seconds."
                } else "Core approved ONE no-effect ticket. Consume or revoke it before expiry."
            } else "Core denied/cancelled the request; zero elevated privileges."
        }
    }

    private fun consume() {
        val ticket = currentTicket ?: run { show("No ticket to consume."); return }
        val client = core ?: return
        perform {
            val result = client.adminConsent("consume-proof", ticket,
                operation, target)
            if (result.optBoolean("consumed") &&
                !result.optBoolean("executedPrivilegedEffect", true)) {
                "One-use ticket consumed. NO privileged effect executed. " +
                    "Repeat Consume to confirm replay denial."
            } else "Core declined consumption."
        }
    }

    private fun executeRollbackProof() {
        if (operation != RiftCoreAdminRollbackProof.OPERATION ||
            target != RiftCoreAdminRollbackProof.TARGET) {
            show("Select the isolated Core rollback test scope first.")
            return
        }
        if (!activity.hasWindowFocus()) {
            show("Core rollback proof requires the trusted window foreground.")
            return
        }
        val bearer = currentTicket ?: run { show("No approved rollback ticket."); return }
        val client = core ?: run { show("No Core IPC available."); return }
        perform {
            val response = client.adminConsent("execute-rollback-proof",
                bearer, RiftCoreAdminRollbackProof.OPERATION,
                RiftCoreAdminRollbackProof.TARGET)
            if (response.optBoolean("transactionCommitted") &&
                response.optBoolean("rolledBack") &&
                !response.optBoolean("pendingJournal", true)) {
                activity.runOnUiThread { if (currentTicket == bearer) currentTicket = null }
                "Core wrote, verified and rolled back the isolated C: test canary. " +
                    "No pending journal, no permanent file, ticket consumed."
            } else {
                "Core did not verify a completed rollback transaction."
            }
        }
    }

    /**
     * C1.4-C2-A: never allows a caller-supplied provider, registry path or
     * signer pin. Only the fixed Core journalled empty registry transaction.
     */
    private fun executeRegistryProof() {
        if (operation != RiftCoreAdminRegistryProof.OPERATION ||
            target != RiftCoreAdminRegistryProof.TARGET) {
            show("Select the isolated empty runtime registry proof scope first.")
            return
        }
        if (!activity.hasWindowFocus()) {
            show("Core registry proof requires trusted window foreground.")
            return
        }
        val bearer = currentTicket ?: run { show("No approved registry ticket."); return }
        val client = core ?: run { show("No Core IPC available."); return }
        perform {
            val response = client.adminConsent("execute-registry-proof", bearer,
                RiftCoreAdminRegistryProof.OPERATION, RiftCoreAdminRegistryProof.TARGET)
            if (response.optBoolean("transactionCommitted") &&
                response.optBoolean("rolledBack") &&
                response.optBoolean("registryRestored") &&
                !response.optBoolean("providerRegistered", true) &&
                !response.optBoolean("pendingJournal", true)) {
                activity.runOnUiThread { if (currentTicket == bearer) currentTicket = null }
                "Core verified its installed signer, wrote and removed the EMPTY " +
                    "registry canary. No provider registered, no journal or registry left."
            } else {
                // The approval has already been consumed in Core. Report the
                // sanitized failure stage instead of hiding Android errors.
                activity.runOnUiThread { if (currentTicket == bearer) currentTicket = null }
                val stage = response.optString("failureStage", "unknown").take(48)
                val kind = response.optString("failureType", "unknown").take(48)
                val errno = response.optInt("failureErrno", 0)
                val type = if (errno != 0) "$kind, errno=$errno" else kind
                "Core C2-A registry transaction FAILED at $stage ($type). " +
                    "No success claimed; inspect Core registry/journal status."
            }
        }
    }

    private fun revoke() {
        val ticket = currentTicket ?: run { show("No ticket to revoke."); return }
        val client = core ?: return
        perform {
            val result = client.adminConsent("revoke", ticket = ticket)
            if (result.optBoolean("revoked")) {
                activity.runOnUiThread { if (currentTicket == ticket) currentTicket = null }
                "Core revoked the ticket. No privileged effect executed."
            } else "Core rejected revocation."
        }
    }

    /**
     * C2-B1 never requests or approves runtime.register and never edits the
     * registry. This is a Core-attested read-only inventory from Android PM.
     */
    private fun discoverProviders() {
        val client = core ?: run { show("No authenticated Core IPC."); return }
        perform {
            val response = client.adminConsent("discover-providers")
            val candidates = response.getJSONArray("candidates")
            if (candidates.length() == 0) {
                "No eligible external runtime services installed; zero registrations."
            } else {
                buildString {
                    append("Eligible installed runtime services: ")
                    append(candidates.length())
                    append(". No registration performed. ")
                    for (index in 0 until minOf(4, candidates.length())) {
                        val item = candidates.getJSONObject(index)
                        append("\n")
                        append(item.getString("id")).append(" · ")
                        append(item.getString("executorKind")).append(" · ")
                        append(item.getString("package")).append("/")
                        append(item.getString("service"))
                    }
                    if (candidates.length() > 4) append("\n…more available")
                }
            }
        }
    }

    private fun refresh() {
        val client = core ?: run { show("No Core IPC. Administrator effects unavailable."); return }
        perform {
            val s = client.adminConsent("status")
            "Core pending: ${s.optInt("pending")}, approved unused: " +
                "${s.optInt("approvedUnconsumed")}; systemEffectsEnabled: " +
                "${s.optBoolean("systemEffectsEnabled", true)}"
        }
    }

    fun close() {
        visible = false
        generation++
        statusView = null
        scopeText = null
        currentTicket = null
        selectedProbeUri = null
        selectedRiftFsProbeName = null
        stagedProbeSha = null
        selectedModule = null
        stagedModule = null
        // Core invalidates ALL unconsumed approvals from the exact OS-attested
        // real shell PID on native window close, even if the UI lost its token.
        worker.execute {
            runCatching { core?.adminConsent("window-closed") }
        }
    }

    fun destroy() {
        close()
        worker.shutdown()
    }
}
