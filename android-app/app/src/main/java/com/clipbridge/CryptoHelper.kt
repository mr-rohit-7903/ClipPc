package com.clipbridge

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoHelper {

    /** Derive 32-byte AES key from shared secret (SHA-256). */
    fun deriveKey(secret: String): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
    }

    /** Derive room ID (double SHA-256 of secret). */
    fun deriveRoom(secret: String): String {
        val first = deriveKey(secret)
        val second = MessageDigest.getInstance("SHA-256").digest(first)
        return second.joinToString("") { "%02x".format(it) }
    }

    data class EncryptedPayload(val data: String, val iv: String)

    /** Encrypt plaintext using AES-256-GCM. Returns base64-encoded ciphertext+tag and IV. */
    fun encrypt(plaintext: String, key: ByteArray): EncryptedPayload {
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedPayload(
            data = Base64.encodeToString(ct, Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP)
        )
    }

    /** Decrypt AES-256-GCM payload. */
    fun decrypt(data: String, iv: String, key: ByteArray): String {
        val ivBytes = Base64.decode(iv, Base64.NO_WRAP)
        val ct = Base64.decode(data, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ivBytes))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }
}
