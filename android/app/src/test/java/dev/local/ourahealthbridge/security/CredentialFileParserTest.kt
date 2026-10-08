package dev.local.ourahealthbridge.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialFileParserTest {
    @Test
    fun acceptsExactHexWithHarmlessWhitespace() {
        val parsed = CredentialFileParser.parse(
            "0011223344556677\n8899AABBCCDDEEFF\r\n".encodeToByteArray(),
        )
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(),
                0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte(),
            ),
            parsed,
        )
        parsed.fill(0)
    }

    @Test
    fun rejectsOversizedMalformedAndNonAsciiInput() {
        assertThrows(IllegalArgumentException::class.java) {
            CredentialFileParser.parse(ByteArray(CredentialFileParser.MAX_FILE_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CredentialFileParser.parse("not-a-key".encodeToByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CredentialFileParser.parse(byteArrayOf(0xc3.toByte(), 0xa9.toByte()))
        }
    }

    @Test
    fun verifiedCredentialIsProtectedWhileUnverifiedImportCanBeCorrected() {
        assertFalse(mayImportCredential(existingCredentialVerified = true))
        assertTrue(mayImportCredential(existingCredentialVerified = false))
    }

    @Test
    fun rejectsInvalidHexEvenWhenLengthIsCorrect() {
        assertThrows(IllegalArgumentException::class.java) {
            CredentialFileParser.parse("00112233445566778899AABBCCDDEEFG".encodeToByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            CredentialFileParser.parse(" ".repeat(128).encodeToByteArray())
        }
    }

    @Test
    fun acceptsBoundarySizedFileAndLeavesCallerInputIntact() {
        val source = (" ".repeat(96) + "00112233445566778899AABBCCDDEEFF").encodeToByteArray()
        val before = source.copyOf()
        val key = CredentialFileParser.parse(source)
        assertArrayEquals(before, source)
        assertTrue(key.size == 16)
        key.fill(0)
        source.fill(0)
        before.fill(0)
    }
}
