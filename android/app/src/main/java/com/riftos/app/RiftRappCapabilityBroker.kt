package com.riftos.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.Executors
import javax.security.auth.x500.X500Principal

/**
 * Generic capability executor for native .rapp programs.
 *
 * The host/runtime protocol stays fixed. New platform capability
 * implementations plug in behind this broker and return a generic
 * HOST_EFFECT_RESULT event to the runtime.
 */
class RiftRappCapabilityBroker(
    private val activity: Activity,
    private val desktop: RiftNativeDesktop
) {
    data class Result(
        val ok: Boolean,
        val token: Int = 0,
        val text: String = "",
        val bytes: ByteArray = ByteArray(0),
        val error: String? = null
    )

    companion object {
        private const val MAX_TEXT_BYTES =
            256 * 1024
        private const val MAX_BINARY_CHUNK_BYTES =
            256 * 1024
        private const val MAX_BINARY_FILE_BYTES =
            256L * 1024L * 1024L
        private const val SIGNING_KEY_SIZE =
            2048
        private const val SIGNING_KEY_ALIAS =
            "riftbuild-apk-v2-rsa-v1"
        private const val MAX_LIST_ENTRIES =
            5_000
        private const val MAX_CLIPBOARD_CHARS =
            64_000
        private const val MAX_SHARE_CHARS =
            256_000
        private const val OPERATION_TIMEOUT_MS =
            60_000L

        private val NO_PROMPT_CAPABILITIES =
            setOf(
                RiftAppAbi.Capability.WINDOW_TITLE
            )
    }

    private val prefs =
        activity.getSharedPreferences(
            "rift-native",
            Context.MODE_PRIVATE
        )

    private val riftRoot =
        File(
            activity.filesDir,
            "riftfs"
        )
            .apply {
                mkdirs()
            }
            .canonicalFile

    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(
                runnable,
                "rift-rapp-capability"
            ).apply {
                isDaemon = true
            }
        }

    private val watchdog =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(
                runnable,
                "rift-rapp-capability-watchdog"
            ).apply {
                isDaemon = true
            }
        }

    fun destroy() {
        executor.shutdownNow()
        watchdog.shutdownNow()
    }

    fun execute(
        appId: String,
        appName: String,
        declared: Set<String>,
        effect: RiftAppAbi.HostEffect,
        complete: (Result) -> Unit
    ) {
        if (
            effect.capability !in
                RiftAppAbi.Capability.DECLARABLE
        ) {
            complete(
                Result(
                    ok = false,
                    token = effect.token,
                    error =
                        "Unknown capability: ${effect.capability}"
                )
            )
            return
        }

        if (
            effect.capability !in
                declared
        ) {
            complete(
                Result(
                    ok = false,
                    token = effect.token,
                    error =
                        "${effect.capability} is not declared by this app"
                )
            )
            return
        }

        val run = {
            dispatch(
                appId,
                appName,
                effect,
                complete
            )
        }

        if (
            effect.capability in
                NO_PROMPT_CAPABILITIES ||
            hasGrant(
                appId,
                effect.capability
            )
        ) {
            run()
            return
        }

        activity.runOnUiThread {
            if (
                activity.isFinishing ||
                activity.isDestroyed
            ) {
                complete(
                    Result(
                        ok = false,
                        token = effect.token,
                        error =
                            "RiftOS host is not available"
                    )
                )
                return@runOnUiThread
            }

            AlertDialog.Builder(
                activity
            )
                .setTitle(
                    "RiftOS permission"
                )
                .setMessage(
                    "$appName wants permission: ${effect.capability}"
                )
                .setPositiveButton(
                    "Allow"
                ) {
                    _,
                    _ ->
                    grant(
                        appId,
                        effect.capability
                    )
                    run()
                }
                .setNegativeButton(
                    "Deny"
                ) {
                    _,
                    _ ->
                    complete(
                        Result(
                            ok = false,
                            token =
                                effect.token,
                            error =
                                "${effect.capability} permission denied"
                        )
                    )
                }
                .setOnCancelListener {
                    complete(
                        Result(
                            ok = false,
                            token =
                                effect.token,
                            error =
                                "${effect.capability} permission denied"
                        )
                    )
                }
                .show()
        }
    }

    private fun dispatch(
        appId: String,
        appName: String,
        effect: RiftAppAbi.HostEffect,
        complete: (Result) -> Unit
    ) {
        val ui =
            effect.capability ==
                RiftAppAbi.Capability.CLIPBOARD_READ ||
                effect.capability ==
                    RiftAppAbi.Capability.CLIPBOARD_WRITE ||
                effect.capability ==
                    RiftAppAbi.Capability.SHARE ||
                effect.capability ==
                    RiftAppAbi.Capability.WINDOW_TITLE

        if (ui) {
            activity.runOnUiThread {
                complete(
                    runCatching {
                        executeNow(
                            appId,
                            appName,
                            effect
                        )
                    }.getOrElse {
                        error ->
                        Result(
                            ok = false,
                            token =
                                effect.token,
                            error =
                                error.message
                                    ?: error
                                        .javaClass
                                        .simpleName
                        )
                    }
                )
            }
            return
        }

        RiftBoundedAsync.submit(
            executor = executor,
            watchdog = watchdog,
            timeoutMs =
                OPERATION_TIMEOUT_MS,
            timeoutValue = {
                Result(
                    ok = false,
                    token =
                        effect.token,
                    error =
                        "RAPP capability timed out"
                )
            },
            failureValue = {
                error ->
                Result(
                    ok = false,
                    token =
                        effect.token,
                    error =
                        error.message
                            ?: error
                                .javaClass
                                .simpleName
                )
            },
            work = {
                RiftDeadline.check(
                    "RAPP capability"
                )
                executeNow(
                    appId,
                    appName,
                    effect
                )
            },
            reply = {
                result ->
                activity.runOnUiThread {
                    complete(
                        result
                    )
                }
            }
        )
    }

    private fun executeNow(
        appId: String,
        appName: String,
        effect: RiftAppAbi.HostEffect
    ): Result =
        when (
            effect.capability
        ) {
            RiftAppAbi.Capability.FS_READ ->
                when (
                    effect.operation
                ) {
                    "readText" ->
                        Result(
                            ok = true,
                            token =
                                effect.token,
                            bytes =
                                readTextForApp(
                                    appId,
                                    effect.text
                                )
                        )

                    "list" ->
                        Result(
                            ok = true,
                            token =
                                effect.token,
                            bytes =
                                listPathForApp(
                                    appId,
                                    effect.text
                                )
                                    .toString()
                                    .toByteArray(
                                        Charsets.UTF_8
                                    )
                        )

                    "stat" ->
                        Result(
                            ok = true,
                            token =
                                effect.token,
                            text =
                                statForApp(
                                    appId,
                                    effect.text
                                )
                                    .toString()
                        )

                    "readBytes" ->
                        readBytesForApp(
                            appId,
                            effect
                        )

                    else ->
                        unsupported(
                            effect
                        )
                }

            RiftAppAbi.Capability.FS_WRITE ->
                when (
                    effect.operation
                ) {
                    "writeText" ->
                        Result(
                            ok = true,
                            token =
                                effect.token,
                            text =
                                writeTextForApp(
                                    appId,
                                    effect.text,
                                    effect.bytes
                                )
                                    .toString()
                        )

                    "writeBytes" ->
                        writeBytesForApp(
                            appId,
                            effect
                        )

                    "mkdir" ->
                        Result(
                            ok = true,
                            token =
                                effect.token,
                            text =
                                mkdirForApp(
                                    appId,
                                    effect.text
                                )
                                    .toString()
                        )

                    "move" ->
                        Result(
                            ok = true,
                            token =
                                effect.token,
                            text =
                                moveForApp(
                                    appId,
                                    effect.text
                                )
                                    .toString()
                        )

                    else ->
                        unsupported(
                            effect
                        )
                }

            RiftAppAbi.Capability.CLIPBOARD_READ ->
                when (
                    effect.operation
                ) {
                    "read" ->
                        Result(
                            ok = true,
                            token =
                                effect.token,
                            bytes =
                                clipboardRead()
                                    .toByteArray(
                                        Charsets.UTF_8
                                    )
                        )

                    else ->
                        unsupported(
                            effect
                        )
                }

            RiftAppAbi.Capability.CLIPBOARD_WRITE ->
                when (
                    effect.operation
                ) {
                    "write" -> {
                        clipboardWrite(
                            effect.text
                        )
                        Result(
                            ok = true,
                            token =
                                effect.token
                        )
                    }

                    else ->
                        unsupported(
                            effect
                        )
                }

            RiftAppAbi.Capability.SHARE ->
                when (
                    effect.operation
                ) {
                    "text" -> {
                        share(
                            effect.text,
                            appName
                        )
                        Result(
                            ok = true,
                            token =
                                effect.token
                        )
                    }

                    else ->
                        unsupported(
                            effect
                        )
                }

            RiftAppAbi.Capability.WINDOW_TITLE ->
                when (
                    effect.operation
                ) {
                    "set" -> {
                        require(
                            effect.text.isNotBlank() &&
                                effect.text.length <=
                                    96
                        ) {
                            "Window title is invalid"
                        }

                        desktop.handle(
                            "desktop.window.title",
                            JSONObject()
                                .put(
                                    "id",
                                    appId
                                )
                                .put(
                                    "title",
                                    effect.text
                                )
                        )

                        Result(
                            ok = true,
                            token =
                                effect.token
                        )
                    }

                    else ->
                        unsupported(
                            effect
                        )
                }

            RiftAppAbi.Capability.SIGNING_IDENTITY ->
                when (
                    effect.operation
                ) {
                    "describe" ->
                        signingIdentityDescribe(
                            effect.token
                        )

                    "signSha256RsaPkcs1" ->
                        signingIdentitySign(
                            effect
                        )

                    "verifySha256RsaPkcs1" ->
                        signingIdentityVerify(
                            effect
                        )

                    else ->
                        unsupported(
                            effect
                        )
                }

            RiftAppAbi.Capability.NETWORK,
            RiftAppAbi.Capability.BUILD_LOCAL ->
                unsupported(
                    effect
                )

            else ->
                unsupported(
                    effect
                )
        }

    private fun unsupported(
        effect: RiftAppAbi.HostEffect
    ): Result =
        Result(
            ok = false,
            token =
                effect.token,
            error =
                "Unsupported ${effect.capability} operation: ${effect.operation}"
        )

    private fun enforceProgramPath(
        appId: String,
        raw: String,
        write: Boolean
    ): String {
        val path =
            RiftVolumePaths
                .normalizeDisplay(
                    raw
                )
        val ownProgram =
            "/C:/Programs/$appId"
        val ownData =
            "/D:/Users/Default/AppData/$appId"
        val publicDataRoots =
            listOf(
                "/D:/Workspace",
                "/D:/Projects",
                "/D:/Packages",
                "/D:/Builds",
                "/D:/Documents",
                "/D:/Downloads",
                "/D:/Temp"
            )

        val allowed =
            path == ownData ||
                path.startsWith(
                    "$ownData/"
                ) ||
                publicDataRoots.any {
                    root ->
                    path == root ||
                        path.startsWith(
                            "$root/"
                        )
                } ||
                (
                    !write &&
                        (
                            path ==
                                ownProgram ||
                                path.startsWith(
                                    "$ownProgram/"
                                )
                            )
                    )

        require(allowed) {
            if (write) {
                "Program writes are restricted to D: user/project data and this app's AppData"
            } else {
                "Program reads are restricted to approved D: data and this app's installed files"
            }
        }

        return path
    }

    private fun safeFile(
        raw: String
    ): File {
        val relative =
            RiftVolumePaths
                .resolveRelative(
                    raw
                )
        val target =
            File(
                riftRoot,
                relative
            )
                .canonicalFile

        require(
            target ==
                riftRoot ||
                target.path.startsWith(
                    riftRoot.path +
                        File.separator
                )
        ) {
            "Path escaped RiftFS"
        }

        return target
    }

    private fun statForApp(
        appId: String,
        rawPath: String
    ): JSONObject {
        val display =
            enforceProgramPath(
                appId,
                rawPath,
                false
            )
        val file =
            safeFile(
                display
            )

        require(file.exists()) {
            "Path not found: $display"
        }

        return statJson(
            file,
            display
        )
    }

    private fun readBytesForApp(
        appId: String,
        effect: RiftAppAbi.HostEffect
    ): Result {
        val request =
            JSONObject(
                effect.text
            )
        val rawPath =
            request
                .optString(
                    "path"
                )
                .trim()
        val offset =
            request.optLong(
                "offset",
                0L
            )
        val length =
            request.optInt(
                "length",
                MAX_BINARY_CHUNK_BYTES
            )

        require(rawPath.isNotBlank()) {
            "Binary read requires path"
        }
        require(offset >= 0L) {
            "Binary read offset is invalid"
        }
        require(
            length in
                1..MAX_BINARY_CHUNK_BYTES
        ) {
            "Binary read length is out of bounds"
        }

        val display =
            enforceProgramPath(
                appId,
                rawPath,
                false
            )
        val file =
            safeFile(
                display
            )

        require(file.isFile) {
            "File not found: $display"
        }
        require(file.length() <= MAX_BINARY_FILE_BYTES) {
            "Binary file exceeds hosted-app I/O limit"
        }
        require(offset <= file.length()) {
            "Binary read offset exceeds file size"
        }

        val count =
            minOf(
                length.toLong(),
                file.length() - offset
            )
                .toInt()
        val bytes =
            ByteArray(
                count
            )

        RandomAccessFile(
            file,
            "r"
        ).use {
            input ->
            input.seek(
                offset
            )
            if (count > 0) {
                input.readFully(
                    bytes
                )
            }
        }

        val metadata =
            JSONObject()
                .put(
                    "schema",
                    "riftos-fs-bytes-read/1"
                )
                .put(
                    "path",
                    display
                )
                .put(
                    "offset",
                    offset
                )
                .put(
                    "bytes",
                    count
                )
                .put(
                    "size",
                    file.length()
                )
                .put(
                    "eof",
                    offset + count >=
                        file.length()
                )

        return Result(
            ok = true,
            token =
                effect.token,
            text =
                metadata.toString(),
            bytes =
                bytes
        )
    }

    private fun writeBytesForApp(
        appId: String,
        effect: RiftAppAbi.HostEffect
    ): Result {
        require(
            effect.bytes.size <=
                MAX_BINARY_CHUNK_BYTES
        ) {
            "Binary write exceeds chunk limit"
        }

        val request =
            JSONObject(
                effect.text
            )
        val rawPath =
            request
                .optString(
                    "path"
                )
                .trim()
        val offset =
            request.optLong(
                "offset",
                0L
            )
        val truncate =
            request.optBoolean(
                "truncate",
                false
            )

        require(rawPath.isNotBlank()) {
            "Binary write requires path"
        }
        require(offset >= 0L) {
            "Binary write offset is invalid"
        }
        require(
            offset <=
                MAX_BINARY_FILE_BYTES -
                    effect.bytes.size
        ) {
            "Binary write exceeds hosted-app file limit"
        }
        if (truncate) {
            require(offset == 0L) {
                "Truncating binary writes must start at offset 0"
            }
        }

        val display =
            enforceProgramPath(
                appId,
                rawPath,
                true
            )
        val file =
            safeFile(
                display
            )
        val parent =
            file.parentFile
                ?: error(
                    "Binary write target has no parent"
                )

        require(
            parent.mkdirs() ||
                parent.isDirectory
        ) {
            "Could not create binary write directory"
        }
        require(
            !file.exists() ||
                file.isFile
        ) {
            "Binary write target is not a file"
        }

        RandomAccessFile(
            file,
            "rw"
        ).use {
            output ->
            if (truncate) {
                output.setLength(
                    0L
                )
            }
            require(
                offset <=
                    output.length()
            ) {
                "Binary write cannot create sparse files"
            }
            output.seek(
                offset
            )
            output.write(
                effect.bytes
            )
            output.fd.sync()
        }

        val metadata =
            statJson(
                file,
                display
            )
                .put(
                    "schema",
                    "riftos-fs-bytes-write/1"
                )
                .put(
                    "offset",
                    offset
                )
                .put(
                    "written",
                    effect.bytes.size
                )

        return Result(
            ok = true,
            token =
                effect.token,
            text =
                metadata.toString()
        )
    }

    private fun mkdirForApp(
        appId: String,
        rawPath: String
    ): JSONObject {
        val display =
            enforceProgramPath(
                appId,
                rawPath,
                true
            )
        val dir =
            safeFile(
                display
            )

        require(
            dir.mkdirs() ||
                dir.isDirectory
        ) {
            "Could not create directory: $display"
        }

        return statJson(
            dir,
            display
        )
    }

    private fun moveForApp(
        appId: String,
        requestText: String
    ): JSONObject {
        val request =
            JSONObject(
                requestText
            )
        val rawFrom =
            request
                .optString(
                    "from"
                )
                .trim()
        val rawTo =
            request
                .optString(
                    "to"
                )
                .trim()
        val replace =
            request.optBoolean(
                "replace",
                false
            )

        require(
            rawFrom.isNotBlank() &&
                rawTo.isNotBlank()
        ) {
            "Move requires from and to"
        }

        val fromDisplay =
            enforceProgramPath(
                appId,
                rawFrom,
                true
            )
        val toDisplay =
            enforceProgramPath(
                appId,
                rawTo,
                true
            )
        val source =
            safeFile(
                fromDisplay
            )
        val target =
            safeFile(
                toDisplay
            )

        require(source.exists()) {
            "Move source not found: $fromDisplay"
        }
        require(
            !target.exists() ||
                replace
        ) {
            "Move target already exists: $toDisplay"
        }
        target.parentFile
            ?.let {
                parent ->
                require(
                    parent.mkdirs() ||
                        parent.isDirectory
                ) {
                    "Could not create move target directory"
                }
            }

        if (target.exists()) {
            require(
                target.isFile &&
                    target.delete()
            ) {
                "Could not replace move target"
            }
        }

        require(
            source.renameTo(
                target
            )
        ) {
            "Could not move path"
        }

        return statJson(
            target,
            toDisplay
        )
    }

    private fun signingIdentityDescribe(
        token: Int
    ): Result {
        val entry =
            signingKeyEntry()
        val certificate =
            entry.certificate as
                X509Certificate
        val encoded =
            certificate.encoded
        val metadata =
            signingIdentityMetadata(
                certificate
            )

        return Result(
            ok = true,
            token =
                token,
            text =
                metadata.toString(),
            bytes =
                encoded
        )
    }

    private fun signingIdentitySign(
        effect: RiftAppAbi.HostEffect
    ): Result {
        require(
            effect.bytes.isNotEmpty() &&
                effect.bytes.size <=
                    MAX_BINARY_CHUNK_BYTES
        ) {
            "Signing payload is out of bounds"
        }

        val entry =
            signingKeyEntry()
        val certificate =
            entry.certificate as
                X509Certificate
        val signature =
            Signature
                .getInstance(
                    "SHA256withRSA"
                )
                .apply {
                    initSign(
                        entry.privateKey
                    )
                    update(
                        effect.bytes
                    )
                }
                .sign()

        return Result(
            ok = true,
            token =
                effect.token,
            text =
                signingIdentityMetadata(
                    certificate
                )
                    .toString(),
            bytes =
                signature
        )
    }

    private fun signingIdentityVerify(
        effect: RiftAppAbi.HostEffect
    ): Result {
        require(
            effect.bytes.isNotEmpty() &&
                effect.bytes.size <=
                    MAX_BINARY_CHUNK_BYTES
        ) {
            "Verification payload is out of bounds"
        }

        val request =
            JSONObject(
                effect.text
            )
        val signatureBytes =
            Base64.decode(
                request.getString(
                    "signatureBase64"
                ),
                Base64.DEFAULT
            )
        require(
            signatureBytes.isNotEmpty() &&
                signatureBytes.size <=
                    8 * 1024
        ) {
            "Verification signature is out of bounds"
        }

        val entry =
            signingKeyEntry()
        val certificate =
            entry.certificate as
                X509Certificate
        val verified =
            Signature
                .getInstance(
                    "SHA256withRSA"
                )
                .apply {
                    initVerify(
                        certificate.publicKey
                    )
                    update(
                        effect.bytes
                    )
                }
                .verify(
                    signatureBytes
                )

        require(verified) {
            "RSA/SHA-256 signature verification failed"
        }

        return Result(
            ok = true,
            token =
                effect.token,
            text =
                JSONObject()
                    .put(
                        "schema",
                        "riftos-signing-verify/1"
                    )
                    .put(
                        "verified",
                        true
                    )
                    .put(
                        "publicKeySha256",
                        sha256(
                            certificate.publicKey.encoded
                        )
                    )
                    .toString()
        )
    }

    private fun signingIdentityMetadata(
        certificate: X509Certificate
    ): JSONObject =
        JSONObject()
            .put(
                "schema",
                "riftos-signing-identity/1"
            )
            .put(
                "identity",
                "rift-local-apk-signing-v1"
            )
            .put(
                "algorithm",
                "SHA256withRSA"
            )
            .put(
                "padding",
                "RSA_PKCS1"
            )
            .put(
                "keyBits",
                SIGNING_KEY_SIZE
            )
            .put(
                "certificateSha256",
                sha256(
                    certificate.encoded
                )
            )
            .put(
                "publicKeySha256",
                sha256(
                    certificate.publicKey.encoded
                )
            )
            .put(
                "certificateDerBase64",
                Base64.encodeToString(
                    certificate.encoded,
                    Base64.NO_WRAP
                )
            )
            .put(
                "publicKeyDerBase64",
                Base64.encodeToString(
                    certificate.publicKey.encoded,
                    Base64.NO_WRAP
                )
            )

    private fun signingKeyEntry():
        KeyStore.PrivateKeyEntry {
        val store =
            KeyStore
                .getInstance(
                    "AndroidKeyStore"
                )
                .apply {
                    load(
                        null
                    )
                }

        if (
            !store.containsAlias(
                SIGNING_KEY_ALIAS
            )
        ) {
            val now =
                System.currentTimeMillis()
            val generator =
                KeyPairGenerator
                    .getInstance(
                        KeyProperties.KEY_ALGORITHM_RSA,
                        "AndroidKeyStore"
                    )
            val spec =
                KeyGenParameterSpec
                    .Builder(
                        SIGNING_KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN or
                            KeyProperties.PURPOSE_VERIFY
                    )
                    .setKeySize(
                        SIGNING_KEY_SIZE
                    )
                    .setDigests(
                        KeyProperties.DIGEST_SHA256
                    )
                    .setSignaturePaddings(
                        KeyProperties.SIGNATURE_PADDING_RSA_PKCS1
                    )
                    .setCertificateSubject(
                        X500Principal(
                            "CN=RiftBuild Local APK V2"
                        )
                    )
                    .setCertificateSerialNumber(
                        BigInteger.ONE
                    )
                    .setCertificateNotBefore(
                        Date(
                            now -
                                24L *
                                    60L *
                                    60L *
                                    1000L
                        )
                    )
                    .setCertificateNotAfter(
                        Date(
                            now +
                                25L *
                                    365L *
                                    24L *
                                    60L *
                                    60L *
                                    1000L
                        )
                    )
                    .setUserAuthenticationRequired(
                        false
                    )
                    .build()

            generator.initialize(
                spec
            )
            generator.generateKeyPair()
        }

        return store.getEntry(
            SIGNING_KEY_ALIAS,
            null
        ) as? KeyStore.PrivateKeyEntry
            ?: error(
                "RiftOS signing identity is unavailable"
            )
    }

    private fun sha256(
        bytes: ByteArray
    ): String =
        MessageDigest
            .getInstance(
                "SHA-256"
            )
            .digest(
                bytes
            )
            .joinToString(
                ""
            ) {
                "%02x".format(
                    it.toInt() and
                        0xff
                )
            }

    private fun readTextForApp(
        appId: String,
        rawPath: String
    ): ByteArray {
        val display =
            enforceProgramPath(
                appId,
                rawPath,
                false
            )
        val file =
            safeFile(
                display
            )

        require(file.isFile) {
            "File not found: $display"
        }
        require(
            file.length() <=
                MAX_TEXT_BYTES
        ) {
            "File exceeds native app text limit"
        }

        val bytes =
            file.readBytes()

        val text =
            bytes.toString(
                Charsets.UTF_8
            )
        require(
            text.toByteArray(
                Charsets.UTF_8
            ).contentEquals(
                bytes
            )
        ) {
            "File is not valid UTF-8: $display"
        }

        return bytes
    }

    private fun writeTextForApp(
        appId: String,
        rawPath: String,
        bytes: ByteArray
    ): JSONObject {
        require(
            bytes.size <=
                MAX_TEXT_BYTES
        ) {
            "Text exceeds native app write limit"
        }

        val text =
            bytes.toString(
                Charsets.UTF_8
            )

        require(
            text.toByteArray(
                Charsets.UTF_8
            ).contentEquals(
                bytes
            )
        ) {
            "Text write payload is not valid UTF-8"
        }

        val display =
            enforceProgramPath(
                appId,
                rawPath,
                true
            )
        val file =
            safeFile(
                display
            )

        atomicWrite(
            file,
            bytes
        )

        return statJson(
            file,
            display
        )
    }

    private fun listPathForApp(
        appId: String,
        rawPath: String
    ): JSONArray {
        val display =
            enforceProgramPath(
                appId,
                rawPath,
                false
            )
        val dir =
            safeFile(
                display
            )

        require(dir.isDirectory) {
            "Directory not found: $display"
        }

        val out =
            JSONArray()

        dir.listFiles()
            ?.sortedWith(
                compareBy<File> {
                    !it.isDirectory
                }.thenBy {
                    it.name.lowercase()
                }
            )
            ?.forEach {
                child ->
                require(
                    out.length() <
                        MAX_LIST_ENTRIES
                ) {
                    "Program directory listing exceeds $MAX_LIST_ENTRIES entries"
                }

                val childPath =
                    "$display/${child.name}"
                        .replace(
                            "//",
                            "/"
                        )

                out.put(
                    statJson(
                        child,
                        childPath
                    )
                )
            }

        return out
    }

    private fun statJson(
        file: File,
        displayPath: String
    ): JSONObject =
        JSONObject()
            .put(
                "path",
                displayPath
            )
            .put(
                "name",
                file.name
            )
            .put(
                "kind",
                if (
                    file.isDirectory
                ) {
                    "directory"
                } else {
                    "file"
                }
            )
            .put(
                "size",
                if (
                    file.isFile
                ) {
                    file.length()
                } else {
                    0L
                }
            )
            .put(
                "modified",
                file.lastModified()
            )
            .put(
                "backend",
                "android-internal"
            )

    private fun atomicWrite(
        target: File,
        bytes: ByteArray
    ) {
        target.parentFile
            ?.mkdirs()

        val temporary =
            File(
                target.parentFile,
                ".${target.name}.rapp-${System.nanoTime()}.tmp"
            )
        val backup =
            File(
                target.parentFile,
                ".${target.name}.rapp-${System.nanoTime()}.backup"
            )

        temporary.writeBytes(
            bytes
        )

        var backedUp =
            false

        try {
            if (
                target.exists()
            ) {
                require(
                    target.isFile
                ) {
                    "App write target is not a file"
                }
                require(
                    target.renameTo(
                        backup
                    )
                ) {
                    "Could not stage existing app file for replacement"
                }
                backedUp =
                    true
            }

            require(
                temporary.renameTo(
                    target
                )
            ) {
                "Could not publish app file"
            }

            if (
                backedUp
            ) {
                backup.delete()
            }
        } catch (
            error: Throwable
        ) {
            temporary.delete()
            if (
                backedUp &&
                !target.exists()
            ) {
                backup.renameTo(
                    target
                )
            }
            throw error
        }
    }

    private fun hasGrant(
        appId: String,
        capability: String
    ): Boolean =
        grants(
            appId
        ).contains(
            capability
        )

    private fun grants(
        appId: String
    ): MutableSet<String> {
        val raw =
            prefs.getString(
                "setting:permissions:$appId",
                null
            )
                ?: return linkedSetOf()

        val array =
            runCatching {
                JSONObject(
                    raw
                )
                    .optJSONArray(
                        "value"
                    )
            }.getOrNull()
                ?: return linkedSetOf()

        val out =
            linkedSetOf<String>()

        for (
            index in
                0 until array.length()
        ) {
            array.optString(
                index
            )
                .takeIf {
                    it.isNotBlank()
                }
                ?.let(
                    out::add
                )
        }

        return out
    }

    private fun grant(
        appId: String,
        capability: String
    ) {
        val set =
            grants(
                appId
            )

        set +=
            capability

        prefs.edit()
            .putString(
                "setting:permissions:$appId",
                JSONObject()
                    .put(
                        "value",
                        JSONArray(
                            set.sorted()
                        )
                    )
                    .put(
                        "modified",
                        System.currentTimeMillis()
                    )
                    .toString()
            )
            .apply()
    }

    private fun clipboardRead():
        String {
        val value =
            (
                activity.getSystemService(
                    Context.CLIPBOARD_SERVICE
                ) as ClipboardManager
                )
                .primaryClip
                ?.getItemAt(0)
                ?.coerceToText(
                    activity
                )
                ?.toString()
                .orEmpty()

        require(
            value.toByteArray(
                Charsets.UTF_8
            ).size <=
                MAX_TEXT_BYTES
        ) {
            "Clipboard text exceeds native app result limit"
        }

        return value
    }

    private fun clipboardWrite(
        text: String
    ) {
        require(
            text.length <=
                MAX_CLIPBOARD_CHARS
        ) {
            "Clipboard text exceeds $MAX_CLIPBOARD_CHARS characters"
        }

        (
            activity.getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager
            )
            .setPrimaryClip(
                ClipData.newPlainText(
                    "RiftOS program",
                    text
                )
            )
    }

    private fun share(
        text: String,
        title: String
    ) {
        require(
            text.length <=
                MAX_SHARE_CHARS
        ) {
            "Share text exceeds $MAX_SHARE_CHARS characters"
        }

        activity.startActivity(
            Intent.createChooser(
                Intent(
                    Intent.ACTION_SEND
                ).apply {
                    type =
                        "text/plain"
                    putExtra(
                        Intent.EXTRA_TEXT,
                        text
                    )
                },
                title
            )
        )
    }
}
