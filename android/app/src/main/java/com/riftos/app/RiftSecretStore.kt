package com.riftos.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class RiftSecretStore(context: Context) {
    companion object {
        private const val MAX_NAME_CHARS = 160
        private const val MAX_SECRET_BYTES = 32 * 1024
        private const val MAX_PACKED_BYTES = 64 * 1024
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BYTES = 16
    }

    private val prefs = context.getSharedPreferences("rift-secrets", Context.MODE_PRIVATE)
    private val alias = "riftos.android.secrets.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun set(name: String, value: String): Boolean {
        validateName(name)
        val plain = value.toByteArray(Charsets.UTF_8)
        require(plain.size <= MAX_SECRET_BYTES) { "Secret value exceeds $MAX_SECRET_BYTES UTF-8 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES) { "Unexpected AES-GCM IV size" }
        val payload = cipher.doFinal(plain)
        val packed = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(payload, Base64.NO_WRAP)
        require(packed.toByteArray(Charsets.UTF_8).size <= MAX_PACKED_BYTES) { "Encrypted secret record exceeds storage limit" }
        require(prefs.edit().putString(name, packed).commit()) { "Encrypted secret could not be persisted" }
        return true
    }

    fun get(name: String): String? {
        validateName(name)
        val packed = prefs.getString(name, null) ?: return null
        if (packed.toByteArray(Charsets.UTF_8).size > MAX_PACKED_BYTES) return null
        val parts = packed.split(':', limit = 2)
        if (parts.size != 2) return null
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            require(iv.size == GCM_IV_BYTES) { "Invalid encrypted secret IV" }
            require(data.size >= GCM_TAG_BYTES && data.size <= MAX_SECRET_BYTES + GCM_TAG_BYTES) { "Invalid encrypted secret payload" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(data)
            require(plain.size <= MAX_SECRET_BYTES) { "Decrypted secret exceeds storage limit" }
            String(plain, Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(name: String): Boolean {
        validateName(name)
        val existed = prefs.contains(name)
        require(prefs.edit().remove(name).commit()) { "Secret removal could not be persisted" }
        return existed
    }

    private fun validateName(name: String) {
        require(name.isNotBlank() && name.length <= MAX_NAME_CHARS && name.none { it.code < 0x20 || it.code == 0x7f }) {
            "Invalid secret key"
        }
    }
}
