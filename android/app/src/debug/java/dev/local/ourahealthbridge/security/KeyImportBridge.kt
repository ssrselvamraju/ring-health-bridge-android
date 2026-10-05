package dev.local.ourahealthbridge.security

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

/** Imports a USB/ADB-staged key only in debuggable builds. */
object KeyImportBridge {
    const val STAGING_FILENAME = ".oura-key-import.hex"

    fun importStagedKey(context: Context): KeyImportState {
        val staged = File(context.filesDir, STAGING_FILENAME)
        if (!staged.isFile) return KeyImportState.NOT_PRESENT

        var source = byteArrayOf()
        var key = byteArrayOf()
        return try {
            source = staged.readBytes()
            key = decodeAsciiHex(source)
            RingKeyStore(context).import(key)
            KeyImportState.IMPORTED
        } catch (_: Exception) {
            KeyImportState.REJECTED
        } finally {
            source.fill(0)
            key.fill(0)
            eraseAndDelete(staged)
        }
    }

    private fun decodeAsciiHex(source: ByteArray): ByteArray {
        val hex = source.filterNot { it.toInt().toChar().isWhitespace() }
        require(hex.size == 32) { "Expected 32 hexadecimal bytes" }
        return ByteArray(16) { index ->
            val high = nibble(hex[index * 2])
            val low = nibble(hex[index * 2 + 1])
            ((high shl 4) or low).toByte()
        }
    }

    private fun nibble(value: Byte): Int = when (val character = value.toInt().toChar()) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        in 'A'..'F' -> character - 'A' + 10
        else -> throw IllegalArgumentException("Non-hexadecimal key byte")
    }

    private fun eraseAndDelete(file: File) {
        runCatching {
            RandomAccessFile(file, "rw").use { handle ->
                val remaining = handle.length()
                handle.seek(0)
                val zeros = ByteArray(minOf(remaining, 4096L).toInt())
                var written = 0L
                while (written < remaining) {
                    val count = minOf(zeros.size.toLong(), remaining - written).toInt()
                    handle.write(zeros, 0, count)
                    written += count
                }
                handle.fd.sync()
            }
        }
        file.delete()
    }
}
