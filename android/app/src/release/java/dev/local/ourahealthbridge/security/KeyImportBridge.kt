package dev.local.ourahealthbridge.security

import android.content.Context

/** Release builds never accept developer/ADB-staged key material. */
object KeyImportBridge {
    fun importStagedKey(context: Context): KeyImportState = KeyImportState.DISABLED
}
