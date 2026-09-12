package com.riftos.app

import android.view.View
import org.json.JSONObject

/** Renderer contract owned by RiftBrowser. Browser chrome/window lifecycle must not depend on a specific engine. */
interface RiftBrowserEngine {
    val view: View
    val rendererId: String

    fun currentUrl(): String
    fun loadUrl(url: String)
    fun canGoBack(): Boolean
    fun goBack()
    fun canGoForward(): Boolean
    fun goForward()
    fun reload()
    fun state(): JSONObject
    fun onResume()
    fun onPause()
    fun destroy()
}
