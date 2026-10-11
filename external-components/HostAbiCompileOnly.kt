package com.riftos.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject

/**
 * COMPILE-ONLY MIRROR of the installed RiftOS host ABI v1.
 * NEVER include any classes from this source in Core/Shell output DEX.
 * D8 producer removes the four compile-only host interface .class files first.
 *
 * Runtime interfaces come from the installed signed RiftOS APK parent loader.
 */
interface RiftCoreComponentV1 {
    fun initialize(context: Context)
    fun snapshot(context: Context, id: String): JSONObject
    fun installed(context: Context): JSONArray
    fun open(context: Context, id: String): JSONObject
    fun reattach(context: Context, id: String, generation: Long): JSONObject
    fun stop(context: Context, id: String, generation: Long): JSONObject
    fun focus(context: Context, id: String?): JSONObject
    fun event(context: Context, id: String, generation: Long, payload: JSONObject): JSONObject
    fun install(context: Context, artifact: String): JSONObject
    fun uninstall(context: Context, id: String): JSONObject
}

interface RiftCoreExecutionViewV1 {
    fun runningApps(context: Context): JSONArray
    fun sessionsView(context: Context): JSONObject
    fun surfacesView(context: Context): JSONObject
    fun focusView(context: Context): JSONObject
    fun coreStatus(context: Context): JSONObject
    fun discoverRuntimeCandidates(context: Context): JSONObject
    fun startApp(context: Context, id: String): JSONObject
    fun stopApp(context: Context, id: String): JSONObject
}

interface RiftShellGraphicalComponentV1 {
    fun attach(activity: Activity, container: FrameLayout,
               services: RiftShellPlatformServicesV1, restore: JSONObject?)
    fun onResume()
    fun onPause()
    fun onWindowFocusChanged(focused: Boolean)
    fun onBackPressed(): Boolean
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean
    fun onDestroy()
}

interface RiftShellPlatformServicesV1 {
    fun installed(): JSONArray
    fun open(id: String): JSONObject
    fun reattach(id: String, generation: Long): JSONObject
    fun stop(id: String, generation: Long): JSONObject
    fun focus(id: String?): JSONObject
    fun snapshot(id: String): JSONObject
    fun event(id: String, generation: Long, payload: JSONObject): JSONObject
    fun installRapp(path: String): JSONObject
    fun uninstallRapp(id: String): JSONObject
    fun pollUi(): JSONObject
    fun respondConsent(ticket: Long, approved: Boolean): Boolean
    fun execute(command: String, cwd: String?): JSONObject
    fun reportDesktop(snapshot: JSONObject): JSONObject
    fun claimRecovery(): JSONObject
}
