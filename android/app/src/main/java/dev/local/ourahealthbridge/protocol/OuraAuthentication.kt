package dev.local.ourahealthbridge.protocol

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object OuraAuthentication {
    /** Mirrors open_oura AES-128/ECB/PKCS7 (Java names this PKCS5Padding). */
    fun encryptNonce(applicationKey: ByteArray, nonce: ByteArray): ByteArray {
        require(applicationKey.size == 16) { "Oura application key must be 16 bytes" }
        require(nonce.size in 1..15) { "Oura nonce must fit one padded AES block" }

        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(applicationKey, "AES"))
        return cipher.doFinal(nonce)
    }
}
