package com.riftos.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import android.os.Process

/**
 * Host-owned Core ABI implemented by the separately selected external Core.
 *
 * Only separately qualified external Core/Shell implementations can be selected.
 * Authentication, admin consent, IPC bounds and Android components stay host-owned.
 *
 * Data across this seam is only JSON + Android Context. A future ABI revision
 * must narrow the Context into explicit platform services before untrusted code.
 * This is a same-UID trusted-component boundary, NOT a security sandbox.
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

/**
 * Additional required Core ownership view for Shell recovery, diagnostics
 * and provider discovery. Real external Core must implement BOTH interfaces;
 * APK-host Recovery never instantiates any embedded Core substitute.
 */
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

/**
 * Optional independent Core app-connection interface. The selected v0.6.3
 * retains its original V1 ABI and fails closed if it lacks this extension.
 * This is NOT the retired v0.7 system-services interface.
 */
interface RiftCoreExternalAppConnectionsV1 {
    fun appConnectionRequest(context: Context, request: JSONObject): JSONObject
}

/**
 * Stable APK-owned JavaScript execution primitive for independent Core DEXes.
 *
 * QUICKJS IS THE ONE EMBEDDED EXECUTION ENGINE EXCEPTION: this service owns
 * neither RAPP lifecycle, state, consent, capabilities nor the Core. Only
 * bounded JavaScript input/output crosses this ABI. There is no embedded-Core
 * fallback, and external Core must validate/persist/publish its own results.
 *
 * The implementation can change behind this V1 contract in a future signed
 * host release without requiring an external Core source rewrite.
 */
object RiftHostQuickJsV1 {
    private val engine = RiftRappQuickJsExecutor()

    fun abiVersion(): Int = 1

    @JvmStatic
    fun execute(runtime: ByteArray, input: ByteArray, outputLimit: Int): ByteArray =
        engine.execute(runtime, input, outputLimit)
}

/**
 * APK-owned generic native-buffer execution primitive for independently
 * installed Core DEXes. Mirrors the existing embedded Core backend without
 * forwarding RAPP identity, permissions, grants, or lifecycle to the APK.
 *
 * The isolated native-buffer worker enforces its own byte and time bounds.
 * Context is used only to bind that Android worker; no Core singleton is read.
 */
object RiftHostNativeBufferV1 {
    fun abiVersion(): Int = 1

    @JvmStatic
    fun execute(context: Context, runtime: ByteArray, input: ByteArray,
                outputLimit: Int): ByteArray {
        require(runtime.size in 1..256 * 1024) { "Native runtime exceeds host bound" }
        require(input.size <= 512 * 1024) { "Native input exceeds host bound" }
        require(outputLimit in 1..512 * 1024) { "Native output exceeds host bound" }
        val result = RiftNativeBufferCompilerService.compile(
            context.applicationContext, runtime, input, outputLimit)
        val status = result.getString("status") ?: "host-reject"
        require(status == "success") {
            buildString {
                append("Native-buffer runtime ").append(status)
                append(" host=").append(result.getInt("hostStatus", -1))
                append(" return=").append(result.getLong("returnValue", 0xffffffffL))
                result.getString("detail")?.takeIf { it.isNotBlank() }?.let {
                    append(" ").append(it.take(160))
                }
            }
        }
        val bytes = result.getByteArray("output")
            ?: error("Native-buffer execution succeeded without output")
        require(bytes.size <= outputLimit) { "Native-buffer output exceeded capacity" }
        return bytes
    }
}

/**
 * Transport ONLY: present Core-owned permission consent and UI effects through
 * the already registered, ticketed and expiring Android Shell IPC channel.
 * The external Core remains responsible for declaration, installed identity,
 * freshness checks, grant persistence and every effect authorization.
 */
object RiftHostCapabilityUiV1 {
    fun abiVersion(): Int = 1

    @JvmStatic
    fun consent(appId: String, appName: String, capability: String,
                reply: (Boolean) -> Unit) {
        require(appId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$")) &&
            appName.length in 1..128 && capability.length in 1..64) {
            "Capability UI request identity invalid"
        }
        RiftCoreShellRemoteUiBroker.ensureRegistered()
        RiftCoreShellCapabilityRequests.requestConsent(
            appId, appName, capability, reply)
    }

    @JvmStatic
    fun uiEffect(appId: String, appName: String, capability: String,
                 operation: String, requestId: Int, token: Int, text: String, bytes: ByteArray,
                 reply: (Boolean, Int, String, ByteArray, String) -> Unit) {
        require(appId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{1,63}$")) &&
            appName.length in 1..128 && operation.length in 1..32 &&
            text.toByteArray(Charsets.UTF_8).size <= 32 * 1024 &&
            bytes.isEmpty()) { "Shell capability UI payload invalid" }
        require(capability in setOf("clipboard.read", "clipboard.write",
            "share", "apk.install", "window.title")) { "Unsupported Shell UI transport" }
        RiftCoreShellRemoteUiBroker.ensureRegistered()
        RiftCoreShellCapabilityRequests.requestUiEffect(
            appId, appName, RiftAppAbi.HostEffect(
                capability = capability, operation = operation,
                token = token, requestId = requestId, text = text, bytes = bytes)
        ) { result ->
            reply(result.ok, result.token, result.text, result.bytes,
                result.error.orEmpty())
        }
    }
}

/**
 * Raw, bounded registered local-build primitive. External Core alone checks
 * manifest declarations, installed identity, consent and grant revocation.
 * Host owns only Android-managed compiler/JVM/D8 plumbing; never RAPP policy.
 */
object RiftHostBuildLocalV1 {
    fun abiVersion(): Int = 1

    @JvmStatic
    fun execute(context: Context, operation: String, requestText: String): String {
        require(requestText.toByteArray(Charsets.UTF_8).size <= 128 * 1024) {
            "Build request exceeds generic host bound"
        }
        val request = if (requestText.isBlank()) JSONObject() else JSONObject(requestText)
        val tool = RiftLocalBuildCapability(context.applicationContext)
        val value = when (operation) {
            "toolchainStatus" -> tool.jvmToolchainStatus()
            "compilerRun" -> {
                val project = request.getString("project").trim()
                val compiler = request.getString("compilerId").trim()
                require(project.isNotBlank() &&
                    compiler.matches(Regex("^[A-Za-z0-9._+-]{1,80}$"))) {
                    "Registered build request invalid"
                }
                tool.compilerRunInline(project, compiler, request.getJSONObject("request"))
            }
            "jvmDex" -> {
                val project = request.getString("project").trim()
                require(project.isNotBlank()) { "Build project missing" }
                tool.dexJvmClasses(project, request.getString("classesDir"),
                    request.getString("outputDir"), request.optInt("minSdk", 26))
            }
            else -> error("Unsupported generic host build operation")
        }
        return value.toString().also {
            require(it.toByteArray(Charsets.UTF_8).size <= 256 * 1024) {
                "Generic build response exceeds bound"
            }
        }
    }
}

/** E0 shell-side portable lifecycle envelope, not activated or connected yet. */
interface RiftShellPresentationV1 {
    fun restore(snapshot: JSONObject): JSONObject
    fun dispatch(method: String, args: JSONObject): JSONObject
    fun snapshot(): JSONObject
    fun close()
}

/**
 * APK-owned Core V1 selector. No in-process switch is permitted: selection is
 * one-time at bootstrap. If external qualification or startup fails, the host
 * fails closed and only user-managed Recovery may select a different revision.
 * A staged candidate cannot be promoted without a verified receipt + pointer.
 */
internal object RiftHostCoreComponents {
    const val CORE_SCHEMA = "riftos.host.core-component/1"
    const val SHELL_SCHEMA = "riftos.host.shell-presentation/1"
    const val ABI_VERSION = 1

    // Production is external-only. No embedded Core object or fallback remains.
    @Volatile private var selected: RiftCoreComponentV1? = null
    @Volatile private var selectedKind = "unselected"

    fun core(): RiftCoreComponentV1 =
        selected ?: error("No qualified external Core is active; use manual Recovery")

    fun executionView(): RiftCoreExecutionViewV1 =
        core() as? RiftCoreExecutionViewV1
            ?: error("Selected Core lacks independent RAPP execution/recovery ABI")

    fun externalAppConnections(): RiftCoreExternalAppConnectionsV1 =
        core() as? RiftCoreExternalAppConnectionsV1
            ?: error("Selected Core does not support external app connections")

    /** No implementation is constructed if no selected, qualified external Core exists. */
    @Synchronized
    fun initializeAtBoot(application: android.app.Application) {
        check(selected == null && selectedKind == "unselected") {
            "Core startup cannot hot-swap a running implementation"
        }
        val candidate = RiftCoreCandidateSwitch.selectAtBoot(application)
            ?: error("External Core is not selected; open RiftOS manual Recovery")
        try {
            RiftCoreRecoveryDiagnostics.installExternalCrashObserver(application)
            candidate.initialize(application)
            selected = candidate
            val sha = RiftCoreCandidateSwitch.status().optString("selectedSha256")
            RiftCoreRecoveryDiagnostics.recordSelected(application, sha)
            selectedKind = "external-selected"
        } catch (failure: Throwable) {
            RiftCoreRecoveryDiagnostics.recordCandidateFailure(
                application, "initialize", failure)
            // Never activate a backup or return to embedded Core automatically.
            throw failure
        }
    }

    fun status(): JSONObject = JSONObject()
        .put("schema", CORE_SCHEMA)
        .put("abi", ABI_VERSION)
        .put("selected", selectedKind)
        .put("externalCoreEnabled", selectedKind == "external-selected")
        .put("externalShellEnabled", true)
        .put("embeddedFallbackAvailable", false)
        .put("candidateSwitch", RiftCoreCandidateSwitch.status())
}
