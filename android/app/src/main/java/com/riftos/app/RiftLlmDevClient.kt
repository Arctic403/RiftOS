package com.riftos.app

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.ExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Fixed-purpose, token-gated Binder client for RiftLLM's standalone Dev Lab API. */
class RiftLlmDevClient(context: Context) {
    companion object {
        private const val TARGET_PACKAGE = "com.riftllm.app"
        private const val AUTHORITY = "com.riftllm.app.devlab"
        private const val TOKEN_KEY = "riftllm.dev.token"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_JSON = "json"
        private const val RESULT_JSON = "json"
        private const val MAX_REQUEST_JSON_BYTES = 512 * 1024
        private const val MAX_RESPONSE_JSON_BYTES = 512 * 1024
        private const val IPC_TIMEOUT_MS = 12_000L
        private val URI: Uri = Uri.parse("content://$AUTHORITY")
        private val METHODS = mapOf(
            "sync_source" to "sync_source",
            "sync_missing" to "sync_missing",
            "load_source" to "load_source",
            "list_staged" to "list_staged",
            "stage" to "stage",
            "delete" to "delete",
            "unstage" to "unstage",
            "reset" to "reset",
            "snapshot" to "snapshot",
            "list_snapshots" to "list_snapshots",
            "get_snapshot" to "get_snapshot",
            "get_patch" to "get_patch",
            "list_benchmarks" to "list_benchmarks",
            "get_benchmark" to "get_benchmark",
            "text_encoding_begin" to "text_encoding_begin",
            "text_encoding_append" to "text_encoding_append",
            "text_encoding_commit" to "text_encoding_commit",
            "text_encoding_start" to "text_encoding_start",
            "text_encoding_status" to "text_encoding_status",
            "train_data_begin" to "train_data_begin",
            "train_data_append" to "train_data_append",
            "train_data_commit" to "train_data_commit",
            "train_data_status" to "train_data_status",
            "train_canary_start" to "train_canary_start",
            "train_canary_status" to "train_canary_status",
            "ack_publish" to "ack_publish"
        )
    }

    private val appContext = context.applicationContext
    private val secrets = RiftSecretStore(appContext)
    private val ipcExecutor = ThreadPoolExecutor(
        0,
        2,
        30L,
        TimeUnit.SECONDS,
        SynchronousQueue<Runnable>()
    )

    fun execute(args: JSONObject): Any {
        return when (val op = args.optString("op").trim().lowercase()) {
            "pair" -> pair(args.optString("token"))
            "unpair" -> JSONObject().put("unpaired", secrets.remove(TOKEN_KEY)).put("paired", false)
            "status" -> status()
            else -> {
                val method = METHODS[op] ?: throw IllegalArgumentException("Unsupported RiftLLM bridge operation: $op")
                val token = secrets.get(TOKEN_KEY)?.trim().orEmpty()
                require(token.isNotEmpty()) { "RiftLLM Dev API is not paired" }
                call(method, token, args.optJSONObject("request") ?: JSONObject())
            }
        }
    }

    private fun pair(rawToken: String): JSONObject {
        val token = rawToken.trim()
        require(token.matches(Regex("^[A-Fa-f0-9]{64}$"))) { "RiftLLM Dev API token must be the 64-character token shown by RiftLLM Dev Lab" }
        require(providerInstalled()) { "RiftLLM Dev API provider is not installed or visible" }
        val provider = call("status", token, JSONObject())
        require(provider is JSONObject) { "RiftLLM Dev API status returned an unexpected payload" }
        secrets.set(TOKEN_KEY, token)
        return JSONObject()
            .put("paired", true)
            .put("installed", true)
            .put("authority", AUTHORITY)
            .put("provider", provider)
    }

    private fun status(): JSONObject {
        val installed = providerInstalled()
        val token = secrets.get(TOKEN_KEY)?.trim().orEmpty()
        val result = JSONObject()
            .put("installed", installed)
            .put("paired", token.isNotEmpty())
            .put("authority", AUTHORITY)
            .put("transport", "android-binder")
        if (!installed || token.isEmpty()) return result.put("apiReachable", false)
        return try {
            result.put("provider", call("status", token, JSONObject())).put("apiReachable", true)
        } catch (error: Throwable) {
            result.put("apiReachable", false).put("error", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun providerInstalled(): Boolean = runCatching {
        val info = appContext.packageManager.resolveContentProvider(AUTHORITY, PackageManager.MATCH_ALL)
        info?.packageName == TARGET_PACKAGE
    }.getOrDefault(false)

    private fun call(method: String, token: String, request: JSONObject): Any {
        val future = try {
            ipcExecutor.submit<Any> { callDirect(method, token, request) }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            throw IllegalStateException("RiftLLM Dev API IPC workers are occupied by stalled provider calls", error)
        }
        return try {
            future.get(IPC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            future.cancel(true)
            throw IllegalStateException("RiftLLM Dev API call timed out after ${IPC_TIMEOUT_MS}ms", error)
        } catch (error: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    private fun callDirect(method: String, token: String, request: JSONObject): Any {
        val requestText = request.toString()
        require(requestText.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_JSON_BYTES) {
            "RiftLLM Dev API request exceeds 512 KiB V1 IPC limit"
        }
        val extras = Bundle().apply {
            putString(EXTRA_TOKEN, token)
            putString(EXTRA_JSON, requestText)
        }
        val reply = appContext.contentResolver.call(URI, method, null, extras)
            ?: throw IllegalStateException("RiftLLM Dev API returned no Bundle")
        val raw = reply.getString(RESULT_JSON)
            ?: throw IllegalStateException("RiftLLM Dev API returned no JSON")
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_JSON_BYTES) {
            "RiftLLM Dev API response exceeds 512 KiB V1 IPC limit"
        }
        return JSONTokener(raw).nextValue()
            ?: throw IllegalStateException("RiftLLM Dev API returned an empty JSON value")
    }
}
