package com.riftos.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLConnection
import java.util.concurrent.Executors

/**
 * Android-owned execution surface for installed RiftOS programs.
 *
 * Installed HTML/JS programs run in a dedicated WebView View attached directly to the
 * Android-native RiftDesktop window. They never run in an iframe or inside the trusted
 * shell WebView. The bridge is fixed and capability-gated; there is no arbitrary native call.
 */
class RiftNativeAppHost(
    private val activity: Activity,
    private val desktop: RiftNativeDesktop
) {
    companion object {
        private const val APP_ORIGIN = "https://app.riftos.local"
        private const val BRIDGE_NAME = "RiftNativeApp"
        private const val MAX_TEXT_BYTES = 8 * 1024 * 1024
        private const val MAX_MESSAGE_BYTES = 1024 * 1024
        private val APP_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$")
        private val ALLOWED_CAPABILITIES = setOf(
            "fs.read", "fs.write", "network", "clipboard.read", "clipboard.write",
            "share", "notifications", "build.local", "native.files", "native.background"
        )
    }

    private data class PackageInfo(
        val id: String,
        val manifest: JSONObject,
        val files: JSONObject,
        val entry: String,
        val permissions: Set<String>
    )

    private data class Instance(
        val windowId: String,
        val app: PackageInfo,
        val webView: WebView
    )

    private val instances = LinkedHashMap<String, Instance>()
    private val prefs = activity.getSharedPreferences("rift-native", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val riftRoot = File(activity.filesDir, "riftfs").apply { mkdirs() }.canonicalFile

    fun open(args: JSONObject): JSONObject {
        val appId = args.optString("appId").trim()
        val windowId = args.optString("windowId").trim()
        require(APP_ID.matches(appId)) { "Invalid Rift app id" }
        require(windowId.isNotBlank()) { "Rift app windowId is required" }
        instances[windowId]?.let { return instanceState(it) }

        val app = loadPackage(appId)
        val networkDeclared = app.permissions.contains("network")
        val networkEnabled = networkDeclared && hasGrant(app.id, "network")
        val webView = WebView(activity).apply {
            setBackgroundColor(Color.rgb(11, 17, 24))
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = true
            overScrollMode = WebView.OVER_SCROLL_NEVER
            contentDescription = "${app.manifest.optString("name", app.id)} application surface"
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = false
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                blockNetworkLoads = !networkEnabled
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
        }

        val instance = Instance(windowId, app, webView)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.scheme.equals("https", true) && uri.host.equals("app.riftos.local", true)) {
                    return localAssetResponse(app, uri)
                }
                return if (webView.settings.blockNetworkLoads) blockedResponse() else null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return !(uri.scheme.equals("https", true) && uri.host.equals("app.riftos.local", true))
            }
        }

        require(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "Android System WebView is too old for Rift native app messaging"
        }
        WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, setOf(APP_ORIGIN)) { _, message, sourceOrigin, isMainFrame, _ ->
            if (!isMainFrame || !sourceOrigin.scheme.equals("https", true) || !sourceOrigin.host.equals("app.riftos.local", true)) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            if (raw.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) {
                deliver(instance, JSONObject().put("id", JSONObject.NULL).put("ok", false).put("error", "Rift app message too large"))
                return@addWebMessageListener
            }
            val request = runCatching { JSONObject(raw) }.getOrElse {
                deliver(instance, JSONObject().put("id", JSONObject.NULL).put("ok", false).put("error", "Invalid Rift app message"))
                return@addWebMessageListener
            }
            handle(instance, request)
        }

        instances[windowId] = instance
        desktop.attachContent(windowId, webView)
        val html = prepareHtml(app, networkDeclared)
        val baseUrl = "$APP_ORIGIN/${Uri.encode(app.id)}/"
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
        return instanceState(instance)
    }

    fun close(args: JSONObject): JSONObject = closeWindow(args.optString("windowId"))

    fun closeWindow(windowId: String): JSONObject {
        val instance = instances.remove(windowId) ?: return JSONObject().put("closed", false).put("windowId", windowId)
        desktop.detachContent(windowId, instance.webView)
        runCatching { WebViewCompat.removeWebMessageListener(instance.webView, BRIDGE_NAME) }
        instance.webView.stopLoading()
        instance.webView.loadUrl("about:blank")
        instance.webView.removeAllViews()
        instance.webView.destroy()
        return JSONObject().put("closed", true).put("windowId", windowId).put("appId", instance.app.id)
    }

    fun state(): JSONObject = JSONObject().put("native", true).put("engine", "native-webview").put("instances", JSONArray().apply {
        for (instance in instances.values) put(instanceState(instance))
    })

    fun onResume() { instances.values.forEach { it.webView.onResume() } }
    fun onPause() { instances.values.forEach { it.webView.onPause() } }
    fun destroy() { instances.keys.toList().forEach(::closeWindow); executor.shutdownNow() }

    private fun handle(instance: Instance, request: JSONObject) {
        val id = request.opt("id") ?: JSONObject.NULL
        val method = request.optString("method")
        val args = request.optJSONObject("args") ?: JSONObject()
        if (method == "app.ready") return
        when (method) {
            "app.close" -> {
                desktop.handle("desktop.window.close", JSONObject().put("id", instance.windowId))
                reply(instance, id, true, true, null)
            }
            "app.setTitle" -> {
                val title = args.optString("title", instance.app.manifest.optString("name", instance.app.id)).take(96)
                desktop.handle("desktop.window.title", JSONObject().put("id", instance.windowId).put("title", title))
                reply(instance, id, true, true, null)
            }
            "app.info" -> reply(instance, id, true, instance.app.manifest, null)
            "storage.get" -> background(instance, id) { storage(instance.app.id).opt(args.optString("key").take(160)) ?: JSONObject.NULL }
            "storage.set" -> background(instance, id) {
                val key = args.optString("key").take(160); require(key.isNotBlank()) { "Storage key is required" }
                val data = storage(instance.app.id); data.put(key, args.opt("value") ?: JSONObject.NULL); writeStorage(instance.app.id, data); true
            }
            "storage.remove" -> background(instance, id) { val data = storage(instance.app.id); data.remove(args.optString("key").take(160)); writeStorage(instance.app.id, data); true }
            "permissions.request" -> withCapability(instance, id, args.optString("capability")) { true }
            "fs.readText" -> withCapability(instance, id, "fs.read") { readTextForApp(instance.app.id, args.optString("path")) }
            "fs.list" -> withCapability(instance, id, "fs.read") { listPathForApp(instance.app.id, args.optString("path", "/D:/Workspace")) }
            "fs.writeText" -> withCapability(instance, id, "fs.write") { writeTextForApp(instance.app.id, args.optString("path"), args.optString("text")) }
            "clipboard.read" -> withCapability(instance, id, "clipboard.read", ui = true) { clipboardRead() }
            "clipboard.write" -> withCapability(instance, id, "clipboard.write", ui = true) { clipboardWrite(args.optString("text")) }
            "share" -> withCapability(instance, id, "share", ui = true) { share(args.optString("text"), instance.app.manifest.optString("name", "RiftOS")); true }
            "build.doctor" -> withCapability(instance, id, "build.local") { JSONObject().put("available", true).put("ready", false).put("nativeExecutor", false).put("blockers", JSONArray().put("Local compiler executor is not installed in this APK yet.")) }
            "build.plan" -> withCapability(instance, id, "build.local") { JSONObject().put("format", "riftbuild-plan-v1").put("project", args.optString("project")).put("target", args.optString("target", "universal")).put("nativeExecutor", false) }
            "build.submit" -> withCapability(instance, id, "build.local") { throw UnsupportedOperationException("Local RiftBuild executor is not installed in this APK") }
            "build.runs" -> withCapability(instance, id, "build.local") { JSONArray() }
            "build.artifacts" -> withCapability(instance, id, "build.local") { listPath("/D:/Builds") }
            else -> reply(instance, id, false, null, "Unsupported Rift app method: $method")
        }
    }

    private fun withCapability(instance: Instance, id: Any, capability: String, ui: Boolean = false, operation: () -> Any?) {
        if (!ALLOWED_CAPABILITIES.contains(capability)) { reply(instance, id, false, null, "Unknown capability: $capability"); return }
        if (!instance.app.permissions.contains(capability)) { reply(instance, id, false, null, "$capability is not declared by this app"); return }
        val run = {
            if (ui) {
                try { reply(instance, id, true, operation(), null) } catch (error: Throwable) { reply(instance, id, false, null, error.message ?: error.javaClass.simpleName) }
            } else background(instance, id, operation)
        }
        if (hasGrant(instance.app.id, capability)) { run(); return }
        AlertDialog.Builder(activity)
            .setTitle("RiftOS permission")
            .setMessage("${instance.app.manifest.optString("name", instance.app.id)} wants permission: $capability")
            .setPositiveButton("Allow") { _, _ -> grant(instance.app.id, capability); if (capability == "network") instance.webView.settings.blockNetworkLoads = false; run() }
            .setNegativeButton("Deny") { _, _ -> reply(instance, id, false, null, "$capability permission denied") }
            .setOnCancelListener { reply(instance, id, false, null, "$capability permission denied") }
            .show()
    }

    private fun background(instance: Instance, id: Any, operation: () -> Any?) {
        executor.execute {
            try { val value = operation(); activity.runOnUiThread { reply(instance, id, true, value, null) } }
            catch (error: Throwable) { activity.runOnUiThread { reply(instance, id, false, null, error.message ?: error.javaClass.simpleName) } }
        }
    }

    private fun reply(instance: Instance, id: Any, ok: Boolean, value: Any?, error: String?) {
        deliver(instance, JSONObject().put("id", id).put("ok", ok).put("value", value ?: JSONObject.NULL).put("error", error ?: JSONObject.NULL))
    }

    private fun deliver(instance: Instance, response: JSONObject) {
        val payload = JSONObject.quote(response.toString())
        instance.webView.post {
            if (!activity.isFinishing && instances[instance.windowId] === instance) instance.webView.evaluateJavascript("window.__RiftNativeReceive?.($payload);", null)
        }
    }

    private fun loadPackage(appId: String): PackageInfo {
        val current = safeFile("/C:/Programs/$appId/package.json")
        val legacy = safeFile("/apps/packages/$appId/package.json")
        val file = when { current.isFile -> current; legacy.isFile -> legacy; else -> throw IllegalArgumentException("Installed Rift app not found: $appId") }
        require(file.length() in 1..MAX_TEXT_BYTES.toLong()) { "Installed package is empty or too large" }
        val pkg = JSONObject(file.readText())
        val manifest = pkg.optJSONObject("manifest") ?: throw IllegalArgumentException("Installed package manifest missing")
        require(manifest.optString("id") == appId) { "Installed package id mismatch" }
        val files = pkg.optJSONObject("files") ?: throw IllegalArgumentException("Installed package files missing")
        val entry = safeAssetPath(manifest.optString("entry", "index.html"))
        require(files.has(entry) && files.opt(entry) is String) { "Installed package entry missing: $entry" }
        val permissions = linkedSetOf<String>()
        val declared = manifest.optJSONArray("permissions") ?: JSONArray()
        for (index in 0 until declared.length()) declared.optString(index).takeIf { it.isNotBlank() }?.let(permissions::add)
        return PackageInfo(appId, manifest, files, entry, permissions)
    }

    private fun prepareHtml(app: PackageInfo, networkEnabled: Boolean): String {
        var html = app.files.getString(app.entry)
        val network = if (networkEnabled) " https: http:" else ""
        val policy = "default-src 'none'; script-src 'unsafe-inline' $APP_ORIGIN blob:$network; style-src 'unsafe-inline' $APP_ORIGIN blob:$network; img-src $APP_ORIGIN data: blob:$network; font-src $APP_ORIGIN data: blob:$network; connect-src ${if (networkEnabled) "https: http:" else "'none'"}; media-src $APP_ORIGIN data: blob:$network; frame-src 'none'; object-src 'none'; base-uri 'self'"
        val meta = "<meta http-equiv=\"Content-Security-Policy\" content=\"${policy.replace("\"", "&quot;")}\">"
        val manifestJson = JSONObject.quote(app.manifest.toString())
        val bridge = """<script>(()=>{const MANIFEST=JSON.parse($manifestJson);let seq=0;const pending=new Map();window.__RiftNativeReceive=raw=>{let msg;try{msg=JSON.parse(raw)}catch{return}const p=pending.get(msg.id);if(!p)return;pending.delete(msg.id);msg.ok?p.resolve(msg.value):p.reject(new Error(msg.error||'RiftOS app host error'))};const call=(method,args={})=>new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});RiftNativeApp.postMessage(JSON.stringify({id,method,args}))});Object.defineProperty(window,'Rift',{value:Object.freeze({version:'2.0-native',app:Object.freeze({info:()=>MANIFEST,close:()=>call('app.close'),setTitle:title=>call('app.setTitle',{title})}),storage:Object.freeze({get:key=>call('storage.get',{key}),set:(key,value)=>call('storage.set',{key,value}),remove:key=>call('storage.remove',{key})}),permissions:Object.freeze({request:capability=>call('permissions.request',{capability})}),fs:Object.freeze({readText:path=>call('fs.readText',{path}),writeText:(path,text)=>call('fs.writeText',{path,text}),list:path=>call('fs.list',{path})}),clipboard:Object.freeze({readText:()=>call('clipboard.read'),writeText:text=>call('clipboard.write',{text})}),share:Object.freeze({text:text=>call('share',{text})}),build:Object.freeze({nativeExecutor:false,doctor:project=>call('build.doctor',{project}),plan:(project,target='universal')=>call('build.plan',{project,target}),submit:job=>call('build.submit',{job}),runs:(limit=20)=>call('build.runs',{limit}),artifacts:project=>call('build.artifacts',{project})})}),writable:false});RiftNativeApp.postMessage(JSON.stringify({method:'app.ready',args:{}}))})();</script>"""
        val injection = meta + bridge
        html = if (Regex("<head[^>]*>", RegexOption.IGNORE_CASE).containsMatchIn(html)) html.replaceFirst(Regex("<head([^>]*)>", RegexOption.IGNORE_CASE), "<head$1>$injection") else injection + html
        return html
    }

    private fun localAssetResponse(app: PackageInfo, uri: Uri): WebResourceResponse {
        val path = uri.path.orEmpty().trimStart('/')
        val slash = path.indexOf('/')
        val encodedApp = if (slash >= 0) path.substring(0, slash) else path
        if (Uri.decode(encodedApp) != app.id) return notFoundResponse()
        val asset = safeAssetPath(if (slash >= 0) Uri.decode(path.substring(slash + 1)) else app.entry)
        if (asset.isBlank() || !app.files.has(asset) || app.files.opt(asset) !is String) return notFoundResponse()
        val text = app.files.getString(asset)
        val mime = URLConnection.guessContentTypeFromName(asset) ?: when (asset.substringAfterLast('.', "").lowercase()) {
            "js", "mjs", "cjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "xml" -> "application/xml"
            else -> "text/plain"
        }
        return WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    }

    private fun notFoundResponse() = WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun blockedResponse() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun safeAssetPath(raw: String): String {
        val parts = raw.replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() && it != "." }
        require(parts.none { it == ".." || it.contains('\u0000') }) { "Invalid app asset path" }
        return parts.joinToString("/")
    }

    private fun safeFile(raw: String): File {
        val relative = RiftVolumePaths.resolveRelative(raw)
        val target = File(riftRoot, relative).canonicalFile
        require(target == riftRoot || target.path.startsWith(riftRoot.path + File.separator)) { "Path escaped RiftFS" }
        return target
    }

    private fun enforceProgramPath(appId: String, raw: String, write: Boolean): String {
        val path = RiftVolumePaths.normalizeDisplay(raw)
        val ownProgram = "/C:/Programs/$appId"
        val ownData = "/D:/Users/Default/AppData/$appId"
        val publicDataRoots = listOf("/D:/Workspace", "/D:/Projects", "/D:/Packages", "/D:/Builds", "/D:/Documents", "/D:/Downloads", "/D:/Temp")
        val allowed = path == ownData || path.startsWith("$ownData/") || publicDataRoots.any { path == it || path.startsWith("$it/") } || (!write && (path == ownProgram || path.startsWith("$ownProgram/")))
        require(allowed) { if (write) "Program writes are restricted to D: user/project data and this app's AppData" else "Program reads are restricted to approved D: data and this app's installed files" }
        return path
    }

    private fun readTextForApp(appId: String, path: String): String {
        val display = enforceProgramPath(appId, path, false)
        val file = safeFile(display); require(file.isFile) { "File not found: $display" }; require(file.length() <= MAX_TEXT_BYTES) { "File exceeds native app text limit" }; return file.readText()
    }

    private fun writeTextForApp(appId: String, path: String, text: String): JSONObject {
        val display = enforceProgramPath(appId, path, true)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES) { "Text exceeds native app write limit" }
        val file = safeFile(display); file.parentFile?.mkdirs(); file.writeText(text)
        return statJson(file, display)
    }

    private fun listPathForApp(appId: String, path: String): JSONArray = listPath(enforceProgramPath(appId, path, false))

    private fun listPath(path: String): JSONArray {
        val display = RiftVolumePaths.normalizeDisplay(path)
        if (RiftVolumePaths.isVolumeRoot(display)) {
            val volume = RiftVolumePaths.volume(display) ?: return JSONArray()
            val out = JSONArray(); val seen = linkedSetOf<String>()
            for (name in volume.roots.keys) { val child = "$display/$name"; seen += child; out.put(JSONObject().put("path", child).put("name", name).put("kind", "directory").put("size", 0).put("modified", 0).put("backend", "rift-volume")) }
            val backing = safeFile(display)
            backing.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { child -> val childPath = "$display/${child.name}"; if (seen.add(childPath)) out.put(statJson(child, childPath)) }
            return out
        }
        val dir = safeFile(display); require(dir.isDirectory) { "Directory not found: $path" }
        val out = JSONArray(); dir.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })?.forEach { child -> out.put(statJson(child, "$display/${child.name}".replace("//", "/"))) }; return out
    }

    private fun statJson(file: File, displayPath: String): JSONObject = JSONObject()
        .put("path", displayPath)
        .put("name", file.name)
        .put("kind", if (file.isDirectory) "directory" else "file")
        .put("size", if (file.isFile) file.length() else 0L)
        .put("modified", file.lastModified())
        .put("backend", "android-internal")

    private fun storage(appId: String): JSONObject {
        val file = safeFile("/D:/Users/Default/AppData/$appId/storage.json")
        if (!file.isFile) return JSONObject()
        require(file.length() <= 1024 * 1024) { "App storage exceeds 1 MB" }
        return runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
    }

    private fun writeStorage(appId: String, value: JSONObject) {
        val encoded = value.toString(); require(encoded.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "App storage exceeds 1 MB" }
        val file = safeFile("/D:/Users/Default/AppData/$appId/storage.json"); file.parentFile?.mkdirs(); file.writeText(encoded)
    }

    private fun hasGrant(appId: String, capability: String): Boolean = grants(appId).contains(capability)

    private fun grants(appId: String): MutableSet<String> {
        val raw = prefs.getString("setting:permissions:$appId", null) ?: return linkedSetOf()
        val array = runCatching { JSONObject(raw).optJSONArray("value") }.getOrNull() ?: return linkedSetOf()
        val out = linkedSetOf<String>(); for (index in 0 until array.length()) array.optString(index).takeIf { it.isNotBlank() }?.let(out::add); return out
    }

    private fun grant(appId: String, capability: String) {
        val set = grants(appId); set += capability
        prefs.edit().putString("setting:permissions:$appId", JSONObject().put("value", JSONArray(set.sorted())).put("modified", System.currentTimeMillis()).toString()).apply()
    }

    private fun clipboardRead(): String = (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString().orEmpty()
    private fun clipboardWrite(text: String): Boolean { (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("RiftOS program", text)); return true }
    private fun share(text: String, title: String) { activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, title)) }

    private fun instanceState(instance: Instance): JSONObject = JSONObject()
        .put("windowId", instance.windowId)
        .put("appId", instance.app.id)
        .put("engine", "native-webview")
        .put("surface", "android-view")
        .put("iframe", false)
        .put("programPath", "/C:/Programs/${instance.app.id}")
        .put("dataPath", "/D:/Users/Default/AppData/${instance.app.id}")
}
