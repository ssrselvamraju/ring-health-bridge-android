package dev.local.ourahealthbridge.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.local.ourahealthbridge.protocol.OuraAuthentication
import dev.local.ourahealthbridge.protocol.EventBatchAccumulator
import dev.local.ourahealthbridge.protocol.OuraGatt
import dev.local.ourahealthbridge.protocol.OuraPacket
import dev.local.ourahealthbridge.protocol.OuraRequests
import dev.local.ourahealthbridge.protocol.OuraResponses
import dev.local.ourahealthbridge.security.RingKeyStore
import dev.local.ourahealthbridge.storage.HistoryStore
import java.util.UUID

data class HistorySyncResult(
    val passed: Boolean,
    val received: Int,
    val inserted: Int,
    val storedTotal: Long?,
    val moreRemains: Boolean,
    val retryableConnectionFailure: Boolean,
    val message: String,
    val batteryPercent: Int? = null,
    val lowBatteryBlocked: Boolean = false,
    val connectionStatus: Int? = null,
)

data class RingFeatureStatusResult(
    val passed: Boolean,
    val featureStatus: OuraResponses.FeatureStatus?,
    val message: String,
)

/** One-shot, read-only validation of BLE transport, app authentication, and battery parsing. */
@SuppressLint("MissingPermission")
class RingConnectionSmokeTest(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onHistoryFinished: ((HistorySyncResult) -> Unit)? = null,
    private val onHistoryBattery: ((Int) -> Unit)? = null,
    private val onFeatureStatusFinished: ((RingFeatureStatusResult) -> Unit)? = null,
) : BluetoothGattCallback() {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var inFlightWrite: PendingWrite? = null
    private var pendingWrite: PendingWrite? = null
    private var stage = Stage.IDLE
    private var active = false
    private var operation = Operation.BATTERY_TEST
    private var historyStore: HistoryStore? = null
    private var historyBatch: EventBatchAccumulator? = null
    private var historyCursor = 0u
    private var historyBatches = 0
    private var historyReceived = 0
    private var historyInserted = 0
    private var historyMoreRemains = false
    private var historyBatchLimit = MAX_HISTORY_BATCHES
    private var historyTimeoutMs = HISTORY_TIMEOUT_MS
    private var checkHistoryBattery = false
    private var minimumHistoryBatteryPercent: Int? = null
    private var historyBatteryPercent: Int? = null
    private var historyLowBatteryBlocked = false
    private var sessionGeneration = 0L
    private var requestedFeatureId = REAL_STEPS_FEATURE_ID
    private var receivedFeatureStatus: OuraResponses.FeatureStatus? = null
    private var originalFeatureState: RealStepsOriginalState? = null
    private var rollbackReason: String? = null
    private var rollbackSubscriptionWarning: String? = null
    private var experimentBatteryPercent: Int? = null

    fun start(device: BluetoothDevice) {
        close()
        begin(device, Operation.BATTERY_TEST, BATTERY_TIMEOUT_MS)
    }

    fun startHistorySync(
        device: BluetoothDevice,
        maxBatches: Int = MAX_HISTORY_BATCHES,
        timeoutMs: Long = HISTORY_TIMEOUT_MS,
        checkBattery: Boolean = false,
        minimumBatteryPercent: Int? = null,
    ) {
        close()
        require(maxBatches > 0)
        historyBatchLimit = maxBatches
        historyTimeoutMs = timeoutMs
        checkHistoryBattery = checkBattery
        minimumHistoryBatteryPercent = minimumBatteryPercent
        historyBatteryPercent = null
        historyLowBatteryBlocked = false
        val store = runCatching { HistoryStore(applicationContext).also { it.cursor() } }.getOrElse {
            onStatus("Could not open app-private history storage")
            return
        }
        historyStore = store
        historyCursor = store.cursor()
        historyBatches = 0
        historyReceived = 0
        historyInserted = 0
        historyMoreRemains = false
        begin(device, Operation.HISTORY_SYNC, historyTimeoutMs)
    }

    fun startFeatureStatus(device: BluetoothDevice, featureId: Int = REAL_STEPS_FEATURE_ID) {
        close()
        require(featureId in 0..255)
        requestedFeatureId = featureId
        receivedFeatureStatus = null
        begin(device, Operation.FEATURE_STATUS, FEATURE_STATUS_TIMEOUT_MS)
    }

    fun startRealStepsEnable(device: BluetoothDevice) {
        close()
        val store = RealStepsExperimentStore(applicationContext)
        if (store.original() != null) {
            onStatus("A saved REAL_STEPS rollback target already exists. Roll back before enabling again; nothing changed.")
            return
        }
        requestedFeatureId = REAL_STEPS_FEATURE_ID
        receivedFeatureStatus = null
        originalFeatureState = null
        rollbackReason = null
        rollbackSubscriptionWarning = null
        experimentBatteryPercent = null
        begin(device, Operation.FEATURE_ENABLE, FEATURE_MUTATION_TIMEOUT_MS)
    }

    fun startRealStepsRollback(device: BluetoothDevice) {
        close()
        val original = RealStepsExperimentStore(applicationContext).original()
        if (original == null) {
            onStatus("No saved REAL_STEPS rollback target exists; nothing changed.")
            return
        }
        requestedFeatureId = REAL_STEPS_FEATURE_ID
        receivedFeatureStatus = null
        originalFeatureState = original
        rollbackReason = null
        rollbackSubscriptionWarning = null
        experimentBatteryPercent = null
        begin(device, Operation.FEATURE_ROLLBACK, FEATURE_MUTATION_TIMEOUT_MS)
    }

    private fun begin(device: BluetoothDevice, requestedOperation: Operation, timeoutMs: Long) {
        operation = requestedOperation
        active = true
        stage = Stage.CONNECTING
        val generation = ++sessionGeneration
        onStatus(
            when (operation) {
                Operation.HISTORY_SYNC -> "Connecting for local history sync…"
                Operation.FEATURE_STATUS -> "Connecting for read-only feature status…"
                Operation.FEATURE_ENABLE -> "Connecting for controlled REAL_STEPS enable…"
                Operation.FEATURE_ROLLBACK -> "Connecting to restore the saved REAL_STEPS state…"
                Operation.BATTERY_TEST -> "Connecting to bonded ring…"
            },
        )
        gatt = device.connectGatt(applicationContext, false, this, BluetoothDevice.TRANSPORT_LE)
        mainHandler.postDelayed({
            if (active && generation == sessionGeneration && stage == Stage.CONNECTING) {
                fail(
                    "Bluetooth connection timed out (status $GATT_CONNECTION_TIMEOUT)",
                    retryableConnectionFailure = true,
                    connectionStatus = GATT_CONNECTION_TIMEOUT,
                )
            }
        }, CONNECTION_WATCHDOG_MS)
        mainHandler.postDelayed({
            if (active && generation == sessionGeneration) {
                fail(
                    "Timed out waiting for the ring; keep it nearby or place it on its charger",
                    retryableConnectionFailure = stage == Stage.CONNECTING,
                    connectionStatus = GATT_CONNECTION_TIMEOUT.takeIf { stage == Stage.CONNECTING },
                )
            }
        }, timeoutMs)
    }

    fun close() {
        sessionGeneration++
        active = false
        stage = Stage.IDLE
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeCharacteristic = null
        inFlightWrite?.value?.fill(0)
        pendingWrite?.value?.fill(0)
        inFlightWrite = null
        pendingWrite = null
        historyStore?.close()
        historyStore = null
        historyBatch = null
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (!active) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail(
                "Bluetooth connection failed (status $status)",
                retryableConnectionFailure = true,
                connectionStatus = status,
            )
            return
        }
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                stage = Stage.DISCOVERING
                onStatus("Connected; discovering Oura service…")
                if (!gatt.discoverServices()) fail("Could not start Oura service discovery")
            }
            BluetoothProfile.STATE_DISCONNECTED -> fail(
                "Ring disconnected before the test completed",
                retryableConnectionFailure = true,
            )
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (!active || status != BluetoothGatt.GATT_SUCCESS) {
            if (active) fail("Oura service discovery failed (status $status)")
            return
        }
        val service = gatt.getService(OuraGatt.service)
        val write = service?.getCharacteristic(OuraGatt.write)
        val notify = service?.getCharacteristic(OuraGatt.notify)
        val descriptor = notify?.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
        if (write == null || notify == null || descriptor == null) {
            fail("The bonded device does not expose the expected Oura Gen 3 service")
            return
        }
        writeCharacteristic = write
        if (!gatt.setCharacteristicNotification(notify, true)) {
            fail("Could not enable Oura notifications")
            return
        }
        stage = Stage.ENABLING_NOTIFICATIONS
        onStatus("Oura service found; enabling responses…")
        val result = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if (result != BluetoothStatusCodes.SUCCESS) fail("Could not subscribe to Oura responses ($result)")
    }

    override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (!active || stage != Stage.ENABLING_NOTIFICATIONS) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("Oura response subscription failed (status $status)")
            return
        }
        stage = Stage.WAITING_FOR_NONCE
        onStatus("Requesting authentication challenge…")
        write(gatt, OuraRequests.authNonce(), "authentication challenge")
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        if (!active || characteristic.uuid != OuraGatt.notify) return
        val packet = OuraPacket.parse(value) ?: return
        when (stage) {
            Stage.WAITING_FOR_NONCE -> handleNonce(gatt, packet)
            Stage.WAITING_FOR_AUTH -> handleAuthentication(gatt, packet)
            Stage.WAITING_FOR_BATTERY -> handleBattery(packet)
            Stage.WAITING_FOR_HISTORY_BATTERY -> handleHistoryBattery(gatt, packet)
            Stage.WAITING_FOR_HISTORY -> handleHistory(gatt, packet)
            Stage.WAITING_FOR_FEATURE_STATUS -> handleFeatureStatus(packet)
            Stage.WAITING_FOR_FEATURE_ENABLE_BATTERY -> handleFeatureEnableBattery(gatt, packet)
            Stage.WAITING_FOR_FEATURE_ENABLE_BASELINE -> handleFeatureEnableBaseline(gatt, packet)
            Stage.WAITING_FOR_FEATURE_MODE_RESULT -> handleFeatureModeResult(gatt, packet)
            Stage.WAITING_FOR_FEATURE_ENABLE_VERIFY -> handleFeatureEnableVerify(gatt, packet)
            Stage.WAITING_FOR_FEATURE_ROLLBACK_SUBSCRIPTION -> handleRollbackSubscriptionResult(gatt, packet)
            Stage.WAITING_FOR_FEATURE_ROLLBACK_SUBSCRIPTION_VERIFY -> handleRollbackSubscriptionVerify(gatt, packet)
            Stage.WAITING_FOR_FEATURE_ROLLBACK_MODE -> handleRollbackModeResult(gatt, packet)
            Stage.WAITING_FOR_FEATURE_ROLLBACK_VERIFY -> handleRollbackVerify(packet)
            else -> Unit
        }
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
    ) {
        if (!active || characteristic.uuid != OuraGatt.write) return
        val completed = inFlightWrite ?: return
        inFlightWrite = null
        completed.value.fill(0)
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("Oura ${completed.operation} failed (status $status)")
            return
        }
        val pending = pendingWrite
        if (pending != null) {
            pendingWrite = null
            sendWrite(gatt, pending)
        } else if (stage == Stage.WAITING_FOR_TIME_SYNC && completed.operation == TIME_SYNC_OPERATION) {
            requestHistoryBatch(gatt)
        }
    }

    private fun handleNonce(gatt: BluetoothGatt, packet: OuraPacket) {
        val nonce = OuraResponses.authNonce(packet) ?: return
        val key = runCatching { RingKeyStore(applicationContext).load() }.getOrElse {
            fail("Could not unlock the stored ring key")
            return
        }
        val encrypted = try {
            OuraAuthentication.encryptNonce(key, nonce)
        } finally {
            key.fill(0)
            nonce.fill(0)
        }
        stage = Stage.WAITING_FOR_AUTH
        onStatus("Authenticating with the ring…")
        try {
            write(gatt, OuraRequests.authenticate(encrypted), "authentication response")
        } finally {
            encrypted.fill(0)
        }
    }

    private fun handleAuthentication(gatt: BluetoothGatt, packet: OuraPacket) {
        if (packet.tag != 0x2f || packet.extendedTag() != 0x2e) return
        if (!OuraResponses.authenticationSucceeded(packet)) {
            fail("The ring rejected the stored application key")
            return
        }
        when (operation) {
            Operation.HISTORY_SYNC -> {
                if (checkHistoryBattery) {
                    stage = Stage.WAITING_FOR_HISTORY_BATTERY
                    onStatus("Authenticated; checking ring battery before history sync...")
                    write(gatt, OuraRequests.battery(), "history battery preflight")
                } else {
                    beginHistoryTimeSync(gatt)
                }
            }
            Operation.FEATURE_STATUS -> {
                stage = Stage.WAITING_FOR_FEATURE_STATUS
                onStatus("Authenticated; reading REAL_STEPS status without changing it…")
                write(gatt, OuraRequests.featureStatus(requestedFeatureId.toUByte()), "feature status request")
            }
            Operation.FEATURE_ENABLE -> {
                stage = Stage.WAITING_FOR_FEATURE_ENABLE_BATTERY
                onStatus("Authenticated; checking battery before changing REAL_STEPS…")
                write(gatt, OuraRequests.battery(), "REAL_STEPS battery preflight")
            }
            Operation.FEATURE_ROLLBACK -> beginFeatureRollback(gatt)
            Operation.BATTERY_TEST -> {
                stage = Stage.WAITING_FOR_BATTERY
                onStatus("Authenticated; reading battery…")
                write(gatt, OuraRequests.battery(), "battery request")
            }
        }
    }

    private fun handleFeatureEnableBattery(gatt: BluetoothGatt, packet: OuraPacket) {
        val percent = OuraResponses.batteryPercent(packet) ?: return
        experimentBatteryPercent = percent
        if (percent < MINIMUM_FEATURE_ENABLE_BATTERY_PERCENT) {
            fail("REAL_STEPS was not enabled: ring battery $percent% is below the $MINIMUM_FEATURE_ENABLE_BATTERY_PERCENT% safety minimum. Nothing changed.")
            return
        }
        stage = Stage.WAITING_FOR_FEATURE_ENABLE_BASELINE
        onStatus("Battery $percent%; capturing the rollback target before changing REAL_STEPS…")
        write(gatt, OuraRequests.featureStatus(requestedFeatureId.toUByte()), "REAL_STEPS baseline status")
    }

    private fun handleFeatureEnableBaseline(gatt: BluetoothGatt, packet: OuraPacket) {
        val status = OuraResponses.featureStatus(packet) ?: return
        if (status.featureId != requestedFeatureId) return
        if (!RealStepsExperimentStore(applicationContext).captureOriginal(status)) {
            fail("Could not persist the REAL_STEPS rollback target; nothing changed.")
            return
        }
        originalFeatureState = RealStepsOriginalState(status.mode, status.subscription)
        stage = Stage.WAITING_FOR_FEATURE_MODE_RESULT
        onStatus("Rollback target saved (${modeName(status.mode)}, subscription ${status.subscription}); enabling AUTOMATIC mode…")
        write(
            gatt,
            OuraRequests.setFeatureMode(requestedFeatureId.toUByte(), FEATURE_MODE_AUTOMATIC.toUByte()),
            "REAL_STEPS AUTOMATIC mode",
        )
    }

    private fun handleFeatureModeResult(gatt: BluetoothGatt, packet: OuraPacket) {
        val result = OuraResponses.setFeatureModeResult(packet) ?: return
        if (result.featureId != requestedFeatureId) return
        if (result.result != FEATURE_RESULT_SUCCESS) {
            rollbackReason = "AUTOMATIC mode was rejected (${featureResultName(result.result)})"
            beginFeatureRollback(gatt)
            return
        }
        stage = Stage.WAITING_FOR_FEATURE_ENABLE_VERIFY
        onStatus("AUTOMATIC accepted; leaving subscription unchanged and verifying the complete on-ring state…")
        write(gatt, OuraRequests.featureStatus(requestedFeatureId.toUByte()), "REAL_STEPS enable verification")
    }

    private fun handleFeatureEnableVerify(gatt: BluetoothGatt, packet: OuraPacket) {
        val status = OuraResponses.featureStatus(packet) ?: return
        if (status.featureId != requestedFeatureId) return
        receivedFeatureStatus = status
        val originalSubscription = originalFeatureState?.subscription ?: return
        if (status.mode != FEATURE_MODE_AUTOMATIC || status.subscription != originalSubscription) {
            rollbackReason = "mode-only read-back did not match AUTOMATIC/unchanged subscription " +
                "(mode ${status.mode}, subscription ${status.subscription}, expected subscription $originalSubscription)"
            beginFeatureRollback(gatt)
            return
        }
        succeed(
            "REAL_STEPS mode-only enable passed - mode AUTOMATIC (1), subscription unchanged at $originalSubscription, " +
                "battery ${experimentBatteryPercent ?: "unknown"}%; exact status read-back verified. " +
                "Rollback target is saved; no steps written to Health Connect.",
        )
    }

    private fun beginFeatureRollback(gatt: BluetoothGatt) {
        val original = originalFeatureState ?: RealStepsExperimentStore(applicationContext).original()
        if (original == null) {
            fail("Cannot restore REAL_STEPS because no saved rollback target exists. Stop the experiment and inspect status manually.")
            return
        }
        originalFeatureState = original
        stage = Stage.WAITING_FOR_FEATURE_ROLLBACK_SUBSCRIPTION
        onStatus("Restoring subscription ${original.subscription} before restoring ${modeName(original.mode)} mode…")
        write(
            gatt,
            OuraRequests.setFeatureSubscription(requestedFeatureId.toUByte(), original.subscription.toUByte()),
            "REAL_STEPS subscription rollback",
        )
    }

    private fun handleRollbackSubscriptionResult(gatt: BluetoothGatt, packet: OuraPacket) {
        val result = OuraResponses.setFeatureSubscriptionResult(packet) ?: return
        if (result.featureId != requestedFeatureId) return
        if (result.result != FEATURE_RESULT_SUCCESS) {
            rollbackSubscriptionWarning = featureResultName(result.result)
            stage = Stage.WAITING_FOR_FEATURE_ROLLBACK_SUBSCRIPTION_VERIFY
            onStatus("Subscription restore returned ${featureResultName(result.result)}; checking whether the ring already reports the saved subscription…")
            write(gatt, OuraRequests.featureStatus(requestedFeatureId.toUByte()), "REAL_STEPS subscription rollback check")
            return
        }
        restoreFeatureMode(gatt)
    }

    private fun handleRollbackSubscriptionVerify(gatt: BluetoothGatt, packet: OuraPacket) {
        val status = OuraResponses.featureStatus(packet) ?: return
        if (status.featureId != requestedFeatureId) return
        val original = originalFeatureState ?: return
        if (status.subscription != original.subscription) {
            fail(
                "REAL_STEPS rollback incomplete: subscription restore was rejected " +
                    "(${rollbackSubscriptionWarning ?: "unknown"}) and read-back is ${status.subscription}, " +
                    "expected ${original.subscription}. Saved target retained; retry rollback.",
            )
            return
        }
        onStatus(
            "Subscription already matches saved value ${original.subscription} despite the rejected write; restoring ${modeName(original.mode)} mode…",
        )
        restoreFeatureMode(gatt)
    }

    private fun restoreFeatureMode(gatt: BluetoothGatt) {
        val original = originalFeatureState ?: return
        stage = Stage.WAITING_FOR_FEATURE_ROLLBACK_MODE
        if (rollbackSubscriptionWarning == null) {
            onStatus("Subscription restored; restoring ${modeName(original.mode)} mode…")
        }
        write(
            gatt,
            OuraRequests.setFeatureMode(requestedFeatureId.toUByte(), original.mode.toUByte()),
            "REAL_STEPS mode rollback",
        )
    }

    private fun handleRollbackModeResult(gatt: BluetoothGatt, packet: OuraPacket) {
        val result = OuraResponses.setFeatureModeResult(packet) ?: return
        if (result.featureId != requestedFeatureId) return
        if (result.result != FEATURE_RESULT_SUCCESS) {
            fail("REAL_STEPS rollback incomplete: mode restore failed (${featureResultName(result.result)}). Saved target retained; retry rollback.")
            return
        }
        stage = Stage.WAITING_FOR_FEATURE_ROLLBACK_VERIFY
        onStatus("Rollback writes accepted; verifying the restored on-ring state…")
        write(gatt, OuraRequests.featureStatus(requestedFeatureId.toUByte()), "REAL_STEPS rollback verification")
    }

    private fun handleRollbackVerify(packet: OuraPacket) {
        val status = OuraResponses.featureStatus(packet) ?: return
        if (status.featureId != requestedFeatureId) return
        receivedFeatureStatus = status
        val original = originalFeatureState ?: return
        if (status.mode != original.mode || status.subscription != original.subscription) {
            fail(
                "REAL_STEPS rollback verification failed: expected mode ${original.mode}/subscription ${original.subscription}, " +
                    "read mode ${status.mode}/subscription ${status.subscription}. Saved target retained; retry rollback.",
            )
            return
        }
        if (!RealStepsExperimentStore(applicationContext).markRestored()) {
            fail("REAL_STEPS is restored on-ring, but the app could not clear its rollback marker. Ring state is ${modeName(status.mode)}, subscription ${status.subscription}.")
            return
        }
        val reason = rollbackReason
        if (reason != null) {
            fail("REAL_STEPS enable stopped because $reason. Automatic rollback passed; restored ${modeName(status.mode)}, subscription ${status.subscription}.")
        } else {
            succeed("REAL_STEPS rollback passed - restored ${modeName(status.mode)} (${status.mode}), subscription ${status.subscription}; exact status read-back verified.")
        }
    }

    private fun handleFeatureStatus(packet: OuraPacket) {
        val status = OuraResponses.featureStatus(packet) ?: return
        if (status.featureId != requestedFeatureId) return
        receivedFeatureStatus = status
        val modeName = modeName(status.mode)
        succeed(
            "REAL_STEPS status read passed - feature 0x${status.featureId.toString(16).padStart(2, '0')}, " +
                "mode $modeName (${status.mode}), status ${status.status}, state ${status.state}, " +
                "subscription ${status.subscription}. Read only; ring state unchanged.",
        )
    }

    private fun handleBattery(packet: OuraPacket) {
        val percent = OuraResponses.batteryPercent(packet) ?: return
        succeed("Authenticated ring connection passed — battery $percent%")
    }

    private fun handleHistoryBattery(gatt: BluetoothGatt, packet: OuraPacket) {
        val percent = OuraResponses.batteryPercent(packet) ?: return
        historyBatteryPercent = percent
        onHistoryBattery?.invoke(percent)
        val minimum = minimumHistoryBatteryPercent
        if (minimum != null && percent < minimum) {
            historyLowBatteryBlocked = true
            fail("Automatic history sync refused below $minimum% ring battery (battery $percent%)")
            return
        }
        beginHistoryTimeSync(gatt)
    }

    private fun beginHistoryTimeSync(gatt: BluetoothGatt) {
        stage = Stage.WAITING_FOR_TIME_SYNC
        onStatus("Battery preflight passed; aligning the ring UTC clock...")
        write(
            gatt,
            OuraRequests.syncTime((System.currentTimeMillis() / 1_000L).toULong()),
            TIME_SYNC_OPERATION,
        )
    }

    private fun requestHistoryBatch(gatt: BluetoothGatt) {
        historyBatch = EventBatchAccumulator(historyCursor)
        stage = Stage.WAITING_FOR_HISTORY
        onStatus("Authenticated; downloading history batch ${historyBatches + 1}…")
        write(gatt, OuraRequests.events(historyCursor), "history request")
    }

    private fun handleHistory(gatt: BluetoothGatt, packet: OuraPacket) {
        val accumulator = historyBatch ?: return
        val summary = accumulator.accept(packet) ?: return
        val completed = accumulator.complete(summary)
        historyReceived += completed.events.size
        historyBatches++

        if (completed.progressed) {
            val inserted = runCatching {
                historyStore?.commitBatch(completed.events, completed.nextCursor)
                    ?: error("History store is closed")
            }.getOrElse {
                fail("Could not commit the downloaded history batch")
                return
            }
            historyInserted += inserted
            historyCursor = completed.nextCursor
        }

        val drained = completed.bytesLeft == 0u
        val bounded = historyBatches >= historyBatchLimit
        historyMoreRemains = !drained
        if (drained || !completed.progressed || bounded) {
            val storedTotal = runCatching { historyStore?.stats()?.eventCount }.getOrNull()
            val suffix = if (bounded && !drained) "; more remains for the next sync" else ""
            succeed(
                "Local history sync passed — received $historyReceived, added $historyInserted" +
                    (storedTotal?.let { ", stored $it total" } ?: "") + suffix,
            )
        } else {
            requestHistoryBatch(gatt)
        }
    }

    private fun write(gatt: BluetoothGatt, value: ByteArray, operation: String) {
        val request = PendingWrite(value.copyOf(), operation)
        if (inFlightWrite != null) {
            pendingWrite?.value?.fill(0)
            pendingWrite = request
            return
        }
        sendWrite(gatt, request)
    }

    private fun sendWrite(gatt: BluetoothGatt, request: PendingWrite) {
        val characteristic = writeCharacteristic ?: run {
            request.value.fill(0)
            fail("Oura write characteristic disappeared")
            return
        }
        val result = gatt.writeCharacteristic(
            characteristic,
            request.value,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
        when (result) {
            BluetoothStatusCodes.SUCCESS -> inFlightWrite = request
            BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> {
                if (request.attempts >= MAX_BUSY_RETRIES) {
                    request.value.fill(0)
                    fail("Bluetooth remained busy while sending ${request.operation}")
                } else {
                    request.attempts++
                    mainHandler.postDelayed({
                        if (active) sendWrite(gatt, request) else request.value.fill(0)
                    }, BUSY_RETRY_DELAY_MS)
                }
            }
            else -> {
                request.value.fill(0)
                fail("Could not send ${request.operation} ($result)")
            }
        }
    }

    private fun succeed(message: String) {
        if (!active) return
        onStatus(message)
        val historyCallback = onHistoryFinished.takeIf { operation == Operation.HISTORY_SYNC }
        val featureCallback = onFeatureStatusFinished.takeIf { operation == Operation.FEATURE_STATUS }
        val historyResult = historyCallback?.let {
            HistorySyncResult(
                passed = true,
                received = historyReceived,
                inserted = historyInserted,
                storedTotal = runCatching { historyStore?.stats()?.eventCount }.getOrNull(),
                moreRemains = historyMoreRemains,
                retryableConnectionFailure = false,
                message = message,
                batteryPercent = historyBatteryPercent,
                lowBatteryBlocked = historyLowBatteryBlocked,
                connectionStatus = null,
            )
        }
        close()
        if (historyCallback != null && historyResult != null) historyCallback(historyResult)
        if (featureCallback != null) {
            featureCallback(RingFeatureStatusResult(true, receivedFeatureStatus, message))
        }
    }

    private fun fail(
        message: String,
        retryableConnectionFailure: Boolean = false,
        connectionStatus: Int? = null,
    ) {
        if (!active) return
        onStatus(message)
        val historyCallback = onHistoryFinished.takeIf { operation == Operation.HISTORY_SYNC }
        val featureCallback = onFeatureStatusFinished.takeIf { operation == Operation.FEATURE_STATUS }
        val historyResult = historyCallback?.let {
            HistorySyncResult(
                passed = false,
                received = historyReceived,
                inserted = historyInserted,
                storedTotal = null,
                moreRemains = false,
                retryableConnectionFailure = retryableConnectionFailure,
                message = message,
                batteryPercent = historyBatteryPercent,
                lowBatteryBlocked = historyLowBatteryBlocked,
                connectionStatus = connectionStatus,
            )
        }
        close()
        if (historyCallback != null && historyResult != null) historyCallback(historyResult)
        if (featureCallback != null) {
            featureCallback(RingFeatureStatusResult(false, null, message))
        }
    }

    private enum class Stage {
        IDLE,
        CONNECTING,
        DISCOVERING,
        ENABLING_NOTIFICATIONS,
        WAITING_FOR_NONCE,
        WAITING_FOR_AUTH,
        WAITING_FOR_TIME_SYNC,
        WAITING_FOR_BATTERY,
        WAITING_FOR_HISTORY_BATTERY,
        WAITING_FOR_HISTORY,
        WAITING_FOR_FEATURE_STATUS,
        WAITING_FOR_FEATURE_ENABLE_BATTERY,
        WAITING_FOR_FEATURE_ENABLE_BASELINE,
        WAITING_FOR_FEATURE_MODE_RESULT,
        WAITING_FOR_FEATURE_ENABLE_VERIFY,
        WAITING_FOR_FEATURE_ROLLBACK_SUBSCRIPTION,
        WAITING_FOR_FEATURE_ROLLBACK_SUBSCRIPTION_VERIFY,
        WAITING_FOR_FEATURE_ROLLBACK_MODE,
        WAITING_FOR_FEATURE_ROLLBACK_VERIFY,
    }

    private enum class Operation { BATTERY_TEST, HISTORY_SYNC, FEATURE_STATUS, FEATURE_ENABLE, FEATURE_ROLLBACK }

    private data class PendingWrite(
        val value: ByteArray,
        val operation: String,
        var attempts: Int = 0,
    )

    private companion object {
        val CLIENT_CHARACTERISTIC_CONFIG: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val BATTERY_TIMEOUT_MS = 20_000L
        const val FEATURE_STATUS_TIMEOUT_MS = 20_000L
        const val FEATURE_MUTATION_TIMEOUT_MS = 45_000L
        const val HISTORY_TIMEOUT_MS = 90_000L
        const val MAX_HISTORY_BATCHES = 20
        const val MAX_BUSY_RETRIES = 5
        const val BUSY_RETRY_DELAY_MS = 100L
        const val TIME_SYNC_OPERATION = "time synchronization"
        const val CONNECTION_WATCHDOG_MS = 45_000L
        const val GATT_CONNECTION_TIMEOUT = 147
        const val REAL_STEPS_FEATURE_ID = 0x0b
        const val FEATURE_MODE_AUTOMATIC = 0x01
        const val FEATURE_RESULT_SUCCESS = 0x00
        const val MINIMUM_FEATURE_ENABLE_BATTERY_PERCENT = 20

        fun modeName(mode: Int): String = when (mode) {
            0 -> "OFF"
            1 -> "AUTOMATIC"
            2 -> "REQUESTED"
            3 -> "CONNECTED_LIVE"
            else -> "UNKNOWN($mode)"
        }

        fun featureResultName(result: Int): String = when (result) {
            0 -> "SUCCESS"
            1 -> "NOT_SUPPORTED"
            2 -> "NOT_AVAILABLE"
            3 -> "NOT_IN_FINGER"
            4 -> "MESSAGE_TOO_SHORT"
            5 -> "LOW_BATTERY"
            else -> "result $result"
        }
    }
}
