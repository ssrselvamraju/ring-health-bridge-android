package dev.local.ourahealthbridge

import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import dev.local.ourahealthbridge.healthconnect.ForegroundRunOutcome
import dev.local.ourahealthbridge.healthconnect.ForegroundRunStateStore

/** Registers Android's system-owned BLE observation for the already-associated ring. */
internal class RingPresenceObservation(context: Context) {
    private val context = context.applicationContext
    private val manager = context.getSystemService(CompanionDeviceManager::class.java)
    private val scheduleState = BackgroundScheduleStateStore(context)

    fun ensureIfEnabled() {
        if (!scheduleState.isEnabled()) return
        val association = newestAssociation() ?: run {
            scheduleState.markPresenceObservation(System.currentTimeMillis(), false, "no ring association")
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 36) {
                manager.startObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder()
                        .setAssociationId(association.id)
                        .build(),
                )
            } else {
                @Suppress("DEPRECATION")
                val address = association.deviceMacAddress?.toString()
                    ?: error("associated address unavailable")
                @Suppress("DEPRECATION")
                manager.startObservingDevicePresence(address)
            }
        }.onSuccess {
            scheduleState.markPresenceObservation(System.currentTimeMillis(), true)
        }.onFailure {
            scheduleState.markPresenceObservation(
                System.currentTimeMillis(), false, it.javaClass.simpleName,
            )
        }
    }

    fun stop() {
        val association = newestAssociation() ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 36) {
                manager.stopObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder()
                        .setAssociationId(association.id)
                        .build(),
                )
            } else {
                @Suppress("DEPRECATION")
                val address = association.deviceMacAddress?.toString() ?: return@runCatching
                @Suppress("DEPRECATION")
                manager.stopObservingDevicePresence(address)
            }
        }
    }

    private fun newestAssociation(): AssociationInfo? = manager.myAssociations.maxByOrNull(AssociationInfo::getId)
}

/** Receives a system callback when the associated ring advertises in BLE range. */
class RingPresenceService : CompanionDeviceService() {
    @RequiresApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event != DevicePresenceEvent.EVENT_BLE_APPEARED) return
        val association = getSystemService(CompanionDeviceManager::class.java)
            .myAssociations.maxByOrNull(AssociationInfo::getId) ?: return
        if (event.associationId != association.id) return
        PresenceSyncTrigger(this).onAppeared()
    }

    @Deprecated("Legacy callback used on Android 14 and 15")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        val newest = getSystemService(CompanionDeviceManager::class.java)
            .myAssociations.maxByOrNull(AssociationInfo::getId) ?: return
        if (associationInfo.id != newest.id) return
        PresenceSyncTrigger(this).onAppeared()
    }
}

internal class PresenceSyncTrigger(context: Context) {
    private val context = context.applicationContext
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun onAppeared(now: Long = System.currentTimeMillis()) {
        val scheduleState = BackgroundScheduleStateStore(context)
        if (!scheduleState.isEnabled()) return
        val lastDispatch = preferences.getLong(LAST_DISPATCH, 0L).takeIf { it > 0 }
        if (lastDispatch != null && now - lastDispatch in 0 until PRESENCE_COOLDOWN_MILLIS) {
            scheduleState.markCoalesced(now, "presence", "a presence callback dispatched less than 10 minutes ago")
            return
        }
        val runState = ForegroundRunStateStore(context)
        val snapshot = runState.uiSnapshot(now)
        val reason = PresenceSyncDispatchPolicy.coalescingReason(
            now,
            runState.freshnessState(),
            snapshot.outcome,
            snapshot.source,
        )
        if (reason != null) {
            scheduleState.markCoalesced(now, "presence", reason)
            return
        }
        preferences.edit().putLong(LAST_DISPATCH, now).apply()
        runCatching {
            ForegroundSyncService.start(context, ForegroundSyncService.SOURCE_PRESENCE)
        }.onFailure {
            preferences.edit().remove(LAST_DISPATCH).apply()
        }
    }

    private companion object {
        const val PREFERENCES = "ring-presence-sync-v1"
        const val LAST_DISPATCH = "last-dispatch"
        const val PRESENCE_COOLDOWN_MILLIS = 10L * 60L * 1_000L
    }
}

internal object PresenceSyncDispatchPolicy {
    const val RECENT_PRESENCE_ATTEMPT_MILLIS = 3L * 60L * 60L * 1_000L

    fun coalescingReason(
        now: Long,
        freshness: dev.local.ourahealthbridge.healthconnect.ForegroundRunFreshnessState,
        outcome: ForegroundRunOutcome,
        source: String?,
    ): String? {
        AutomaticSyncDispatchPolicy.coalescingReason(now, freshness)?.let { return it }
        val finished = freshness.lastFinishedMillis ?: return null
        val age = now - finished
        if (source == ForegroundSyncService.SOURCE_PRESENCE &&
            age in 0 until RECENT_PRESENCE_ATTEMPT_MILLIS
        ) {
            return "a presence-triggered attempt finished less than 3 hours ago"
        }
        if (source != ForegroundSyncService.SOURCE_MANUAL &&
            (outcome == ForegroundRunOutcome.FAILED || outcome == ForegroundRunOutcome.SKIPPED) &&
            age in 0 until RECENT_PRESENCE_ATTEMPT_MILLIS
        ) {
            return "a recent automatic failure is waiting for periodic or manual retry"
        }
        return null
    }
}
