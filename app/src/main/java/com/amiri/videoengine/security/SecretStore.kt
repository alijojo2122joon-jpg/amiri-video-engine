package com.amiri.videoengine.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys / tokens encrypted with an AES-256-GCM key that lives inside the
 * Android Keystore (hardware-backed on the realme GT3). The key never leaves
 * the secure hardware; only ciphertext is written to app-private storage.
 * Secrets are never logged and never written into project files.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("amiri_secrets", Context.MODE_PRIVATE)

    fun put(name: String, value: String?) {
        val clean = value?.trim().orEmpty()
        if (clean.isEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(1 + iv.size + encrypted.size)
        packed[0] = iv.size.toByte()
        System.arraycopy(iv, 0, packed, 1, iv.size)
        System.arraycopy(encrypted, 0, packed, 1 + iv.size, encrypted.size)
        prefs.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val packed = Base64.decode(stored, Base64.NO_WRAP)
            val ivLen = packed[0].toInt()
            val iv = packed.copyOfRange(1, 1 + ivLen)
            val data = packed.copyOfRange(1 + ivLen, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun has(name: String): Boolean = prefs.contains(name)

    /** Shows only the last 4 characters, for the settings screen. */
    fun masked(name: String): String? {
        val v = get(name) ?: return null
        return if (v.length <= 4) "••••" else "••••••" + v.takeLast(4)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ALIAS = "amiri_video_engine_secrets"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        const val HF_TOKEN = "hf_token"
        const val GOOGLE_KEY = "google_gemini_key"
        const val XAI_KEY = "xai_key"
    }
}
