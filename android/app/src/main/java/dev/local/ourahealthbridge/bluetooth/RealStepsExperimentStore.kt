package dev.local.ourahealthbridge.bluetooth

import android.content.Context
import dev.local.ourahealthbridge.protocol.OuraResponses

data class RealStepsOriginalState(
    val mode: Int,
    val subscription: Int,
)

/** Restart-safe record of the state that must be restored after the experiment. */
class RealStepsExperimentStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun captureOriginal(status: OuraResponses.FeatureStatus): Boolean = preferences.edit()
        .putInt(ORIGINAL_MODE, status.mode)
        .putInt(ORIGINAL_SUBSCRIPTION, status.subscription)
        .putBoolean(RESTORE_REQUIRED, true)
        .commit()

    fun original(): RealStepsOriginalState? {
        if (!preferences.getBoolean(RESTORE_REQUIRED, false)) return null
        return RealStepsOriginalState(
            mode = preferences.getInt(ORIGINAL_MODE, 0),
            subscription = preferences.getInt(ORIGINAL_SUBSCRIPTION, 0),
        )
    }

    fun markRestored(): Boolean = preferences.edit()
        .remove(ORIGINAL_MODE)
        .remove(ORIGINAL_SUBSCRIPTION)
        .putBoolean(RESTORE_REQUIRED, false)
        .commit()

    fun statusText(): String = original()?.let {
        "REAL_STEPS experiment active or incomplete; rollback target mode ${it.mode}, subscription ${it.subscription}."
    } ?: "REAL_STEPS experiment not enabled by this app."

    private companion object {
        const val PREFERENCES = "real-steps-experiment-v1"
        const val ORIGINAL_MODE = "original-mode"
        const val ORIGINAL_SUBSCRIPTION = "original-subscription"
        const val RESTORE_REQUIRED = "restore-required"
    }
}
