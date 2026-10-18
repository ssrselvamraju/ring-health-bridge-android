package dev.local.ourahealthbridge.security

import android.content.Context

object CredentialFileParser {
    const val MAX_FILE_BYTES = 128

    /** Caller owns the returned bytes and must overwrite them after import. */
    fun parse(source: ByteArray): ByteArray {
        require(source.size <= MAX_FILE_BYTES) { "Credential file is too large" }
        require(source.all { byte ->
            val value = byte.toInt() and 0xff
            value == 0x09 || value == 0x0a || value == 0x0d || value == 0x20 || value in 0x21..0x7e
        }) { "Credential file must contain ASCII text only" }
        val compact = ByteArray(MAX_FILE_BYTES)
        val key = ByteArray(16)
        try {
            var count = 0
            for (byte in source) {
                if (!byte.toInt().toChar().isWhitespace()) compact[count++] = byte
            }
            require(count == 32) { "Credential must contain exactly 32 hexadecimal characters" }
            for (index in key.indices) {
                val high = compact[index * 2].hexNibble()
                val low = compact[index * 2 + 1].hexNibble()
                key[index] = ((high shl 4) or low).toByte()
            }
            return key
        } catch (failure: Throwable) {
            key.fill(0)
            throw failure
        } finally {
            compact.fill(0)
        }
    }

    private fun Byte.hexNibble(): Int = when (val character = toInt().toChar()) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        in 'A'..'F' -> character - 'A' + 10
        else -> throw IllegalArgumentException("Credential contains a non-hexadecimal character")
    }
}

/** A known-working credential must never be overwritten by the first-run importer. */
fun mayImportCredential(existingCredentialVerified: Boolean): Boolean = !existingCredentialVerified

class RingAuthenticationStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun markVerified(nowUnixMillis: Long = System.currentTimeMillis()) {
        preferences.edit().putLong(LAST_VERIFIED, nowUnixMillis).apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    fun lastVerifiedMillis(): Long? = preferences.getLong(LAST_VERIFIED, 0L).takeIf { it > 0L }

    private companion object {
        const val PREFERENCES = "ring-authentication-state-v1"
        const val LAST_VERIFIED = "last-verified"
    }
}
