package dev.local.ourahealthbridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.health.connect.client.HealthConnectClient
import dev.local.ourahealthbridge.healthconnect.DailyHealthConnectPublisher
import dev.local.ourahealthbridge.healthconnect.ForegroundRunStateStore
import dev.local.ourahealthbridge.security.RingKeyStore
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal object ForegroundOpenSyncPolicy {
    const val FRESHNESS_MILLIS = 30L * 60L * 1_000L
    const val ATTEMPT_COOLDOWN_MILLIS = 15L * 60L * 1_000L

    fun shouldStart(
        now: Long,
        lastSuccess: Long?,
        lastAttempt: Long?,
        lastOpenDispatch: Long?,
    ): Boolean {
        if (lastSuccess != null && now - lastSuccess < FRESHNESS_MILLIS) return false
        if (lastAttempt != null && now - lastAttempt < ATTEMPT_COOLDOWN_MILLIS) return false
        if (lastOpenDispatch != null && now - lastOpenDispatch < ATTEMPT_COOLDOWN_MILLIS) return false
        return true
    }
}

/** Starts an implicit sync only when a real foreground entry finds stale local data. */
internal class ForegroundOpenSyncCoordinator(context: Context) {
    private val context = context.applicationContext
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun maybeStart(scope: CoroutineScope, now: Long = System.currentTimeMillis()) {
        RingPresenceObservation(context).ensureIfEnabled()
        val runState = ForegroundRunStateStore(context).freshnessState()
        val lastDispatch = preferences.getLong(LAST_DISPATCH, 0L).takeIf { it > 0 }
        if (!ForegroundOpenSyncPolicy.shouldStart(
                now,
                runState.lastSuccessMillis,
                runState.lastAttemptMillis,
                lastDispatch,
            )
        ) return
        if (!isRingReady() || HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return

        scope.launch {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrNull()
                ?: return@launch
            if (!granted.containsAll(DailyHealthConnectPublisher.ALL_PERMISSIONS)) return@launch

            val refreshed = ForegroundRunStateStore(context).freshnessState()
            val dispatch = preferences.getLong(LAST_DISPATCH, 0L).takeIf { it > 0 }
            val dispatchNow = System.currentTimeMillis()
            if (!ForegroundOpenSyncPolicy.shouldStart(
                    dispatchNow,
                    refreshed.lastSuccessMillis,
                    refreshed.lastAttemptMillis,
                    dispatch,
                )
            ) return@launch

            preferences.edit().putLong(LAST_DISPATCH, dispatchNow).apply()
            runCatching {
                ForegroundSyncService.start(context, ForegroundSyncService.SOURCE_FOREGROUND_OPEN)
            }.onFailure {
                preferences.edit().remove(LAST_DISPATCH).apply()
            }
        }
    }

    private fun isRingReady(): Boolean {
        if (!RingKeyStore(context).hasKey()) return false
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val association = context.getSystemService(CompanionDeviceManager::class.java)
            .myAssociations.maxByOrNull(AssociationInfo::getId) ?: return false
        val device = association.associatedDevice?.bleDevice?.device ?: association.deviceMacAddress
            ?.toString()?.uppercase(Locale.ROOT)?.takeIf(BluetoothAdapter::checkBluetoothAddress)
            ?.let { context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(it) }
        return device?.bondState == BluetoothDevice.BOND_BONDED
    }

    private companion object {
        const val PREFERENCES = "foreground-open-sync-v1"
        const val LAST_DISPATCH = "last-dispatch"
    }
}
