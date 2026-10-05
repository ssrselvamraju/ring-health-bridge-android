package dev.local.ourahealthbridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.health.connect.client.HealthConnectClient
import dev.local.ourahealthbridge.analysis.HealthConnectCandidatePreviewBuilder
import dev.local.ourahealthbridge.analysis.LatestLocalMetricsSelector
import dev.local.ourahealthbridge.analysis.LatestLocalMetricsStore
import dev.local.ourahealthbridge.bluetooth.HistorySyncResult
import dev.local.ourahealthbridge.bluetooth.RingConnectionSmokeTest
import dev.local.ourahealthbridge.healthconnect.DailyHealthConnectPublisher
import dev.local.ourahealthbridge.healthconnect.DailyPublicationSelector
import dev.local.ourahealthbridge.healthconnect.DailyPublicationStateStore
import dev.local.ourahealthbridge.healthconnect.ForegroundPublicationPlan
import dev.local.ourahealthbridge.healthconnect.ForegroundPublicationPlanner
import dev.local.ourahealthbridge.healthconnect.ForegroundRunReport
import dev.local.ourahealthbridge.healthconnect.ForegroundRunStateStore
import dev.local.ourahealthbridge.storage.HistoryStore
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** User-started foreground operation that survives activity recreation and screen-off. */
class ForegroundSyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runState: ForegroundRunStateStore
    private var ringConnection: RingConnectionSmokeTest? = null
    private var run: ServiceRun? = null
    private var active = false
    private var triggerSource = SOURCE_MANUAL

    override fun onCreate() {
        super.onCreate()
        runState = ForegroundRunStateStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (active) return START_NOT_STICKY
        triggerSource = intent?.getStringExtra(EXTRA_SOURCE) ?: SOURCE_MANUAL
        startForeground(NOTIFICATION_ID, notification("Preparing ring sync"))
        active = true
        runState.markAttempt(
            System.currentTimeMillis(),
            triggerSource,
            PhoneBatteryReader.read(this),
        )
        progress("Starting ring connection before decoding local history...")
        scope.launch { begin() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ringConnection?.close()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun begin() {
        val device = associatedBondedDevice() ?: run {
            fail("associated and bonded ring unavailable; nothing written")
            return
        }
        run = ServiceRun(device, ZoneId.systemDefault())
        startHistorySession()
    }

    private fun startHistorySession() {
        val current = run ?: return
        current.syncSessions++
        progress("Ring sync session ${current.syncSessions} starting...")
        ringConnection?.close()
        ringConnection = RingConnectionSmokeTest(
            context = this,
            onStatus = { progress("Session ${current.syncSessions}: $it") },
            onHistoryFinished = ::handleHistoryResult,
            onHistoryBattery = ::handleBatteryPreflight,
        ).also {
            it.startHistorySync(
                current.device,
                maxBatches = BATCHES_PER_CONNECTION,
                timeoutMs = HISTORY_TIMEOUT_MS,
                checkBattery = true,
                minimumBatteryPercent = if (triggerSource == SOURCE_MANUAL) {
                    null
                } else {
                    RingBatteryPolicy.MINIMUM_AUTOMATIC_SYNC_PERCENT
                },
            )
        }
    }

    private fun handleBatteryPreflight(percent: Int) {
        val current = run ?: return
        if (current.startingBatteryPercent == null) current.startingBatteryPercent = percent
        RingBatteryAlertManager(this).onBatteryRead(percent)
    }

    private fun handleHistoryResult(result: HistorySyncResult) {
        val current = run ?: return
        current.receivedEvents += result.received
        current.addedEvents += result.inserted
        result.storedTotal?.let { current.storedEvents = it }
        if (current.startingBatteryPercent == null) current.startingBatteryPercent = result.batteryPercent
        if (!result.passed) {
            if (result.lowBatteryBlocked) {
                finish(
                    current.report(
                        passed = false,
                        detail = "automatic sync refused below " +
                            "${RingBatteryPolicy.MINIMUM_AUTOMATIC_SYNC_PERCENT}% ring battery",
                        skippedLowBattery = true,
                    ),
                )
                return
            }
            if (AutomaticConnectionFailurePolicy.shouldDefer(triggerSource, result.connectionStatus)) {
                val presenceActive = BackgroundScheduleStateStore(this).isPresenceObservationActive()
                if (AutomaticConnectionFailurePolicy.shouldQueueFallback(triggerSource, presenceActive)) {
                    BackgroundSyncScheduler(this).enqueueUnavailableRecovery()
                }
                val recovery = if (presenceActive) {
                    "ring unavailable; waiting for return-to-range or periodic retry"
                } else {
                    "ring unavailable; waiting for return-to-range or delayed retry"
                }
                finish(
                    current.report(
                        passed = false,
                        detail = recovery,
                        deferredUnavailable = true,
                    ),
                )
                return
            }
            val retryLimit = AutomaticConnectionFailurePolicy.maxImmediateRetries(
                triggerSource,
                result.connectionStatus,
                MAX_CONNECTION_RETRIES,
            )
            if (result.retryableConnectionFailure &&
                current.consecutiveConnectionFailures < retryLimit
            ) {
                current.consecutiveConnectionFailures++
                current.connectionRetries++
                progress(
                    "Transient Bluetooth failure; retry " +
                        "${current.consecutiveConnectionFailures}/$retryLimit after cooldown...",
                )
                mainExecutor.executeDelayed(CONNECTION_RETRY_DELAY_MS, ::startHistorySession)
            } else {
                fail("ring sync failed; ${result.message}")
            }
            return
        }
        current.consecutiveConnectionFailures = 0
        if (result.moreRemains) {
            if (current.syncSessions >= MAX_SYNC_SESSIONS) {
                fail("history remains after the safety limit; nothing published")
            } else {
                progress("More ring history remains; continuing after cooldown...")
                mainExecutor.executeDelayed(RECONNECT_DELAY_MS, ::startHistorySession)
            }
            return
        }
        progress("History drained; rebuilding candidates and affected dates...")
        scope.launch { buildAndPublish() }
    }

    private suspend fun buildAndPublish() {
        val current = run ?: return
        val after = runCatching {
            withContext(Dispatchers.Default) {
                HistoryStore(this@ForegroundSyncService).use { store ->
                    current.storedEvents = store.stats().eventCount
                    HealthConnectCandidatePreviewBuilder.build(store.loadRawEvents(), current.zoneId)
                }
            }
        }.getOrElse {
            fail("after-sync candidate build failed (${it.javaClass.simpleName})")
            return
        }
        LatestLocalMetricsStore(this).save(LatestLocalMetricsSelector.select(after))
        val now = System.currentTimeMillis()
        val publicationState = DailyPublicationStateStore(this)
        val plan = ForegroundPublicationPlanner.planAgainstPublicationState(
            after, current.zoneId, now, publicationState::state,
        )
        current.affectedDates = plan.affectedDateCount
        current.deferredRecentSleepRecords = plan.deferredRecentSleepRecords
        if (plan.dates.isEmpty()) {
            finish(current.report(true, plan, "exact no-change reconciliation passed"))
            return
        }
        progress("Publishing and exactly verifying ${plan.affectedDateCount} affected date(s)...")
        val publisher = DailyHealthConnectPublisher(
            HealthConnectClient.getOrCreate(this), packageName,
        )
        val failure = runCatching {
            plan.dates.forEach { affected ->
                val selection = affected.current
                val oldIds = publicationState.clientIds(selection.localDate)
                if (affected.publishHrHrv) {
                    val verified = publisher.publishHrHrv(selection)
                    check(verified.passed) {
                        "HR/HRV exact read-back mismatch: ${verified.mismatchCategories.sorted().joinToString(", ")}"
                    }
                    current.obsoleteRecordsDeleted += publisher.deleteObsolete(
                        affected.previous, selection, true, false, oldIds,
                    )
                    publicationState.setHrHrvVerified(selection, true)
                    current.heartRateRecords += selection.heartRate.size
                    current.heartRateSamples += selection.heartRateSamples
                    current.hrvRecords += selection.hrv.size
                }
                if (affected.publishSleep) {
                    val verified = publisher.publishSleep(selection)
                    check(verified.passed) {
                        "sleep exact read-back mismatch: ${verified.mismatchCategories.sorted().joinToString(", ")}"
                    }
                    current.obsoleteRecordsDeleted += publisher.deleteObsolete(
                        affected.previous, selection, false, true, oldIds,
                    )
                    publicationState.setSleepVerified(selection, true)
                    current.sleepRecords += selection.sleep.size
                }
            }
        }.exceptionOrNull()
        if (failure != null) {
            fail("publication/verification failed (${publicationFailureCategory(failure)}); retry is safe")
            return
        }
        finish(current.report(true, plan, "exact read-back verified"))
    }

    private fun fail(detail: String) {
        val report = run?.report(false, detail = detail) ?: emptyFailure(detail)
        finish(report)
    }

    private fun finish(report: ForegroundRunReport) {
        runState.markFinished(System.currentTimeMillis(), report, PhoneBatteryReader.read(this))
        if (report.passed) BackgroundSyncScheduler(this).cancelUnavailableRecovery()
        progress(report.statusText(), finished = true)
        active = false
        ringConnection?.close()
        ringConnection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun progress(status: String, finished: Boolean = false) {
        if (!finished) runState.markProgress(status)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification(if (finished) "Sync finished" else "Syncing ring"),
            )
        }
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Ring Health Bridge")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ring synchronization", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun associatedBondedDevice(): BluetoothDevice? {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val association = getSystemService(CompanionDeviceManager::class.java)
            .myAssociations.maxByOrNull(AssociationInfo::getId) ?: return null
        val device = association.associatedDevice?.bleDevice?.device ?: association.deviceMacAddress
            ?.toString()?.uppercase(Locale.ROOT)?.takeIf(BluetoothAdapter::checkBluetoothAddress)
            ?.let { getSystemService(android.bluetooth.BluetoothManager::class.java).adapter.getRemoteDevice(it) }
        return device?.takeIf { it.bondState == BluetoothDevice.BOND_BONDED }
    }

    private fun java.util.concurrent.Executor.executeDelayed(delayMillis: Long, block: () -> Unit) {
        android.os.Handler(mainLooper).postDelayed({ execute(block) }, delayMillis)
    }

    private fun emptyFailure(detail: String) = ForegroundRunReport(
        false, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, detail = detail,
    )

    companion object {
        const val ACTION_STATE_CHANGED = "dev.local.ourahealthbridge.FOREGROUND_STATE_CHANGED"

        fun start(context: Context, source: String = SOURCE_MANUAL) {
            context.startForegroundService(
                Intent(context, ForegroundSyncService::class.java).putExtra(EXTRA_SOURCE, source),
            )
        }

        const val SOURCE_MANUAL = "manual"
        const val SOURCE_TEST = "test"
        const val SOURCE_PERIODIC = "periodic"
        const val SOURCE_FOREGROUND_OPEN = "foreground-open"
        const val SOURCE_PRESENCE = "presence"
        const val SOURCE_RECOVERY = "recovery"

        private const val CHANNEL_ID = "ring-sync"
        private const val NOTIFICATION_ID = 100
        private const val BATCHES_PER_CONNECTION = 80
        private const val HISTORY_TIMEOUT_MS = 5L * 60L * 1_000L
        private const val MAX_SYNC_SESSIONS = 6
        private const val MAX_CONNECTION_RETRIES = 3
        private const val CONNECTION_RETRY_DELAY_MS = 8_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val EXTRA_SOURCE = "source"
    }
}

internal fun publicationFailureCategory(failure: Throwable): String {
    val message = failure.message
    return if (failure is IllegalStateException &&
        message != null && message.contains("exact read-back mismatch")
    ) {
        "${failure.javaClass.simpleName}: $message"
    } else {
        failure.javaClass.simpleName
    }
}

internal object AutomaticConnectionFailurePolicy {
    const val GATT_CONNECTION_TIMEOUT = 147

    fun shouldDefer(source: String, connectionStatus: Int?): Boolean =
        source != ForegroundSyncService.SOURCE_MANUAL && connectionStatus == GATT_CONNECTION_TIMEOUT

    fun shouldQueueFallback(source: String, presenceObservationActive: Boolean): Boolean =
        !presenceObservationActive && source != ForegroundSyncService.SOURCE_RECOVERY

    fun maxImmediateRetries(source: String, connectionStatus: Int?, defaultLimit: Int): Int =
        if (source == ForegroundSyncService.SOURCE_MANUAL && connectionStatus == GATT_CONNECTION_TIMEOUT) {
            1
        } else {
            defaultLimit
        }
}

private data class ServiceRun(
    val device: BluetoothDevice,
    val zoneId: ZoneId,
    var syncSessions: Int = 0,
    var connectionRetries: Int = 0,
    var consecutiveConnectionFailures: Int = 0,
    var receivedEvents: Int = 0,
    var addedEvents: Int = 0,
    var storedEvents: Long? = null,
    var affectedDates: Int = 0,
    var heartRateRecords: Int = 0,
    var heartRateSamples: Int = 0,
    var hrvRecords: Int = 0,
    var sleepRecords: Int = 0,
    var obsoleteRecordsDeleted: Int = 0,
    var deferredRecentSleepRecords: Int = 0,
    var startingBatteryPercent: Int? = null,
) {
    fun report(
        passed: Boolean,
        plan: ForegroundPublicationPlan? = null,
        detail: String? = null,
        skippedLowBattery: Boolean = false,
        deferredUnavailable: Boolean = false,
    ) = ForegroundRunReport(
        passed, syncSessions, receivedEvents, addedEvents, storedEvents,
        plan?.affectedDateCount ?: affectedDates,
        heartRateRecords, heartRateSamples, hrvRecords, sleepRecords,
        obsoleteRecordsDeleted,
        plan?.deferredRecentSleepRecords ?: deferredRecentSleepRecords,
        connectionRetries = connectionRetries,
        startingBatteryPercent = startingBatteryPercent,
        skippedLowBattery = skippedLowBattery,
        deferredUnavailable = deferredUnavailable,
        detail = detail,
    )
}

private class RingBatteryAlertManager(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    fun onBatteryRead(percent: Int) {
        val band = RingBatteryPolicy.alertBand(percent)
        val previous = preferences.getInt(LAST_ALERT_BAND, 0)
        if (band == 0) {
            preferences.edit().putInt(LAST_ALERT_BAND, 0).apply()
            return
        }
        if (band <= previous) return
        preferences.edit().putInt(LAST_ALERT_BAND, band).apply()
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        notifications.createNotificationChannel(
            NotificationChannel(BATTERY_CHANNEL, "Ring battery alerts", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val title = if (band == 2) "Oura ring battery below 10%" else "Oura ring battery below 20%"
        val message = "Ring battery is $percent%. Charge it when convenient."
        val openApp = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notifications.notify(
            BATTERY_NOTIFICATION_ID,
            Notification.Builder(context, BATTERY_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val PREFERENCES = "ring-battery-alerts-v1"
        const val LAST_ALERT_BAND = "last-alert-band"
        const val BATTERY_CHANNEL = "ring-battery-alerts"
        const val BATTERY_NOTIFICATION_ID = 101
    }
}

internal object RingBatteryPolicy {
    const val MINIMUM_AUTOMATIC_SYNC_PERCENT = 5
    fun blocksAutomaticSync(percent: Int): Boolean = percent < MINIMUM_AUTOMATIC_SYNC_PERCENT
    fun alertBand(percent: Int): Int = when {
        percent < 10 -> 2
        percent < 20 -> 1
        else -> 0
    }
}
