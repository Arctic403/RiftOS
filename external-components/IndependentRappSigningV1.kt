package com.riftos.external.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Core-owned policy caller only: uses the already existing RiftOS Android
 * keystore alias so APK signing identity remains consistent after Core moves.
 * Never returns the private key; signing requires a declared, consented RAPP.
 */
internal object IndependentRappSigning {
    private const val ALIAS = "riftbuild-apk-v2-rsa-v1"
    private const val KEY_BITS = 2048
    private const val MAX_BYTES = 256 * 1024

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Synchronized
    private fun entry(): KeyStore.PrivateKeyEntry {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            val now = System.currentTimeMillis()
            val spec = KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(KEY_BITS)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=RiftBuild Local APK V2"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(now - 24L * 60L * 60L * 1000L))
                .setCertificateNotAfter(
                    Date(now + 25L * 365L * 24L * 60L * 60L * 1000L))
                .setUserAuthenticationRequired(false)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,
                "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
        }
        return store.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: error("Existing RiftOS signing identity unavailable")
    }

    private fun metadata(cert: X509Certificate): JSONObject = JSONObject()
        .put("schema", "riftos-signing-identity/1")
        .put("identity", "rift-local-apk-signing-v1")
        .put("algorithm", "SHA256withRSA")
        .put("padding", "RSA_PKCS1")
        .put("keyBits", KEY_BITS)
        .put("certificateSha256", sha(cert.encoded))
        .put("publicKeySha256", sha(cert.publicKey.encoded))
        .put("certificateDerBase64",
            Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
        .put("publicKeyDerBase64",
            Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP))

    fun execute(effect: RiftAppAbi.HostEffect): IndependentRappEffects.Result {
        val target = entry()
        val certificate = target.certificate as X509Certificate
        val token = effect.token
        return when (effect.operation) {
            "describe" -> IndependentRappEffects.Result(true, token,
                text = metadata(certificate).toString(), bytes = certificate.encoded)
            "signSha256RsaPkcs1" -> {
                require(effect.bytes.size in 1..MAX_BYTES) {
                    "Core signing payload out of bounds"
                }
                val bytes = Signature.getInstance("SHA256withRSA").apply {
                    initSign(target.privateKey)
                    update(effect.bytes)
                }.sign()
                IndependentRappEffects.Result(true, token,
                    text = metadata(certificate).toString(), bytes = bytes)
            }
            "verifySha256RsaPkcs1" -> {
                require(effect.bytes.size in 1..MAX_BYTES) {
                    "Core signature verification input out of bounds"
                }
                val signature = Base64.decode(
                    JSONObject(effect.text).getString("signatureBase64"),
                    Base64.DEFAULT)
                require(signature.size in 1..8 * 1024) {
                    "RAPP verification signature invalid"
                }
                val valid = Signature.getInstance("SHA256withRSA").apply {
                    initVerify(certificate.publicKey)
                    update(effect.bytes)
                }.verify(signature)
                require(valid) { "RSA/SHA-256 signature verification failed" }
                IndependentRappEffects.Result(true, token,
                    text = JSONObject().put("schema", "riftos-signing-verify/1")
                        .put("verified", true)
                        .put("publicKeySha256", sha(certificate.publicKey.encoded))
                        .toString())
            }
            else -> IndependentRappEffects.Result(false, token,
                error = "Unsupported signing.identity operation")
        }
    }
}
