package com.riftos.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Stable Android-only transport for external Core owned app connections.
 * No app registry, authorization policy, filesystem or compiler lives here.
 * The independent Core pins a user's approved package signer and operation.
 *
 * This transport is only called after authenticated :riftShell -> Core IPC.
 * It cannot be invoked by a RAPP effect or the MCP/relay.
 */
object RiftHostExternalAppBinderV1 {
    private const val CONNECT_TIMEOUT_SECONDS = 5L
    private const val MAX_REQUEST = 24 * 1024
    private const val MAX_RESPONSE = 128 * 1024
    private val COMPONENT_SEGMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val PACKAGE_NAME = Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+$")

    @JvmStatic
    fun abiVersion(): Int = 1

    /** Platform derives the identity from Android PackageManager; never from a caller claim. */
    @JvmStatic
    fun identity(context: Context, packageName: String, serviceClass: String): JSONObject {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid external app package" }
        // Android applicationId and Kotlin/Java package need not share a prefix.
        // The PackageManager ComponentName lookup below proves real APK ownership.
        require(serviceClass.length in 3..180 &&
            serviceClass.split('.').size >= 2 &&
            serviceClass.split('.').all { COMPONENT_SEGMENT.matches(it) }) {
            "External app service class name invalid"
        }
        val pm = context.packageManager
        val component = ComponentName(packageName, serviceClass)
        val service = pm.getServiceInfo(component, 0)
        require(service.exported && service.packageName == packageName) {
            "External app service is not exported by its package"
        }
        @Suppress("DEPRECATION")
        val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo ?: error("External app signer unavailable")
        val signers = if (signing.hasMultipleSigners()) signing.apkContentsSigners
                      else signing.signingCertificateHistory
        require(signers.isNotEmpty()) { "External app has no signing certificate" }
        // Pin the installed APK's current signer(s), not the rotation history.
        val active = signing.apkContentsSigners
        require(active.isNotEmpty()) { "External app current signer missing" }
        val fingerprints = active.map { signature ->
            val bytes = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
            bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }.sorted()
        val identity = MessageDigest.getInstance("SHA-256")
            .digest(fingerprints.joinToString(":").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return JSONObject().put("package", packageName)
            .put("service", serviceClass).put("signerSha256", identity)
    }

    /**
     * Synchronous Binder call: ALWAYS dispatch from a background worker.
     * Android Binder authenticates the remote process; the Core checks the signer
     * before every invocation. No clipboard, reflection, or arbitrary transaction ID.
     */
    @JvmStatic
    fun execute(context: Context, packageName: String, serviceClass: String,
                descriptor: String, request: String): JSONObject {
        require(Thread.currentThread().name != "main") {
            "External app Binder request must not run on the UI thread"
        }
        identity(context, packageName, serviceClass)
        require(descriptor.length in 3..160 && descriptor.all {
            it.isLetterOrDigit() || it == '.' || it == '_' || it == '-'
        }) { "Invalid Binder contract" }
        require(request.toByteArray(Charsets.UTF_8).size in 1..MAX_REQUEST) {
            "External app request exceeds bounded transport"
        }
        val bound = CountDownLatch(1)
        val endpoint = java.util.concurrent.atomic.AtomicReference<IBinder?>(null)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (name.packageName == packageName && name.className == serviceClass) {
                    endpoint.set(service)
                }
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { endpoint.set(null) }
            override fun onNullBinding(name: ComponentName) { bound.countDown() }
        }
        val intent = Intent().setComponent(ComponentName(packageName, serviceClass))
        val didBind = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        require(didBind) { "External app Binder service could not be bound" }
        try {
            require(bound.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "External app Binder connection timed out"
            }
            val binder = endpoint.get() ?: error("External app returned no Binder endpoint")
            // A custom Binder may implement INTERFACE_TRANSACTION without
            // attachInterface(). Codynex Editor intentionally does exactly this.
            // Ask the actual endpoint instead of relying on interfaceDescriptor.
            val descriptorData = Parcel.obtain()
            val descriptorReply = Parcel.obtain()
            try {
                check(binder.transact(IBinder.INTERFACE_TRANSACTION,
                    descriptorData, descriptorReply, 0)) {
                    "External Binder did not expose an interface description"
                }
                require(descriptorReply.readString() == descriptor) {
                    "External Binder contract mismatch"
                }
            } finally {
                descriptorReply.recycle()
                descriptorData.recycle()
            }
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(descriptor)
                data.writeString(request)
                check(binder.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) {
                    "External Binder rejected the request"
                }
                reply.readException()
                val value = reply.readString() ?: error("External Binder returned no result")
                require(value.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE) {
                    "External Binder response exceeded limit"
                }
                return JSONObject(value)
            } finally {
                reply.recycle()
                data.recycle()
            }
        } finally {
            context.unbindService(serviceConnection)
        }
    }
}
