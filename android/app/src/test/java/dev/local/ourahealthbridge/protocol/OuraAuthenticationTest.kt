package dev.local.ourahealthbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class OuraAuthenticationTest {
    @Test
    fun encryptNonce_matchesPinnedOpenOuraVector() {
        val key = "4431967d8bacc2659743142b68391d9a".hexBytes()
        val nonce = "0e2d6a0a08c99b4365f458e6e97382".hexBytes()

        val encrypted = OuraAuthentication.encryptNonce(key, nonce)

        assertEquals("a38a8772d3acb6db5c2b516dd56987c8", encrypted.hex())
    }
}

internal fun String.hexBytes(): ByteArray = ByteArray(length / 2) { index ->
    substring(index * 2, index * 2 + 2).toInt(16).toByte()
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toUByte().toInt()) }
