package com.riftos.app

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Environment
import android.os.Message
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.URLConnection

/** Android System WebView compatibility backend for RiftBrowserEngine. */
class AndroidWebViewBrowserEngine(
    private val activity: Activity,
    private val launchFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams?) -> Boolean,
    private val stateChanged: () -> Unit
) : RiftBrowserEngine {
    companion object {
        private val EXTERNAL_SCHEMES = setOf("mailto", "tel", "geo")
        private val AUTH_FLOW_HOSTS = setOf(
            "chatgpt.com",
            "www.chatgpt.com",
            "openai.com",
            "www.openai.com",
            "auth.openai.com",
            "accounts.google.com",
            "login.microsoftonline.com",
            "login.live.com",
            "appleid.apple.com"
        )
    }

    private val container = android.widget.FrameLayout(activity).apply {
        clipChildren = true
        clipToPadding = true
        setBackgroundColor(0xff0a0d12.toInt())
    }
    val webView = WebView(activity)
    override val view get() = container
    override val rendererId = "android-webview"

    private val mcpApp = RiftBrowserMcpAppBridge(activity, webView)
    private val defaultUserAgent = WebSettings.getDefaultUserAgent(activity)
    private var defaultUserAgentMetadata: UserAgentMetadata? = null
    private var popupWebView: WebView? = null
    private var popupHost: android.widget.FrameLayout? = null
    private var mainRendererGone = false
    private var crashed = false
    private var desktopMode = false
    private var inspectorActive = false

    init {
        CookieManager.getInstance().setAcceptCookie(true)
        container.addView(
            webView,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        configureMainWebView(webView)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            defaultUserAgentMetadata = runCatching { WebSettingsCompat.getUserAgentMetadata(webView.settings) }.getOrNull()
        }
        mcpApp.install()
        installChromeClient()
        installWebViewClient()
        installDownloads()
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
        webView.setBackgroundColor(0xff0a0d12.toInt())
    }

    override fun currentUrl(): String = if (mainRendererGone) "" else webView.url.orEmpty()

    override fun loadUrl(url: String) {
        check(!mainRendererGone) { "RiftBrowser renderer is recovering" }
        crashed = false
        inspectorActive = false
        updateCookiePolicy(url)
        webView.loadUrl(url)
    }

    override fun canGoBack(): Boolean = !mainRendererGone && webView.canGoBack()
    override fun goBack() { if (!mainRendererGone && webView.canGoBack()) webView.goBack() }
    override fun canGoForward(): Boolean = !mainRendererGone && webView.canGoForward()
    override fun goForward() { if (!mainRendererGone && webView.canGoForward()) webView.goForward() }
    override fun reload() { check(!mainRendererGone) { "RiftBrowser renderer is recovering" }; crashed = false; inspectorActive = false; webView.reload() }

    override fun setDesktopMode(enabled: Boolean) {
        check(!mainRendererGone) { "RiftBrowser renderer is recovering" }
        if (desktopMode == enabled) return
        val current = webView.url.orEmpty()
        runCatching { webView.stopLoading() }
        destroyPopup()
        inspectorActive = false
        desktopMode = enabled
        applyBrowserIdentity(webView.settings, enabled)
        crashed = false
        if (current.isNotBlank() && current != "about:blank") webView.reload() else stateChanged()
    }

    override fun inspect(request: JSONObject, callback: (JSONObject?, Throwable?) -> Unit) {
        if (mainRendererGone) { callback(null, IllegalStateException("RiftBrowser renderer is recovering")); return }
        val uri = runCatching { Uri.parse(webView.url.orEmpty()) }.getOrNull()
        if (uri?.scheme?.equals("https", ignoreCase = true) != true) {
            callback(null, IllegalArgumentException("RiftBrowser inspector only supports the active HTTPS page"))
            return
        }
        val action = request.optString("action").trim().lowercase()
        val script = try {
            buildInspectorScript(action, request)
        } catch (error: Throwable) {
            callback(null, error)
            return
        }
        webView.evaluateJavascript(script) { raw ->
            try {
                val decoded = JSONObject("{\"value\":$raw}").optString("value")
                val envelope = JSONObject(decoded)
                if (!envelope.optBoolean("ok", false)) {
                    throw IllegalStateException(envelope.optString("error", "RiftBrowser inspector failed"))
                }
                inspectorActive = envelope.optBoolean("active", inspectorActive)
                callback(envelope.optJSONObject("result") ?: JSONObject(), null)
                stateChanged()
            } catch (error: Throwable) {
                callback(null, error)
            }
        }
    }

    private fun buildInspectorScript(action: String, request: JSONObject): String {
        val allowedActions = setOf("status", "dom", "inspect", "focus", "hide", "show", "text", "attr", "style", "outline", "reset")
        require(action in allowedActions) { "Unsupported RiftBrowser inspector action: $action" }
        val selector = if (action in setOf("dom", "inspect", "focus", "hide", "show", "text", "attr", "style")) {
            validateInspectorSelector(request.optString("selector").ifBlank { if (action == "dom") "body *" else "" })
        } else ""
        val limit = request.optInt("limit", 60).coerceIn(1, 100)
        val text = request.optString("text")
        require(text.length <= 4096) { "Inspector text is limited to 4096 characters" }
        val attrName = request.optString("name").trim().lowercase()
        val attrValue = request.optString("value")
        if (action == "attr") {
            require(attrName in setOf("class", "title", "aria-label", "role", "tabindex")) { "Inspector attribute is not allowed" }
            require(attrValue.length <= 1024) { "Inspector attribute value is limited to 1024 characters" }
        }
        val styleProperty = request.optString("property").trim().lowercase()
        val styleValue = request.optString("value")
        if (action == "style") validateInspectorStyle(styleProperty, styleValue)

        val selectorJs = JSONObject.quote(selector)
        val textJs = JSONObject.quote(text)
        val attrNameJs = JSONObject.quote(attrName)
        val attrValueJs = JSONObject.quote(attrValue)
        val stylePropertyJs = JSONObject.quote(styleProperty)
        val styleValueJs = JSONObject.quote(styleValue)
        val outlineEnabled = request.optBoolean("enabled", false)

        val actionBody = when (action) {
            "status" -> "result={available:true,active:s.touched.length>0||!!s.outlineStyle,origin:location.origin,host:location.hostname,actions:['dom','inspect','focus','hide','show','text','attr','style','outline','reset']};"
            "dom" -> "const list=Array.from(document.querySelectorAll($selectorJs)).filter(visible).slice(0,$limit);result={selector:$selectorJs,count:list.length,elements:list.map(meta)};"
            "inspect" -> "const el=one($selectorJs);result={selector:$selectorJs,element:meta(el)};"
            "focus" -> "const el=one($selectorJs);if(sensitive(el))throw new Error('Sensitive form controls cannot be targeted');touchStyle(el);el.scrollIntoView({block:'center',inline:'nearest'});el.style.setProperty('outline','3px solid #4fb99a','important');el.style.setProperty('outline-offset','2px','important');result={selector:$selectorJs,element:meta(el),focused:true};"
            "hide" -> "const el=one($selectorJs);if(sensitive(el))throw new Error('Sensitive form controls cannot be targeted');touchStyle(el);el.style.setProperty('display','none','important');result={selector:$selectorJs,hidden:true};"
            "show" -> "const el=one($selectorJs);if(sensitive(el))throw new Error('Sensitive form controls cannot be targeted');touchStyle(el);el.style.setProperty('display','block','important');result={selector:$selectorJs,shown:true,element:meta(el)};"
            "text" -> "const el=one($selectorJs);if(sensitive(el)||/^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName))throw new Error('Form controls cannot be text-edited');touchText(el);el.textContent=$textJs;result={selector:$selectorJs,changed:true,element:meta(el)};"
            "attr" -> "const el=one($selectorJs);if(sensitive(el))throw new Error('Sensitive form controls cannot be targeted');touchAttr(el,$attrNameJs);el.setAttribute($attrNameJs,$attrValueJs);result={selector:$selectorJs,changed:true,element:meta(el)};"
            "style" -> "const el=one($selectorJs);if(sensitive(el))throw new Error('Sensitive form controls cannot be targeted');touchStyle(el);el.style.setProperty($stylePropertyJs,$styleValueJs,'important');result={selector:$selectorJs,property:$stylePropertyJs,value:$styleValueJs,changed:true,element:meta(el)};"
            "outline" -> if (outlineEnabled) {
                "if(s.outlineStyle)s.outlineStyle.remove();const style=document.createElement('style');style.id='rift-browser-inspector-outline';style.textContent='body *{outline:1px solid rgba(79,185,154,.35)!important}';(document.head||document.documentElement).appendChild(style);s.outlineStyle=style;result={outline:true};"
            } else {
                "if(s.outlineStyle)s.outlineStyle.remove();s.outlineStyle=null;result={outline:false};"
            }
            "reset" -> "for(const r of s.touched){if(!r.el)continue;if(r.styleStored){if(r.style===null)r.el.removeAttribute('style');else r.el.setAttribute('style',r.style)}if(r.textStored)r.el.textContent=r.text;for(const name of Object.keys(r.attrs)){const value=r.attrs[name];if(value===null)r.el.removeAttribute(name);else r.el.setAttribute(name,value)}}if(s.outlineStyle)s.outlineStyle.remove();s.touched.length=0;s.outlineStyle=null;result={reset:true};"
            else -> error("unreachable")
        }

        return """
            (function(){
              try{
                const KEY='__riftBrowserInspectorV1';
                let s=window[KEY];
                if(!s||s.version!==1){s={version:1,touched:[],outlineStyle:null};Object.defineProperty(window,KEY,{value:s,configurable:true});}
                const token=v=>{v=String(v||'');return /^[A-Za-z_][A-Za-z0-9_-]{0,63}$/.test(v)?v:''};
                const structuralPath=el=>{if(!el||el.nodeType!==1)return '';const safeId=token(el.id);if(safeId)return '#'+safeId;const parts=[];let cur=el;for(let depth=0;cur&&cur!==document.documentElement&&depth<5;depth++,cur=cur.parentElement){let part=cur.tagName.toLowerCase();const c=Array.from(cur.classList||[]).map(token).filter(Boolean)[0];if(c)part+='.'+c;const p=cur.parentElement;if(p){const peers=Array.from(p.children).filter(x=>x.tagName===cur.tagName);if(peers.length>1)part+=':nth-of-type('+(peers.indexOf(cur)+1)+')'}parts.unshift(part)}return parts.join('>')};
                const visible=el=>{if(!el||el.nodeType!==1)return false;const r=el.getBoundingClientRect();const cs=getComputedStyle(el);return r.width>0&&r.height>0&&cs.display!=='none'&&cs.visibility!=='hidden'};
                const sensitive=el=>!!(el&&(el.matches('input[type=password]')||el.querySelector('input[type=password]')));
                const meta=el=>{const r=el.getBoundingClientRect(),cs=getComputedStyle(el);return {path:structuralPath(el),tag:el.tagName.toLowerCase(),id:token(el.id),classes:Array.from(el.classList||[]).map(token).filter(Boolean).slice(0,8),role:token(el.getAttribute('role')),type:token(el.getAttribute('type')),bounds:{x:Math.round(r.x),y:Math.round(r.y),width:Math.round(r.width),height:Math.round(r.height)},display:cs.display,visibility:cs.visibility,position:cs.position,overflow:cs.overflow,zIndex:String(cs.zIndex||'').slice(0,24)}};
                const record=el=>{let r=s.touched.find(x=>x.el===el);if(!r){r={el:el,styleStored:false,style:null,textStored:false,text:null,attrs:{}};s.touched.push(r)}return r};
                const touchStyle=el=>{const r=record(el);if(!r.styleStored){r.styleStored=true;r.style=el.getAttribute('style')}};
                const touchText=el=>{const r=record(el);if(!r.textStored){r.textStored=true;r.text=el.textContent}};
                const touchAttr=(el,name)=>{const r=record(el);if(!Object.prototype.hasOwnProperty.call(r.attrs,name))r.attrs[name]=el.getAttribute(name)};
                const one=selector=>{const el=document.querySelector(selector);if(!el)throw new Error('No element matches selector');return el};
                let result={};
                $actionBody
                return JSON.stringify({ok:true,active:s.touched.length>0||!!s.outlineStyle,result:result});
              }catch(error){return JSON.stringify({ok:false,error:String(error&&error.message||error)})}
            })();
        """.trimIndent()
    }

    private fun validateInspectorSelector(raw: String): String {
        val selector = raw.trim()
        require(selector.isNotBlank()) { "Inspector selector is required" }
        require(selector.length <= 512) { "Inspector selector is limited to 512 characters" }
        require(!selector.contains('[') && !selector.contains(']') && !selector.contains('=') && !selector.contains('"') && !selector.contains('\'')) { "Attribute/value selectors are not allowed" }
        require(!selector.contains(Regex(":has\\s*\\(", RegexOption.IGNORE_CASE))) { ":has() selectors are not allowed" }
        require(selector.matches(Regex("^[A-Za-z0-9_#.*:(),\\- >+~]+$"))) { "Inspector selector contains unsupported characters" }
        return selector
    }

    private fun validateInspectorStyle(property: String, value: String) {
        val allowed = setOf(
            "display", "visibility", "position", "top", "right", "bottom", "left",
            "width", "height", "min-width", "max-width", "min-height", "max-height",
            "overflow", "overflow-x", "overflow-y", "z-index", "opacity", "box-sizing",
            "flex", "flex-direction", "flex-wrap", "align-items", "justify-content", "gap",
            "grid-template-columns", "grid-template-rows", "white-space", "font-size", "line-height",
            "margin", "margin-top", "margin-right", "margin-bottom", "margin-left",
            "padding", "padding-top", "padding-right", "padding-bottom", "padding-left",
            "transform", "pointer-events"
        )
        require(property in allowed) { "Inspector style property is not allowed" }
        require(value.isNotBlank() && value.length <= 512) { "Inspector style value must be 1..512 characters" }
        require(value.matches(Regex("^[A-Za-z0-9#%.,()_\\-+ /]*$"))) { "Inspector style value contains unsupported characters" }
        val forbidden = Regex("(url|attr|expression|javascript|data|https?|@import|;|\\{|\\})", RegexOption.IGNORE_CASE)
        require(!forbidden.containsMatchIn(value)) { "Inspector style value contains a blocked construct" }
    }

    override fun state(): JSONObject = JSONObject()
        .put("renderer", rendererId)
        .put("url", if (mainRendererGone) "" else webView.url ?: "")
        .put("title", if (mainRendererGone) "RiftBrowser · renderer recovering" else webView.title ?: "RiftBrowser")
        .put("canGoBack", !mainRendererGone && webView.canGoBack())
        .put("canGoForward", !mainRendererGone && webView.canGoForward())
        .put("progress", if (mainRendererGone) 0 else webView.progress)
        .put("crashed", crashed)
        .put("desktopMode", desktopMode)
        .put("inspectorActive", inspectorActive)
        .put("popupOpen", popupWebView != null)
        .put("riftMcpApp", mcpApp.state())

    override fun onResume() {
        if (!mainRendererGone) webView.onResume()
        popupWebView?.onResume()
    }

    override fun onPause() {
        popupWebView?.onPause()
        if (!mainRendererGone) webView.onPause()
    }

    override fun destroy() {
        destroyPopup()
        runCatching { mcpApp.destroy() }
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.removeAllViews() }
        runCatching { webView.destroy() }
        runCatching { container.removeAllViews() }
    }

    private fun configureMainWebView(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) safeBrowsingEnabled = true
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
        }
    }

    private fun configurePopupWebView(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        applyBrowserIdentity(view.settings, desktopMode)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
    }

    private fun desktopUserAgent(): String {
        val chromeVersion = Regex("Chrome/([0-9.]+)").find(defaultUserAgent)?.groupValues?.getOrNull(1)
            ?: "140.0.0.0"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
    }

    private fun desktopUserAgentMetadata(): UserAgentMetadata? {
        val base = defaultUserAgentMetadata ?: return null
        val builder = UserAgentMetadata.Builder(base)
            .setMobile(false)
            .setPlatform("Windows")
            .setPlatformVersion("10.0.0")
            .setModel("")
            .setArchitecture("x86")
            .setBitness(64)
            .setWow64(false)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS)) {
            builder.setFormFactors(listOf(UserAgentMetadata.FORM_FACTOR_DESKTOP))
        }
        return builder.build()
    }

    private fun applyBrowserIdentity(settings: WebSettings, enabled: Boolean) {
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = enabled
        settings.builtInZoomControls = enabled
        settings.displayZoomControls = false
        settings.userAgentString = if (enabled) desktopUserAgent() else defaultUserAgent
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            val metadata = if (enabled) desktopUserAgentMetadata() else defaultUserAgentMetadata
            if (metadata != null) runCatching { WebSettingsCompat.setUserAgentMetadata(settings, metadata) }
        }
    }

    private fun installChromeClient() {
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val callback = filePathCallback ?: return false
                return launchFileChooser(callback, fileChooserParams)
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val opener = runCatching { Uri.parse(view.url.orEmpty()) }.getOrNull()
                if (!isUserGesture || opener?.scheme?.equals("https", ignoreCase = true) != true) return false
                return createVisiblePopup(resultMsg)
            }

            override fun onCloseWindow(window: WebView) {
                if (window === popupWebView) destroyPopup()
            }

            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, false, false)
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) { stateChanged() }
        }
    }

    private fun createVisiblePopup(resultMsg: Message): Boolean {
        destroyPopup()
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val popup = WebView(activity)
        val host = android.widget.FrameLayout(activity).apply {
            clipChildren = true
            clipToPadding = true
            setBackgroundColor(0xff0a0d12.toInt())
        }
        popupWebView = popup
        popupHost = host
        configurePopupWebView(popup)
        popup.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        popup.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                return when (uri.scheme?.lowercase()) {
                    "https" -> false
                    "http" -> {
                        view.loadUrl(uri.buildUpon().scheme("https").build().toString())
                        true
                    }
                    in EXTERNAL_SCHEMES -> openExternal(uri)
                    else -> true
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                handler.cancel()
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                super.onPageFinished(view, url)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                RiftRendererCrashGuard.record(activity, "browser-popup", detail)
                val hostView = popupHost
                popupWebView = null
                popupHost = null
                if (hostView != null) runCatching { container.removeView(hostView) }
                RiftRendererCrashGuard.destroyDeadWebView(view)
                runCatching { stateChanged() }
                return true
            }
        }
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) {
                if (window === popupWebView) destroyPopup()
            }

            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, false, false)
            }
        }
        host.addView(
            popup,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val closeSize = (48f * activity.resources.displayMetrics.density + 0.5f).toInt()
        val close = android.widget.Button(activity).apply {
            text = "×"
            contentDescription = "Close authentication popup"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(0xcc101a22.toInt())
            setOnClickListener { destroyPopup() }
        }
        host.addView(
            close,
            android.widget.FrameLayout.LayoutParams(
                closeSize,
                closeSize,
                android.view.Gravity.TOP or android.view.Gravity.END
            )
        )
        container.addView(
            host,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        host.bringToFront()
        transport.webView = popup
        resultMsg.sendToTarget()
        stateChanged()
        return true
    }

    private fun installWebViewClient() {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                return when (uri.scheme?.lowercase()) {
                    "https" -> {
                        updateCookiePolicy(uri.toString())
                        false
                    }
                    "http" -> {
                        val upgraded = uri.buildUpon().scheme("https").build().toString()
                        updateCookiePolicy(upgraded)
                        view.loadUrl(upgraded)
                        true
                    }
                    in EXTERNAL_SCHEMES -> openExternal(uri)
                    else -> true
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                handler.cancel()
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                crashed = false
                inspectorActive = false
                updateCookiePolicy(url)
                stateChanged()
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                mcpApp.ensureInjected(url)
                stateChanged()
                super.onPageFinished(view, url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                stateChanged()
                super.doUpdateVisitedHistory(view, url, isReload)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                mainRendererGone = true
                crashed = true
                RiftRendererCrashGuard.record(activity, "browser-main", detail)
                RiftRendererCrashGuard.destroyDeadWebView(view)
                runCatching { stateChanged() }
                RiftRendererCrashGuard.requestShellRecovery(activity)
                return true
            }
        }
    }

    private fun installDownloads() {
        webView.setDownloadListener { url, userAgent, disposition, mime, _ ->
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return@setDownloadListener
            if (uri.scheme?.lowercase() != "https") return@setDownloadListener
            val guessed = android.webkit.URLUtil.guessFileName(url, disposition, mime)
            val safeName = guessed.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(180)
                .ifBlank { "download" }
            val request = DownloadManager.Request(uri).apply {
                setMimeType(mime ?: URLConnection.guessContentTypeFromName(safeName) ?: "application/octet-stream")
                addRequestHeader("User-Agent", userAgent)
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                    addRequestHeader("Cookie", it)
                }
                setTitle(safeName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, safeName)
            }
            (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        }
    }

    private fun updateCookiePolicy(url: String?) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, isAuthFlowUrl(url))
    }

    private fun isAuthFlowUrl(url: String?): Boolean {
        val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val hostName = uri.host?.lowercase() ?: return false
        return hostName in AUTH_FLOW_HOSTS ||
            hostName.endsWith(".chatgpt.com") ||
            hostName.endsWith(".openai.com")
    }

    private fun destroyPopup() {
        val popup = popupWebView
        val host = popupHost
        popupWebView = null
        popupHost = null
        if (host != null) runCatching { container.removeView(host) }
        if (popup != null) {
            runCatching { popup.stopLoading() }
            runCatching { popup.loadUrl("about:blank") }
            runCatching { popup.removeAllViews() }
            runCatching { popup.destroy() }
        }
        stateChanged()
    }

    private fun openExternal(uri: Uri): Boolean {
        if (uri.scheme?.lowercase() !in EXTERNAL_SCHEMES) return true
        return try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
            true
        } catch (_: Exception) {
            true
        }
    }
}
