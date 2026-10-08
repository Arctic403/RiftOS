package com.riftos.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Generic, package-external runtime execution boundary.
 *
 * A runtime registers an explicitly pinned Android service in the platform
 * runtime registry. RiftOS owns only discovery, signer validation, transport,
 * bounds and timeouts; it never loads provider code into the RiftOS process.
 *
 * The legacy embedded RAPP executor is retained as a migration fallback only
 * while no registered provider owns the requested execution kind. An invalid
 * registered provider MUST fail closed instead of falling back.
 */
class RiftExternalRuntimeProviders(context: Context) {
    private val app = context.applicationContext
    private val registry = File(
        File(app.filesDir, "riftfs"),
        "system/runtime-providers/registry.json"
    )

    private data class Provider(
        val id: String,
        val kind: String,
        val packageName: String,
        val serviceName: String,
        val signerSha256: String
    )

    companion object {
        const val REGISTRY_SCHEMA = "riftos-runtime-providers/1"
        const val EXECUTION_SCHEMA = "riftos-runtime-exec/1"
        const val PROVIDER_ACTION = "com.riftos.runtime.EXECUTE_V1"
        const val BINDER_DESCRIPTOR = "riftos.runtime.provider/1"

        private const val MAX_REGISTRY_BYTES = 16 * 1024L
        private const val MAX_PROVIDERS = 12
        // Stay under Android's Binder transaction size ceiling.
        private const val MAX_RUNTIME_BYTES = 384 * 1024
        private const val MAX_INPUT_BYTES = 192 * 1024
        private const val MAX_RETURN_BYTES = 192 * 1024
        private const val BIND_TIMEOUT_MS = 1200L
        private const val EXEC_TIMEOUT_MS = 4300L
        private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,79}$")
        private val SAFE_PACKAGE = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
        private val SAFE_SERVICE = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
        private val SAFE_SHA256 = Regex("^[0-9a-f]{64}$")
    }

    /** Registry entries are installed platform configuration, never app manifests. */
    private fun providers(): List<Provider> {
        if (!registry.exists()) return emptyList()
        require(registry.isFile && registry.length() in 1..MAX_REGISTRY_BYTES) {
            "Runtime provider registry size or type is invalid"
        }
        val root = JSONObject(registry.readText(Charsets.UTF_8))
        require(root.optString("schema") == REGISTRY_SCHEMA) {
            "Unsupported runtime provider registry schema"
        }
        val entries = root.getJSONArray("providers")
        require(entries.length() <= MAX_PROVIDERS) {
            "Too many registered runtime providers"
        }
        val kinds = HashSet<String>()
        val ids = HashSet<String>()
        return (0 until entries.length()).map { index ->
            val value = entries.getJSONObject(index)
            val provider = Provider(
                id = value.getString("id"),
                kind = value.getString("executorKind"),
                packageName = value.getString("package"),
                serviceName = value.getString("service"),
                signerSha256 = value.getString("signerSha256")
            )
            require(provider.id.matches(SAFE_ID) && ids.add(provider.id)) {
                "Duplicate or invalid runtime provider id"
            }
            require(provider.kind.matches(SAFE_ID) && kinds.add(provider.kind)) {
                "Duplicate or invalid runtime execution kind"
            }
            require(provider.packageName.matches(SAFE_PACKAGE) &&
                    provider.serviceName.matches(SAFE_SERVICE) &&
                    provider.serviceName.startsWith(provider.packageName + ".")) {
                "Runtime provider package/service identity is invalid"
            }
            require(provider.signerSha256.matches(SAFE_SHA256)) {
                "Runtime provider certificate fingerprint is invalid"
            }
            provider
        }
    }

    private fun verifyInstalled(p: Provider): ComponentName {
        val pm = app.packageManager
        val component = ComponentName(p.packageName, p.serviceName)
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(
                p.packageName,
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                )
            )
        } else {
            pm.getPackageInfo(
                p.packageName,
                if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
                else PackageManager.GET_SIGNATURES
            )
        }
        val certificates = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray() }.orEmpty()
        }
        require(certificates.size == 1 &&
                sha256(certificates.single()) == p.signerSha256) {
            "Installed runtime provider signer does not match registry pin: ${p.id}"
        }
        @Suppress("DEPRECATION")
        val service = if (Build.VERSION.SDK_INT >= 33) {
            pm.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0L))
        } else {
            pm.getServiceInfo(component, 0)
        }
        require(service.exported && service.enabled && service.packageName == p.packageName) {
            "Registered runtime provider service is not available: ${p.id}"
        }
        @Suppress("DEPRECATION")
        val visible = pm.queryIntentServices(
            Intent(PROVIDER_ACTION).setPackage(p.packageName),
            0
        )
        require(visible.any {
            it.serviceInfo?.name == p.serviceName &&
                it.serviceInfo?.packageName == p.packageName
        }) {
            "Runtime service does not advertise the generic execution contract: ${p.id}"
        }
        return component
    }

    fun status(): JSONObject {
        val entries = JSONArray()
        for (p in providers()) {
            val failure = runCatching { verifyInstalled(p) }.exceptionOrNull()
            entries.put(
                JSONObject()
                    .put("id", p.id)
                    .put("executorKind", p.kind)
                    .put("package", p.packageName)
                    .put("service", p.serviceName)
                    .put("ready", failure == null)
                    .put("error", failure?.message ?: JSONObject.NULL)
            )
        }
        return JSONObject()
            .put("schema", "riftos-runtime-status/1")
            .put("registrySchema", REGISTRY_SCHEMA)
            .put("registered", entries.length())
            .put("providers", entries)
            .put("legacyEmbeddedFallback", true)
            .put("state", if (registry.exists()) "configured" else "no-external-providers")
    }

    fun execute(
        executorKind: String,
        runtime: ByteArray,
        input: ByteArray,
        outputCapacity: Int,
        fallback: () -> ByteArray
    ): ByteArray {
        val provider = providers().firstOrNull { it.kind == executorKind }
            ?: return fallback()
        require(runtime.size in 1..MAX_RUNTIME_BYTES &&
                input.size <= MAX_INPUT_BYTES &&
                outputCapacity in 1..(512 * 1024)) {
            "External runtime request exceeds bounded Binder transport"
        }
        val component = verifyInstalled(provider)
        val connected = CompletableFuture<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service == null) {
                    connected.completeExceptionally(
                        IllegalStateException("Runtime provider returned no Binder")
                    )
                } else connected.complete(service)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                if (!connected.isDone) connected.completeExceptionally(
                    IllegalStateException("Runtime provider disconnected")
                )
            }
            override fun onBindingDied(name: ComponentName?) {
                if (!connected.isDone) connected.completeExceptionally(
                    IllegalStateException("Runtime provider binding died")
                )
            }
            override fun onNullBinding(name: ComponentName?) {
                if (!connected.isDone) connected.completeExceptionally(
                    IllegalStateException("Runtime provider rejected binding")
                )
            }
        }
        require(app.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)) {
            "Runtime provider service binding failed: ${provider.id}"
        }
        val worker = Executors.newSingleThreadExecutor { action ->
            Thread(action, "rift-runtime-provider").apply { isDaemon = true }
        }
        try {
            val binder = connected.get(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val future = worker.submit<ByteArray> {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(BINDER_DESCRIPTOR)
                    data.writeString(EXECUTION_SCHEMA)
                    data.writeString(executorKind)
                    data.writeByteArray(runtime)
                    data.writeByteArray(input)
                    data.writeInt(outputCapacity.coerceAtMost(MAX_RETURN_BYTES))
                    require(binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) {
                        "Runtime provider does not implement execution transaction"
                    }
                    reply.readException()
                    reply.createByteArray() ?: error("Runtime provider returned no output")
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
            val result = future.get(EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            require(result.isNotEmpty() && result.size <= outputCapacity &&
                    result.size <= MAX_RETURN_BYTES) {
                "Runtime provider returned invalid output size"
            }
            return result
        } finally {
            worker.shutdownNow()
            app.unbindService(connection)
        }
    }

    private fun sha256(input: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(input)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
