package com.riftos.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import java.io.ByteArrayOutputStream
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.EditText
import org.json.JSONObject

/**
 * APK-owned manual Recovery entrance, separate :riftRecovery process.
 * The only restore actions originate from explicit on-screen confirmation;
 * neither Core nor Shell is loaded or restarted by this Activity.
 */
class RiftManualRecoveryActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var coreBackup: Button
    private lateinit var shellBackup: Button
    private lateinit var saveCoreBackup: Button
    private lateinit var saveShellBackup: Button
    private lateinit var coreEmbedded: Button
    private lateinit var shellEmbedded: Button
    private var lastResult: String = ""
    private var selectedManifest: ByteArray? = null
    private var stagedComponent: String? = null
    private var stagedSha: String? = null
    private lateinit var dexPicker: Button
    private lateinit var installUpdate: Button

    private companion object {
        const val MANIFEST_PICKER = 9101
        const val DEX_PICKER = 9102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "RiftOS Recovery"
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(34))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(screen)
        })
        screen.addView(TextView(this).apply {
            text = "RiftOS Recovery"
            textSize = 26f
        })
        screen.addView(TextView(this).apply {
            text = "This recovery screen runs in the Android APK independently of " +
                "RiftOS Core and RiftShell. It restores only your manually saved " +
                "backup or the built-in migration fallback. Changes apply on " +
                "the NEXT Core/Shell start, not to processes already running."
            textSize = 15f
            setPadding(0, dp(12), 0, dp(20))
        })
        statusText = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
        }
        screen.addView(statusText, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        saveCoreBackup = saveAction(screen, "Save selected Core as recovery backup", "core")
        saveShellBackup = saveAction(screen, "Save selected RiftShell as recovery backup", "shell")
        coreBackup = action(screen, "Restore saved Core backup", "core", "working-backup")
        shellBackup = action(screen, "Restore saved RiftShell backup", "shell", "working-backup")
        coreEmbedded = action(screen, "Restore embedded Core", "core", "embedded")
        shellEmbedded = action(screen, "Restore embedded RiftShell", "shell", "embedded")
        screen.addView(TextView(this).apply {
            text = "Install a verified external Core / RiftShell update"
            textSize = 19f
            setPadding(0, dp(22), 0, dp(8))
        })
        screen.addView(TextView(this).apply {
            text = "Only select a trusted, independently built component.json and " +
                "matching .dex. The APK verifies both before staging. " +
                "Installing changes the next-start selection, NOT the running OS."
            textSize = 14f
        })
        screen.addView(TextView(this).apply {
            text = "RiftOS private Builds files are not visible in Android's file picker. " +
                "For a locally compiled pair, enter its RiftOS folder instead. " +
                "Staging checks the same protected manifest, DEX and ABI rules; " +
                "it does NOT install or start the component."
            textSize = 14f
            setPadding(0, dp(12), 0, dp(6))
        })
        val localComponentPath = EditText(this).apply {
            setSingleLine(true)
            setText("/D:/Builds/Components/core/trial")
            hint = "/D:/Builds/Components/core/trial"
            textSize = 14f
        }
        screen.addView(localComponentPath)
        screen.addView(Button(this).apply {
            text = "Stage verified LOCAL component pair"
            setOnClickListener {
                selectedManifest = null
                stagedComponent = null
                stagedSha = null
                dexPicker.isEnabled = false
                installUpdate.isEnabled = false
                lastResult = runCatching {
                    val staged = RiftManualRecoveryInstaller.stageLocal(
                        this@RiftManualRecoveryActivity,
                        localComponentPath.text.toString()
                    )
                    stagedComponent = staged.getString("component")
                    stagedSha = staged.getString("sha256")
                    installUpdate.isEnabled = true
                    "Verified local " + stagedComponent + " SHA " + stagedSha +
                        " is staged only. Confirm Install separately to change " +
                        "the NEXT startup selection."
                }.getOrElse {
                    "Local stage refused: " + it.javaClass.simpleName +
                        ". " + (it.message ?: "No component was selected").take(160)
                }
                showStatus()
            }
        })
        screen.addView(Button(this).apply {
            text = "1. Choose component.json"
            setOnClickListener { chooseDocument(MANIFEST_PICKER) }
        })
        dexPicker = Button(this).apply {
            text = "2. Choose matching component DEX"
            isEnabled = false
            setOnClickListener { chooseDocument(DEX_PICKER) }
        }
        screen.addView(dexPicker)
        installUpdate = Button(this).apply {
            text = "3. Install verified staged update"
            isEnabled = false
            setOnClickListener {
                val component = stagedComponent ?: return@setOnClickListener
                val sha = stagedSha ?: return@setOnClickListener
                AlertDialog.Builder(this@RiftManualRecoveryActivity)
                    .setTitle("Install $component update?")
                    .setMessage("Select verified component $sha for its NEXT startup? " +
                        "Existing sessions are not hot-swapped. " +
                        "No boot-health approval is required. " +
                        "Only a backup that you explicitly save is kept, " +
                        "alongside the APK's embedded fallback.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Install") { _, _ ->
                        val outcome = runCatching {
                            RiftManualRecoveryInstaller.install(this@RiftManualRecoveryActivity, component, sha)
                        }
                        lastResult = outcome.fold(
                            onSuccess = {
                                stagedSha = null
                                stagedComponent = null
                                installUpdate.isEnabled = false
                                "Update selected for next start. Force-stop RiftOS from " +
                                    "Android Settings, then reopen RiftOS. If it fails, " +
                                    "open Recovery and restore the prior working version."
                            },
                            onFailure = {
                                "Install refused: ${it.javaClass.simpleName}. " +
                                    "No successful update was recorded."
                            }
                        )
                        showStatus()
                    }
                    .show()
            }
        }
        screen.addView(installUpdate)
        screen.addView(Button(this).apply {
            text = "Refresh recovery status"
            setOnClickListener { showStatus() }
        })
        screen.addView(Button(this).apply {
            text = "Close Recovery"
            setOnClickListener { finish() }
        })
        showStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) showStatus()
    }

    private fun chooseDocument(requestCode: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, requestCode)
    }

    private fun readManifest(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri)
            ?: error("Selected component manifest cannot be opened")
        return input.use {
            val data = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = it.read(buffer)
                if (count == -1) break
                if (count == 0) continue
                require(data.size() + count <= 32 * 1024) {
                    "Component manifest exceeds the safe limit"
                }
                data.write(buffer, 0, count)
            }
            data.toByteArray()
        }
    }

    @Deprecated("Android file picker result contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == MANIFEST_PICKER) {
            selectedManifest = null
            stagedComponent = null
            stagedSha = null
            installUpdate.isEnabled = false
            dexPicker.isEnabled = false
            lastResult = runCatching {
                val bytes = readManifest(uri)
                val component = RiftProtectedComponentManifest.parse(bytes)
                selectedManifest = bytes
                dexPicker.isEnabled = true
                "Manifest selected: ${component.component} ${component.version}. " +
                    "Choose its matching DEX file to verify and stage."
            }.getOrElse {
                "Manifest rejected: ${it.javaClass.simpleName}"
            }
        } else if (requestCode == DEX_PICKER) {
            val manifest = selectedManifest
            lastResult = if (manifest == null) {
                "Choose the component manifest first."
            } else runCatching {
                val input = contentResolver.openInputStream(uri)
                    ?: error("Selected component DEX cannot be opened")
                val staged = input.use {
                    RiftManualRecoveryInstaller.stage(this, manifest, it)
                }
                stagedComponent = staged.getString("component")
                stagedSha = staged.getString("sha256")
                selectedManifest = null
                dexPicker.isEnabled = false
                installUpdate.isEnabled = true
                "Verified and staged: ${stagedComponent} SHA ${stagedSha}. " +
                    "The active Core/Shell is unchanged until you confirm Install."
            }.getOrElse {
                stagedComponent = null
                stagedSha = null
                installUpdate.isEnabled = false
                "Stage rejected: ${it.javaClass.simpleName}"
            }
        }
        showStatus()
    }

    private fun saveAction(parent: LinearLayout, title: String, component: String): Button =
        Button(this).also { button ->
            button.text = title
            button.isEnabled = false
            button.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage("Save the currently selected verified $component revision as the ONLY recovery backup? This replaces your previously saved $component backup. It does not reboot, activate, or judge boot health.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Save backup") { _, _ ->
                        val saved = runCatching {
                            RiftManualRecoveryStore.saveSelectedBackup(this, component)
                        }
                        lastResult = saved.fold(
                            onSuccess = { "BACKUP SAVED: $component SHA " +
                                it.optString("sha256") + ". Restore remains a separate manual choice." },
                            onFailure = { "Backup NOT saved: " + it.javaClass.simpleName +
                                ". " + (it.message ?: "").take(130) }
                        )
                        showStatus()
                    }.show()
            }
            parent.addView(button)
        }

    private fun action(parent: LinearLayout, title: String, component: String,
                       selection: String): Button = Button(this).also { button ->
        button.text = title
        button.isEnabled = false
        button.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(
                    "Restore $component to " +
                        (if (selection == "embedded") "the APK's embedded fallback"
                         else "your manually saved and SHA-verified backup") +
                        "? This updates the next-start selector only. " +
                        "Running Core/Shell processes will NOT be hot-swapped."
                )
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore") { _, _ ->
                    val result = runCatching {
                        RiftManualRecoveryStore.restore(this, component, selection)
                    }
                    lastResult = result.fold(
                        onSuccess = { restored ->
                            "RESTORE SELECTED: ${restored.optString("selected")}. " +
                                "Return to Android Settings, force-stop RiftOS, " +
                                "then open RiftOS again to load the selected version."
                        },
                        onFailure = { error ->
                            "Restore could not be completed: ${error.javaClass.simpleName}. " +
                                "Check the selected Core/Shell above before restarting."
                        }
                    )
                    showStatus()
                }
                .show()
        }
        parent.addView(button)
    }

    private fun showStatus() {
        val result = runCatching { RiftManualRecoveryStore.status(this) }
        statusText.text = result.fold(
            onSuccess = { view ->
                val core = view.getJSONObject("core")
                val shell = view.getJSONObject("shell")
                saveCoreBackup.isEnabled = core.optBoolean("saveBackupEnabled")
                saveShellBackup.isEnabled = shell.optBoolean("saveBackupEnabled")
                coreBackup.isEnabled = core.optBoolean("restoreWorkingBackupEnabled")
                shellBackup.isEnabled = shell.optBoolean("restoreWorkingBackupEnabled")
                coreEmbedded.isEnabled = core.optBoolean("restoreEmbeddedEnabled")
                shellEmbedded.isEnabled = shell.optBoolean("restoreEmbeddedEnabled")
                buildString {
                    if (lastResult.isNotEmpty()) append(lastResult).append("\n\n")
                    append("Recovery status: manual restore Gate B\n\n")
                    appendSlot(this, "Core", core)
                    append("\n")
                    appendSlot(this, "RiftShell", shell)
                    append("\nFirst install: embedded migration Core/Shell are still available.")
                    append("\nRestoring never removes RAPP data.")
                }
            },
            onFailure = { error ->
                saveCoreBackup.isEnabled = false
                saveShellBackup.isEnabled = false
                coreBackup.isEnabled = false
                shellBackup.isEnabled = false
                coreEmbedded.isEnabled = false
                shellEmbedded.isEnabled = false
                "Could not inspect recovery records: " + error.javaClass.simpleName +
                    "\nNo component was changed. Embedded fallback remains."
            }
        )
    }

    private fun appendSlot(out: StringBuilder, name: String, slot: JSONObject) {
        out.append(name).append("\n")
        out.append("Selected: ").append(slot.optString("active", "unknown")).append("\n")
        if (slot.has("activeSha256")) {
            out.append("Selected SHA: ").append(slot.optString("activeSha256")).append("\n")
        }
        out.append("Stored working backup: ").append(slot.optString("backup", "unknown")).append("\n")
        if (slot.has("backupSha256")) {
            out.append("Backup SHA: ").append(slot.optString("backupSha256")).append("\n")
        }
        if (slot.has("backupError") || slot.has("activeError")) {
            out.append("Record warning: invalid data; not safe to use as backup\n")
        }
        out.append("Startup marker: ")
            .append(if (slot.optBoolean("startupMarkerPresent", false)) "present" else "absent")
            .append("\n")
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
