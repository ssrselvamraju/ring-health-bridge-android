package dev.local.ourahealthbridge.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the exportable Oura key using a non-exportable Android Keystore key. */
class RingKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun hasKey(): Boolean = preferences.contains(CIPHERTEXT) && preferences.contains(IV)

    fun importHex(hex: String) {
        val key = decodeHex(hex)
        try {
            import(key)
        } finally {
            key.fill(0)
        }
    }

    /** The caller retains ownership and must overwrite [key] after this returns. */
    fun import(key: ByteArray) {
        require(key.size == 16) { "Oura application key must be exactly 16 bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val ciphertext = cipher.doFinal(key)
        preferences.edit()
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putLong(IMPORTED_AT, System.currentTimeMillis())
            .apply()
        ciphertext.fill(0)
    }

    /** Caller must overwrite the returned bytes immediately after authentication. */
    fun load(): ByteArray {
        val iv = preferences.getString(IV, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: error("No Oura key IV is stored")
        val ciphertext = preferences.getString(CIPHERTEXT, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: error("No encrypted Oura key is stored")

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).also {
            check(it.size == 16) { "Stored Oura key has an invalid length" }
        }
    }

    fun clear() {
        preferences.edit().clear().apply()
        keyStore().deleteEntry(KEY_ALIAS)
    }

    fun importedAtMillis(): Long? = preferences.getLong(IMPORTED_AT, 0L).takeIf { it > 0L }

    private fun wrappingKey(): SecretKey {
        val store = keyStore()
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun decodeHex(value: String): ByteArray {
        val normalized = value.trim()
        require(normalized.length == 32 && normalized.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Oura application key must be exactly 32 hexadecimal characters"
        }
        return ByteArray(16) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "oura-ring-key-wrapping-v1"
        const val PREFERENCES = "ring-key-v1"
        const val IV = "iv"
        const val CIPHERTEXT = "ciphertext"
        const val IMPORTED_AT = "imported-at"
    }
}
