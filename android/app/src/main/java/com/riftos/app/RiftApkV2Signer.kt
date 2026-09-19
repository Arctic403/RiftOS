package com.riftos.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Narrow APK Signature Scheme v2 signer/verifier used by RiftBuild bootstrap V0.
 *
 * Scope is intentionally fixed:
 * - one AndroidKeyStore-owned RSA-2048 key;
 * - one signer;
 * - RSASSA-PKCS1-v1_5 with SHA-256 (v2 algorithm id 0x0103);
 * - non-ZIP64 APKs bounded by RiftBuild before entry.
 */
class RiftApkV2Signer(context: Context) {
    data class SignResult(
        val outputBytes: Long,
        val apkSha256: String,
        val certificateSha256: String,
        val publicKeySha256: String,
        val contentDigestSha256: String,
        val signingBlockBytes: Int
    )

    data class VerifyResult(
        val apkSha256: String,
        val certificateSha256: String,
        val publicKeySha256: String,
        val contentDigestSha256: String,
        val signingBlockBytes: Int,
        val signatureAlgorithmId: Int
    )

    private data class ZipLayout(
        val eocdOffset: Int,
        val centralDirOffset: Int,
        val centralDirSize: Int
    )

    private data class SigningBlock(
        val start: Int,
        val size: Int,
        val v2Value: ByteArray
    )

    private class Cursor(private val bytes: ByteArray) {
        var offset: Int = 0
            private set

        fun remaining(): Int = bytes.size - offset

        fun u32(): Int {
            require(remaining() >= 4) { "truncated v2 field" }
            val value = readU32(bytes, offset)
            require(value <= Int.MAX_VALUE.toLong()) { "v2 field length overflow" }
            offset += 4
            return value.toInt()
        }

        fun bytes(length: Int): ByteArray {
            require(length >= 0 && remaining() >= length) { "truncated v2 payload" }
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }

        fun lengthPrefixed(): ByteArray {
            val length = u32()
            return bytes(length)
        }

        fun requireDone(label: String) {
            require(remaining() == 0) { "$label contains trailing bytes" }
        }
    }

    companion object {
        private const val KEY_ALIAS = "riftbuild-apk-v2-rsa-v1"
        private const val KEY_SIZE = 2048
        private const val SIGNATURE_ALGORITHM_ID = 0x0103
        private const val V2_BLOCK_ID = 0x7109871a
        private const val CHUNK_BYTES = 1024 * 1024
        private const val MAX_APK_BYTES = 256 * 1024 * 1024
        private const val EOCD_MIN_BYTES = 22
        private const val EOCD_MAX_COMMENT = 0xffff
        private const val ZIP_EOCD = 0x06054b50L
        private val APK_SIG_MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

        private fun readU16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8)

        private fun readU32(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xffL) or
                ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xffL) shl 24)

        private fun readU64(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (index in 0 until 8) {
                value = value or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8))
            }
            return value
        }
    }

    private val appContext = context.applicationContext

    fun sign(unsignedApk: File, outputApk: File): SignResult {
        require(unsignedApk.isFile) { "unsigned APK is missing" }
        require(unsignedApk.length() in 1..MAX_APK_BYTES.toLong()) { "unsigned APK exceeds signer bound" }
        require(!outputApk.exists()) { "signed APK output already exists" }

        val unsigned = unsignedApk.readBytes()
        val layout = parseZipLayout(unsigned)
        require(findSigningBlock(unsigned, layout.centralDirOffset) == null) { "APK already contains an APK Signing Block" }

        val contentDigest = computeContentDigest(
            unsigned,
            beforeSigningStart = 0,
            beforeSigningEnd = layout.centralDirOffset,
            centralDirStart = layout.centralDirOffset,
            eocdStart = layout.eocdOffset,
            signingBlockOffsetForDigest = layout.centralDirOffset
        )

        val keyEntry = keyEntry()
        val certificate = keyEntry.certificate as X509Certificate
        certificate.checkValidity()
        val certificateBytes = certificate.encoded
        val publicKeyBytes = certificate.publicKey.encoded
        require(publicKeyBytes.isNotEmpty()) { "signing public key is not X.509 encodable" }

        val digests = encodeIntAndLengthPrefixedBytes(SIGNATURE_ALGORITHM_ID, contentDigest)
        val certificates = encodeSequence(certificateBytes)
        val signedData = encodeSequence(digests, certificates, ByteArray(0))

        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(keyEntry.privateKey)
            update(signedData)
        }.sign()

        val signatures = encodeIntAndLengthPrefixedBytes(SIGNATURE_ALGORITHM_ID, signature)
        val signer = encodeSequence(signedData, signatures, publicKeyBytes)
        val signers = encodeSequence(signer)
        val v2Value = encodeSequence(signers)
        val signingBlock = buildApkSigningBlock(v2Value)

        val signedCentralDirOffset = layout.centralDirOffset + signingBlock.size
        val eocd = unsigned.copyOfRange(layout.eocdOffset, unsigned.size)
        writeU32(eocd, 16, signedCentralDirOffset.toLong())

        val output = ByteArrayOutputStream(unsigned.size + signingBlock.size).apply {
            write(unsigned, 0, layout.centralDirOffset)
            write(signingBlock)
            write(unsigned, layout.centralDirOffset, layout.eocdOffset - layout.centralDirOffset)
            write(eocd)
        }.toByteArray()

        val temp = File(outputApk.parentFile, "." + outputApk.name + ".tmp")
        try {
            temp.outputStream().buffered().use { it.write(output) }
            require(temp.length() == output.size.toLong()) { "signed APK byte count drift" }
            require(temp.renameTo(outputApk)) { "could not publish signed APK" }
        } catch (error: Throwable) {
            temp.delete()
            throw error
        }

        val verified = verify(outputApk)
        require(verified.contentDigestSha256 == hex(contentDigest)) { "post-sign content digest verification drift" }
        require(verified.certificateSha256 == sha256(certificateBytes)) { "post-sign certificate verification drift" }

        return SignResult(
            outputBytes = outputApk.length(),
            apkSha256 = sha256(outputApk.readBytes()),
            certificateSha256 = verified.certificateSha256,
            publicKeySha256 = verified.publicKeySha256,
            contentDigestSha256 = verified.contentDigestSha256,
            signingBlockBytes = verified.signingBlockBytes
        )
    }

    fun verify(apk: File): VerifyResult {
        require(apk.isFile) { "signed APK is missing" }
        require(apk.length() in 1..(MAX_APK_BYTES + 1024 * 1024).toLong()) { "signed APK exceeds verifier bound" }
        val bytes = apk.readBytes()
        val layout = parseZipLayout(bytes)
        val block = findSigningBlock(bytes, layout.centralDirOffset)
            ?: error("APK Signature Scheme v2 signing block is missing")

        val v2 = Cursor(block.v2Value)
        val signerSequence = Cursor(v2.lengthPrefixed())
        v2.requireDone("v2 signer container")
        val signerBytes = signerSequence.lengthPrefixed()
        signerSequence.requireDone("v2 signer sequence")

        val signer = Cursor(signerBytes)
        val signedData = signer.lengthPrefixed()
        val signaturesBytes = signer.lengthPrefixed()
        val publicKeyBytes = signer.lengthPrefixed()
        signer.requireDone("v2 signer")

        val signatures = Cursor(signaturesBytes)
        val signatureRecord = Cursor(signatures.lengthPrefixed())
        val signatureAlgorithmId = signatureRecord.u32()
        require(signatureAlgorithmId == SIGNATURE_ALGORITHM_ID) { "unsupported v2 signature algorithm" }
        val signatureBytes = signatureRecord.lengthPrefixed()
        signatureRecord.requireDone("v2 signature record")
        signatures.requireDone("v2 signatures")

        val signed = Cursor(signedData)
        val digestsBytes = signed.lengthPrefixed()
        val certificatesBytes = signed.lengthPrefixed()
        val attributesBytes = signed.lengthPrefixed()
        require(attributesBytes.isEmpty()) { "unexpected v2 additional attributes" }
        signed.requireDone("v2 signed data")

        val digests = Cursor(digestsBytes)
        val digestRecord = Cursor(digests.lengthPrefixed())
        val digestAlgorithmId = digestRecord.u32()
        require(digestAlgorithmId == SIGNATURE_ALGORITHM_ID) { "v2 digest/signature algorithm mismatch" }
        val expectedDigest = digestRecord.lengthPrefixed()
        require(expectedDigest.size == 32) { "v2 SHA-256 digest length mismatch" }
        digestRecord.requireDone("v2 digest record")
        digests.requireDone("v2 digests")

        val certificates = Cursor(certificatesBytes)
        val certificateBytes = certificates.lengthPrefixed()
        certificates.requireDone("v2 certificates")
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificateBytes)) as X509Certificate
        certificate.checkValidity()
        require(certificate.publicKey.encoded.contentEquals(publicKeyBytes)) { "v2 certificate/public-key mismatch" }

        val signatureVerified = Signature.getInstance("SHA256withRSA").run {
            initVerify(certificate.publicKey)
            update(signedData)
            verify(signatureBytes)
        }
        require(signatureVerified) { "v2 signed-data RSA signature verification failed" }

        val actualDigest = computeContentDigest(
            bytes,
            beforeSigningStart = 0,
            beforeSigningEnd = block.start,
            centralDirStart = layout.centralDirOffset,
            eocdStart = layout.eocdOffset,
            signingBlockOffsetForDigest = block.start
        )
        require(actualDigest.contentEquals(expectedDigest)) { "v2 protected APK content digest mismatch" }

        return VerifyResult(
            apkSha256 = sha256(bytes),
            certificateSha256 = sha256(certificateBytes),
            publicKeySha256 = sha256(publicKeyBytes),
            contentDigestSha256 = hex(actualDigest),
            signingBlockBytes = block.size,
            signatureAlgorithmId = signatureAlgorithmId
        )
    }

    private fun keyEntry(): KeyStore.PrivateKeyEntry {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            val now = System.currentTimeMillis()
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setKeySize(KEY_SIZE)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=RiftBuild Local APK V2"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(now - 24L * 60L * 60L * 1000L))
                .setCertificateNotAfter(Date(now + 25L * 365L * 24L * 60L * 60L * 1000L))
                .setUserAuthenticationRequired(false)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }
        return store.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: error("RiftBuild APK signing key is unavailable")
    }

    private fun parseZipLayout(bytes: ByteArray): ZipLayout {
        require(bytes.size >= EOCD_MIN_BYTES) { "APK ZIP is truncated" }
        val floor = (bytes.size - EOCD_MIN_BYTES - EOCD_MAX_COMMENT).coerceAtLeast(0)
        var eocd = -1
        var cursor = bytes.size - EOCD_MIN_BYTES
        while (cursor >= floor) {
            if (readU32(bytes, cursor) == ZIP_EOCD) {
                val commentLength = readU16(bytes, cursor + 20)
                if (cursor + EOCD_MIN_BYTES + commentLength == bytes.size) {
                    eocd = cursor
                    break
                }
            }
            cursor -= 1
        }
        require(eocd >= 0) { "ZIP EOCD not found" }
        require(readU16(bytes, eocd + 4) == 0 && readU16(bytes, eocd + 6) == 0) { "multi-disk ZIP APK is unsupported" }
        require(readU16(bytes, eocd + 8) == readU16(bytes, eocd + 10)) { "ZIP central-directory entry count mismatch" }

        val centralDirSize = readU32(bytes, eocd + 12)
        val centralDirOffset = readU32(bytes, eocd + 16)
        require(centralDirSize != 0xffffffffL && centralDirOffset != 0xffffffffL) { "ZIP64 APK is unsupported" }
        require(centralDirOffset <= Int.MAX_VALUE && centralDirSize <= Int.MAX_VALUE) { "ZIP offsets exceed bootstrap bounds" }
        val cdOffset = centralDirOffset.toInt()
        val cdSize = centralDirSize.toInt()
        require(cdOffset >= 0 && cdSize >= 0 && cdOffset + cdSize == eocd) { "ZIP central-directory layout is malformed" }
        return ZipLayout(eocd, cdOffset, cdSize)
    }

    private fun findSigningBlock(bytes: ByteArray, centralDirOffset: Int): SigningBlock? {
        if (centralDirOffset < 24) return null
        val magicStart = centralDirOffset - APK_SIG_MAGIC.size
        if (!bytes.copyOfRange(magicStart, centralDirOffset).contentEquals(APK_SIG_MAGIC)) return null

        val trailingSizeOffset = magicStart - 8
        require(trailingSizeOffset >= 8) { "APK Signing Block is truncated" }
        val blockSizeField = readU64(bytes, trailingSizeOffset)
        require(blockSizeField in 24..Int.MAX_VALUE.toLong()) { "APK Signing Block size is invalid" }
        val blockStartLong = centralDirOffset.toLong() - (blockSizeField + 8L)
        require(blockStartLong in 0..Int.MAX_VALUE.toLong()) { "APK Signing Block start is invalid" }
        val blockStart = blockStartLong.toInt()
        require(readU64(bytes, blockStart) == blockSizeField) { "APK Signing Block size fields disagree" }

        var pairOffset = blockStart + 8
        val pairsEnd = trailingSizeOffset
        var v2: ByteArray? = null
        while (pairOffset < pairsEnd) {
            require(pairOffset + 8 <= pairsEnd) { "APK Signing Block pair length is truncated" }
            val pairSize = readU64(bytes, pairOffset)
            require(pairSize in 4..Int.MAX_VALUE.toLong()) { "APK Signing Block pair size is invalid" }
            val pairTotal = 8L + pairSize
            require(pairOffset.toLong() + pairTotal <= pairsEnd.toLong()) { "APK Signing Block pair exceeds container" }
            val id = readU32(bytes, pairOffset + 8).toInt()
            val valueStart = pairOffset + 12
            val valueEnd = (pairOffset.toLong() + pairTotal).toInt()
            if (id == V2_BLOCK_ID) {
                require(v2 == null) { "duplicate APK Signature Scheme v2 block" }
                v2 = bytes.copyOfRange(valueStart, valueEnd)
            }
            pairOffset = valueEnd
        }
        require(pairOffset == pairsEnd) { "APK Signing Block pair alignment mismatch" }
        return SigningBlock(blockStart, centralDirOffset - blockStart, v2 ?: error("APK Signing Block has no v2 block"))
    }

    private fun computeContentDigest(
        bytes: ByteArray,
        beforeSigningStart: Int,
        beforeSigningEnd: Int,
        centralDirStart: Int,
        eocdStart: Int,
        signingBlockOffsetForDigest: Int
    ): ByteArray {
        require(beforeSigningStart == 0) { "unexpected pre-signing section start" }
        require(beforeSigningEnd in 0..bytes.size) { "invalid pre-signing section end" }
        require(centralDirStart in beforeSigningEnd..eocdStart && eocdStart <= bytes.size) { "invalid central directory section" }

        val eocd = bytes.copyOfRange(eocdStart, bytes.size)
        require(eocd.size >= EOCD_MIN_BYTES) { "EOCD section is truncated" }
        writeU32(eocd, 16, signingBlockOffsetForDigest.toLong())

        val sections = listOf(
            bytes.copyOfRange(beforeSigningStart, beforeSigningEnd),
            bytes.copyOfRange(centralDirStart, eocdStart),
            eocd
        )
        val totalChunks = sections.sumOf { section ->
            if (section.isEmpty()) 0 else (section.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        }
        require(totalChunks > 0) { "APK has no digestible chunks" }

        val top = ByteArrayOutputStream(5 + (totalChunks * 32))
        top.write(0x5a)
        writeU32(top, totalChunks.toLong())

        for (section in sections) {
            var offset = 0
            while (offset < section.size) {
                val length = minOf(CHUNK_BYTES, section.size - offset)
                val chunkDigest = MessageDigest.getInstance("SHA-256")
                chunkDigest.update(0xa5.toByte())
                chunkDigest.update(u32Bytes(length.toLong()))
                chunkDigest.update(section, offset, length)
                top.write(chunkDigest.digest())
                offset += length
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(top.toByteArray())
    }

    private fun buildApkSigningBlock(v2Value: ByteArray): ByteArray {
        val resultSize = 8 + 8 + 4 + v2Value.size + 8 + APK_SIG_MAGIC.size
        val blockSize = resultSize - 8L
        return ByteArrayOutputStream(resultSize).apply {
            writeU64(this, blockSize)
            writeU64(this, 4L + v2Value.size)
            writeU32(this, V2_BLOCK_ID.toLong() and 0xffffffffL)
            write(v2Value)
            writeU64(this, blockSize)
            write(APK_SIG_MAGIC)
        }.toByteArray()
    }

    private fun encodeSequence(vararg elements: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            for (element in elements) {
                writeU32(this, element.size.toLong())
                write(element)
            }
        }.toByteArray()

    private fun encodeIntAndLengthPrefixedBytes(id: Int, value: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            writeU32(this, 8L + value.size)
            writeU32(this, id.toLong() and 0xffffffffL)
            writeU32(this, value.size.toLong())
            write(value)
        }.toByteArray()

    private fun u32Bytes(value: Long): ByteArray =
        ByteArrayOutputStream(4).apply { writeU32(this, value) }.toByteArray()

    private fun writeU32(output: ByteArrayOutputStream, value: Long) {
        require(value in 0..0xffffffffL) { "uint32 overflow" }
        for (index in 0 until 4) output.write(((value ushr (index * 8)) and 0xffL).toInt())
    }

    private fun writeU64(output: ByteArrayOutputStream, value: Long) {
        require(value >= 0) { "uint64 value must be non-negative" }
        for (index in 0 until 8) output.write(((value ushr (index * 8)) and 0xffL).toInt())
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
        require(value in 0..0xffffffffL && offset >= 0 && offset + 4 <= bytes.size) { "uint32 patch is out of range" }
        for (index in 0 until 4) bytes[offset + index] = ((value ushr (index * 8)) and 0xffL).toByte()
    }

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
