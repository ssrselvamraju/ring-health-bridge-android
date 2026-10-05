package dev.local.ourahealthbridge

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelUuid
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import dev.local.ourahealthbridge.protocol.OuraGatt
import dev.local.ourahealthbridge.bluetooth.RingConnectionSmokeTest
import dev.local.ourahealthbridge.bluetooth.HistorySyncResult
import dev.local.ourahealthbridge.bluetooth.RealStepsExperimentStore
import dev.local.ourahealthbridge.analysis.HistoryDryRunPreviewBuilder
import dev.local.ourahealthbridge.analysis.HealthConnectCandidatePreviewBuilder
import dev.local.ourahealthbridge.analysis.RecordDryRunPreviewBuilder
import dev.local.ourahealthbridge.healthconnect.HistoricalDataSource
import dev.local.ourahealthbridge.healthconnect.HistoricalHealthConnectAudit
import dev.local.ourahealthbridge.healthconnect.DailyHealthConnectPublisher
import dev.local.ourahealthbridge.healthconnect.DailyPublicationSelection
import dev.local.ourahealthbridge.healthconnect.DailyPublicationSelector
import dev.local.ourahealthbridge.healthconnect.DailyPublicationStateStore
import dev.local.ourahealthbridge.healthconnect.ForegroundPublicationPlanner
import dev.local.ourahealthbridge.healthconnect.ForegroundPublicationPlan
import dev.local.ourahealthbridge.healthconnect.ForegroundRunReport
import dev.local.ourahealthbridge.healthconnect.ForegroundRunStateStore
import dev.local.ourahealthbridge.healthconnect.OneHourTestPublisher
import dev.local.ourahealthbridge.healthconnect.OneHourTestReadBackVerifier
import dev.local.ourahealthbridge.healthconnect.OneHourTestSelection
import dev.local.ourahealthbridge.healthconnect.OneHourTestSelector
import dev.local.ourahealthbridge.healthconnect.StepTrialReader
import dev.local.ourahealthbridge.healthconnect.StepTrialStore
import dev.local.ourahealthbridge.healthconnect.StepTrialSummarizer
import dev.local.ourahealthbridge.healthconnect.MarkedStepTrialEnd
import dev.local.ourahealthbridge.security.KeyImportBridge
import dev.local.ourahealthbridge.security.KeyImportState
import dev.local.ourahealthbridge.security.RingKeyStore
import dev.local.ourahealthbridge.storage.HistoryStore
import java.util.regex.Pattern
import java.util.Locale
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Duration
import java.time.ZoneId
import dev.local.ourahealthbridge.analysis.HealthConnectCandidateSet
import dev.local.ourahealthbridge.analysis.LatestLocalMetricsStore
import dev.local.ourahealthbridge.analysis.PrivateStepResearchAudit
import dev.local.ourahealthbridge.analysis.RingStepInventoryBuilder
import dev.local.ourahealthbridge.analysis.StepResearchWindow
import dev.local.ourahealthbridge.analysis.StepTimeWindowAudit
import dev.local.ourahealthbridge.ui.HealthConnectState
import dev.local.ourahealthbridge.ui.PrimeBridgeApp
import dev.local.ourahealthbridge.ui.PrimeUiEvent
import dev.local.ourahealthbridge.ui.PrimeUiState
import dev.local.ourahealthbridge.ui.RingDisplayNameStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    internal var associationStatus by mutableStateOf("Not associated")
    internal var connectionStatus by mutableStateOf("Not tested")
    internal var historyStatus by mutableStateOf("Not synced")
    internal var previewStatus by mutableStateOf("Not generated")
    internal var recordPreviewStatus by mutableStateOf("Not generated")
    internal var candidatePreviewStatus by mutableStateOf("Not generated")
    internal var historicalAuditStatus by mutableStateOf("Not run")
    internal var samsungAuditStatus by mutableStateOf("Not run")
    internal var stepResearchStatus by mutableStateOf("Not run")
    internal var stepTimeWindowStatus by mutableStateOf("Not run")
    internal var realStepsStatus by mutableStateOf("Not checked")
    internal var realStepsExperimentState by mutableStateOf("REAL_STEPS experiment not enabled by this app.")
    internal var stepTrialStatus by mutableStateOf("No controlled trial started")
    internal var oneHourTestStatus by mutableStateOf("Not prepared")
    internal var oneHourVerificationStatus by mutableStateOf("Not verified")
    internal var dailyDateStatus by mutableStateOf("No date selected")
    internal var dailyPreviewStatus by mutableStateOf("Not generated")
    internal var dailyPublicationStatus by mutableStateOf("No publication attempted")
    internal var foregroundSyncStatus by mutableStateOf("Not run")
    internal var foregroundRunHistory by mutableStateOf("No foreground sync/publish result yet")
    internal var backgroundScheduleStatus by mutableStateOf("Periodic background sync disabled")
    internal var pendingHistoricalSource = HistoricalDataSource.OURA
    internal var pendingStepReadAction = StepReadAction.PRIVATE_AUDIT
    internal var pendingManualStepCount: Int? = null
    internal var pendingStepResearchWindow: StepResearchWindow? = null
    internal var pendingWriteAction = OneHourWriteAction.WRITE
    internal var oneHourSelection: OneHourTestSelection? = null
    internal var dailySelection: DailyPublicationSelection? = null
    internal var availableDailyDates: List<LocalDate> = emptyList()
    internal var pendingDailyAction = DailyPublicationAction.PUBLISH_HR_HRV
    internal var smokeTest: RingConnectionSmokeTest? = null
    internal var foregroundRun: ForegroundRunContext? = null
    internal var foregroundOperationActive = false
    internal var notificationPermissionResolvedForRun = false
    internal var primeUiState by mutableStateOf(PrimeUiState())
    internal var healthConnectPermissionStateKnown = false
    internal var healthConnectWriteReady = false
    internal val foregroundStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshForegroundStatus()
    }

    internal val nearbyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.values.all { it }) {
            associateOrBond()
        } else {
            associationStatus = "Nearby Devices permission was not granted"
        }
    }

    internal val healthPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        if (granted.containsAll(HistoricalHealthConnectAudit.REQUIRED_PERMISSIONS)) {
            runHistoricalAudit(pendingHistoricalSource)
        } else {
            setHistoricalAuditStatus(
                pendingHistoricalSource,
                "Read access was incomplete. Enable the selected data types and Access past data.",
            )
        }
    }

    internal val stepResearchPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        if (granted.containsAll(PrivateStepResearchAudit.REQUIRED_PERMISSIONS)) {
            executeStepReadAction(pendingStepReadAction, pendingManualStepCount)
        } else {
            setStepReadActionStatus(
                pendingStepReadAction,
                "Step read access was incomplete. Enable Steps and Access past data; nothing changed.",
            )
        }
    }

    internal val writePermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        if (granted.containsAll(OneHourTestPublisher.WRITE_PERMISSIONS)) {
            executeOneHourAction(pendingWriteAction)
        } else {
            oneHourTestStatus = "HR and HRV write permission was not granted; nothing changed."
        }
    }

    internal val dailyPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        val required = dailyPermissions(pendingDailyAction)
        if (granted.containsAll(required)) {
            executeDailyAction(pendingDailyAction)
        } else {
            dailyPublicationStatus = "Required Health Connect permissions were incomplete; nothing changed."
        }
    }

    internal val foregroundPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        healthConnectPermissionStateKnown = true
        healthConnectWriteReady = granted.containsAll(DailyHealthConnectPublisher.ALL_PERMISSIONS)
        refreshPrimeUiState()
        if (granted.containsAll(DailyHealthConnectPublisher.ALL_PERMISSIONS)) {
            startLifecycleSafeForegroundSync()
        } else {
            foregroundSyncStatus = "Required Health Connect permissions were incomplete; nothing changed."
        }
    }

    internal val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationPermissionResolvedForRun = true
        requestForegroundSyncPublish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val importState = KeyImportBridge.importStagedKey(this)
        val keyPresent = RingKeyStore(this).hasKey()
        val healthConnectStatus = HealthConnectClient.getSdkStatus(this)
        ForegroundRunStateStore(this).also {
            foregroundSyncStatus = it.currentStatusText()
            foregroundRunHistory = it.statusText()
        }
        backgroundScheduleStatus = BackgroundScheduleStateStore(this).statusText()
        stepTrialStatus = StepTrialStore(this).lastStatus()
        realStepsExperimentState = RealStepsExperimentStore(this).statusText()
        val deviceManager = getSystemService(CompanionDeviceManager::class.java)
        if (deviceManager.myAssociations.isNotEmpty()) {
            associationStatus = "System association exists; tap below to request the Bluetooth bond"
        }
        refreshPrimeUiState()

        setContent {
            PrimeBridgeApp(
                state = primeUiState,
                onEvent = ::handlePrimeUiEvent,
                legacyContent = {
                    FoundationScreen(
                    keyPresent = keyPresent,
                    importState = importState,
                    associationStatus = associationStatus,
                    connectionStatus = connectionStatus,
                    historyStatus = historyStatus,
                    previewStatus = previewStatus,
                    recordPreviewStatus = recordPreviewStatus,
                    candidatePreviewStatus = candidatePreviewStatus,
                    historicalAuditStatus = historicalAuditStatus,
                    samsungAuditStatus = samsungAuditStatus,
                    stepResearchStatus = stepResearchStatus,
                    stepTimeWindowStatus = stepTimeWindowStatus,
                    realStepsStatus = realStepsStatus,
                    realStepsExperimentState = realStepsExperimentState,
                    stepTrialStatus = stepTrialStatus,
                    oneHourTestStatus = oneHourTestStatus,
                    oneHourVerificationStatus = oneHourVerificationStatus,
                    dailyDateStatus = dailyDateStatus,
                    dailyPreviewStatus = dailyPreviewStatus,
                    dailyPublicationStatus = dailyPublicationStatus,
                    foregroundSyncStatus = foregroundSyncStatus,
                    foregroundRunHistory = foregroundRunHistory,
                    backgroundScheduleStatus = backgroundScheduleStatus,
                    healthConnectStatus = healthConnectStatus,
                    onAssociate = ::requestAssociation,
                    onTestConnection = ::testRingConnection,
                    onSyncHistory = ::syncRingHistory,
                    onBuildPreview = ::buildDryRunPreview,
                    onBuildRecordPreview = ::buildRecordDryRunPreview,
                    onBuildCandidatePreview = ::buildHealthConnectCandidatePreview,
                    onAuditHistoricalOura = { requestHistoricalAudit(HistoricalDataSource.OURA) },
                    onAuditHistoricalSamsung = { requestHistoricalAudit(HistoricalDataSource.SAMSUNG_HEALTH) },
                    onRunStepResearchAudit = ::requestPrivateStepResearchAudit,
                    onRunStepTimeWindowAudit = ::requestStepTimeWindowAudit,
                    onRunLastStepTrialAudit = ::requestLastCompletedStepWindowAudit,
                    onCheckRealStepsStatus = ::checkRealStepsStatus,
                    onEnableRealStepsExperiment = ::enableRealStepsExperiment,
                    onRollbackRealStepsExperiment = ::rollbackRealStepsExperiment,
                    onStartStepTrial = { requestStepReadAction(StepReadAction.START_TRIAL) },
                    onMarkStepTrialEnd = ::markStepTrialWalkFinished,
                    onFinishStepTrial = { requestStepReadAction(StepReadAction.FINISH_TRIAL) },
                    onPrepareOneHourTest = ::prepareOneHourTest,
                    onWriteOneHourTest = { requestOneHourAction(OneHourWriteAction.WRITE) },
                    onDeleteOneHourTest = { requestOneHourAction(OneHourWriteAction.DELETE) },
                    onVerifyOneHourTest = ::verifyOneHourTest,
                    onLoadLatestDate = { loadDailyDate(0, selectLatest = true) },
                    onPreviousDate = { loadDailyDate(-1, selectLatest = false) },
                    onNextDate = { loadDailyDate(1, selectLatest = false) },
                    onPublishDateHrHrv = { requestDailyAction(DailyPublicationAction.PUBLISH_HR_HRV) },
                    onPublishDateSleep = { requestDailyAction(DailyPublicationAction.PUBLISH_SLEEP) },
                    onRepairDate = { requestDailyAction(DailyPublicationAction.REPAIR_DATE) },
                    onDeleteDate = { requestDailyAction(DailyPublicationAction.DELETE_DATE) },
                    onForegroundSyncPublish = ::requestForegroundSyncPublish,
                    onRunBackgroundTest = ::enqueueBackgroundTriggerTest,
                    onEnableBackgroundSync = ::enableBackgroundSync,
                    onDisableBackgroundSync = ::disableBackgroundSync,
                    onCopy = ::copyDiagnostics,
                    onCopyAll = {
                        copyDiagnostics(
                            listOf(
                                "Aggregate preview: $previewStatus",
                                "Record preview: $recordPreviewStatus",
                                "Candidate preview: $candidatePreviewStatus",
                                "Historical Oura audit: $historicalAuditStatus",
                                "Historical Samsung Health audit: $samsungAuditStatus",
                                "Private step-source audit: $stepResearchStatus",
                                "Private step time-window audit: $stepTimeWindowStatus",
                                "REAL_STEPS status: $realStepsStatus",
                                "REAL_STEPS experiment: $realStepsExperimentState",
                                "Controlled step trial: $stepTrialStatus",
                                "One-hour Health Connect test: $oneHourTestStatus",
                                "One-hour read-back verification: $oneHourVerificationStatus",
                                "Selected publication date: $dailyDateStatus",
                                "Date preview: $dailyPreviewStatus",
                                "Date publication: $dailyPublicationStatus",
                                "Foreground sync/publish: $foregroundSyncStatus",
                                "Foreground run history: $foregroundRunHistory",
                                "Background schedule: $backgroundScheduleStatus",
                            ).joinToString("\n\n"),
                        )
                    },
                    )
                },
            )
        }
    }

    override fun onDestroy() {
        smokeTest?.close()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(
            foregroundStatusReceiver,
            IntentFilter(ForegroundSyncService.ACTION_STATE_CHANGED),
            RECEIVER_NOT_EXPORTED,
        )
        refreshForegroundStatus()
        refreshHealthConnectAccess()
        ForegroundOpenSyncCoordinator(this).maybeStart(lifecycleScope)
    }

    override fun onStop() {
        unregisterReceiver(foregroundStatusReceiver)
        super.onStop()
    }
}

@Composable
private fun FoundationScreen(
    keyPresent: Boolean,
    importState: KeyImportState,
    associationStatus: String,
    connectionStatus: String,
    historyStatus: String,
    previewStatus: String,
    recordPreviewStatus: String,
    candidatePreviewStatus: String,
    historicalAuditStatus: String,
    samsungAuditStatus: String,
    stepResearchStatus: String,
    stepTimeWindowStatus: String,
    realStepsStatus: String,
    realStepsExperimentState: String,
    stepTrialStatus: String,
    oneHourTestStatus: String,
    oneHourVerificationStatus: String,
    dailyDateStatus: String,
    dailyPreviewStatus: String,
    dailyPublicationStatus: String,
    foregroundSyncStatus: String,
    foregroundRunHistory: String,
    backgroundScheduleStatus: String,
    healthConnectStatus: Int,
    onAssociate: () -> Unit,
    onTestConnection: () -> Unit,
    onSyncHistory: () -> Unit,
    onBuildPreview: () -> Unit,
    onBuildRecordPreview: () -> Unit,
    onBuildCandidatePreview: () -> Unit,
    onAuditHistoricalOura: () -> Unit,
    onAuditHistoricalSamsung: () -> Unit,
    onRunStepResearchAudit: () -> Unit,
    onRunStepTimeWindowAudit: (String, String) -> Unit,
    onRunLastStepTrialAudit: () -> Unit,
    onCheckRealStepsStatus: () -> Unit,
    onEnableRealStepsExperiment: () -> Unit,
    onRollbackRealStepsExperiment: () -> Unit,
    onStartStepTrial: () -> Unit,
    onMarkStepTrialEnd: (Int?) -> Unit,
    onFinishStepTrial: () -> Unit,
    onPrepareOneHourTest: () -> Unit,
    onWriteOneHourTest: () -> Unit,
    onDeleteOneHourTest: () -> Unit,
    onVerifyOneHourTest: () -> Unit,
    onLoadLatestDate: () -> Unit,
    onPreviousDate: () -> Unit,
    onNextDate: () -> Unit,
    onPublishDateHrHrv: () -> Unit,
    onPublishDateSleep: () -> Unit,
    onRepairDate: () -> Unit,
    onDeleteDate: () -> Unit,
    onForegroundSyncPublish: () -> Unit,
    onRunBackgroundTest: () -> Unit,
    onEnableBackgroundSync: () -> Unit,
    onDisableBackgroundSync: () -> Unit,
    onCopy: (String) -> Unit,
    onCopyAll: () -> Unit,
) {
    var confirmOneHourDelete by rememberSaveable { mutableStateOf(false) }
    var confirmDateDelete by rememberSaveable { mutableStateOf(false) }
    var manualStepCount by rememberSaveable { mutableStateOf("") }
    var stepWindowDate by rememberSaveable { mutableStateOf("2026-09-29") }
    var stepWindowTime by rememberSaveable { mutableStateOf("20:15") }
    var confirmRealStepsEnable by rememberSaveable { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onCopyAll, modifier = Modifier.fillMaxWidth()) {
                Text("Copy diagnostic report")
            }

            Text("Latest result", style = MaterialTheme.typography.titleLarge)
            DiagnosticOutput("Foreground sync/publish", foregroundSyncStatus, onCopy)

            AdvancedGroup("Ring & transport", "Association, authentication, and local history") {
                Text("Ring key: ${if (keyPresent) "stored securely" else "not imported"}")
                when (importState) {
                    KeyImportState.IMPORTED -> Text("USB key import completed; staging data removed.")
                    KeyImportState.REJECTED -> Text("USB key import rejected; staging data removed.")
                    else -> Unit
                }
                OperationStatusLine("Association", associationStatus)
                AdvancedAction("Associate Oura ring", onAssociate, keyPresent)
                OperationStatusLine("Connection", connectionStatus)
                AdvancedAction("Test authenticated connection", onTestConnection, keyPresent)
                OperationStatusLine("Local history", historyStatus)
                AdvancedAction("Sync ring history locally", onSyncHistory, keyPresent)
            }

            AdvancedGroup("Sync & publication", "Run now and inspect recent activity") {
                Text(healthConnectLabel(healthConnectStatus))
                AdvancedAction(
                    "Sync and publish now",
                    onForegroundSyncPublish,
                    keyPresent && healthConnectStatus == HealthConnectClient.SDK_AVAILABLE,
                )
                DiagnosticOutput("Foreground run history", foregroundRunHistory, onCopy)
            }

            AdvancedGroup("Data pipeline", "Private decoding and candidate diagnostics") {
                AdvancedAction("Build aggregate dry-run preview", onBuildPreview)
                DiagnosticOutput("Aggregate preview", previewStatus, onCopy)
                AdvancedAction("Build record-level dry run", onBuildRecordPreview)
                DiagnosticOutput("Record preview", recordPreviewStatus, onCopy)
                AdvancedAction("Build Health Connect candidates", onBuildCandidatePreview)
                DiagnosticOutput("Candidate preview", candidatePreviewStatus, onCopy)
            }

            AdvancedGroup("Source audits", "Read-only comparison with historical sources") {
                AdvancedAction("Run private step-source audit", onRunStepResearchAudit)
                DiagnosticOutput("Private step-source audit", stepResearchStatus, onCopy)
                AdvancedAction("Audit historical Oura data", onAuditHistoricalOura)
                DiagnosticOutput("Historical Oura audit", historicalAuditStatus, onCopy)
                AdvancedAction("Audit historical Samsung Health data", onAuditHistoricalSamsung)
                DiagnosticOutput("Historical Samsung Health audit", samsungAuditStatus, onCopy)
            }

            AdvancedGroup("Step research", "Controlled on-ring experiment and local comparisons") {
                OutlinedTextField(
                    value = stepWindowDate,
                    onValueChange = { stepWindowDate = it.take(10) },
                    label = { Text("Walk date (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = stepWindowTime,
                    onValueChange = { stepWindowTime = it.take(5) },
                    label = { Text("Walk start (HH:MM local)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                AdvancedAction("Analyze private walk time window", {
                    onRunStepTimeWindowAudit(stepWindowDate, stepWindowTime)
                })
                AdvancedAction("Analyze last completed controlled trial", onRunLastStepTrialAudit)
                DiagnosticOutput("Private step time-window audit", stepTimeWindowStatus, onCopy)
                AdvancedAction("Check REAL_STEPS status (read only)", onCheckRealStepsStatus, keyPresent)
                DiagnosticOutput("REAL_STEPS status", realStepsStatus, onCopy)
                Text(realStepsExperimentState)
                if (confirmRealStepsEnable) {
                    Text(
                        "Temporarily enable only AUTOMATIC mode while leaving subscription unchanged? " +
                            "The original state will be saved and verified rollback remains available.",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            confirmRealStepsEnable = false
                            onEnableRealStepsExperiment()
                        }) { Text("Confirm enable") }
                        TextButton(onClick = { confirmRealStepsEnable = false }) { Text("Cancel") }
                    }
                } else {
                    AdvancedAction(
                        "Enable mode-only REAL_STEPS experiment",
                        { confirmRealStepsEnable = true },
                        keyPresent,
                    )
                }
                DangerAction("Rollback REAL_STEPS to saved state", onRollbackRealStepsExperiment)
                AdvancedAction("Start controlled step trial", onStartStepTrial)
                OutlinedTextField(
                    value = manualStepCount,
                    onValueChange = { value -> manualStepCount = value.filter(Char::isDigit).take(6) },
                    label = { Text("Manually counted steps") },
                    supportingText = {
                        Text("Enter the count, then mark the end immediately after your last step.")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                AdvancedAction("Mark walk finished now", {
                    onMarkStepTrialEnd(manualStepCount.toIntOrNull())
                })
                Text("After marking the end, remain still, run Sync and publish now, then finalize.")
                AdvancedAction("Finalize trial after sync", onFinishStepTrial)
                DiagnosticOutput("Controlled step trial", stepTrialStatus, onCopy)
            }

            AdvancedGroup("One-hour Health Connect test", "Exact write, read-back, and deletion") {
                AdvancedAction("Prepare one-hour write test", onPrepareOneHourTest)
                AdvancedAction("Write selected one-hour test", onWriteOneHourTest)
                AdvancedAction("Verify selected one-hour test", onVerifyOneHourTest)
                if (confirmOneHourDelete) {
                    Text("Delete only the selected deterministic test records?")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DangerAction("Confirm delete") {
                            confirmOneHourDelete = false
                            onDeleteOneHourTest()
                        }
                        TextButton(onClick = { confirmOneHourDelete = false }) { Text("Cancel") }
                    }
                } else {
                    DangerAction("Delete selected one-hour test") { confirmOneHourDelete = true }
                }
                DiagnosticOutput("One-hour Health Connect test", oneHourTestStatus, onCopy)
                DiagnosticOutput("One-hour read-back verification", oneHourVerificationStatus, onCopy)
            }

            AdvancedGroup("Manual date publication", "Temporary date-level repair tools") {
                AdvancedAction("Load latest available date", onLoadLatestDate)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPreviousDate, modifier = Modifier.weight(1f)) { Text("Previous") }
                    Button(onClick = onNextDate, modifier = Modifier.weight(1f)) { Text("Next") }
                }
                DiagnosticOutput("Selected publication date", dailyDateStatus, onCopy)
                AdvancedAction("Publish and verify date HR/HRV", onPublishDateHrHrv)
                AdvancedAction("Publish and verify completed sleep", onPublishDateSleep)
                AdvancedAction("Repair and verify selected date", onRepairDate)
                if (confirmDateDelete) {
                    Text("Delete only this app's records for the selected date?")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DangerAction("Confirm delete") {
                            confirmDateDelete = false
                            onDeleteDate()
                        }
                        TextButton(onClick = { confirmDateDelete = false }) { Text("Cancel") }
                    }
                } else {
                    DangerAction("Delete app records for selected date") { confirmDateDelete = true }
                }
                DiagnosticOutput("Date preview", dailyPreviewStatus, onCopy)
                DiagnosticOutput("Date publication", dailyPublicationStatus, onCopy)
            }

            AdvancedGroup("Background scheduler", "Periodic and return-to-range diagnostics") {
                AdvancedAction("Run one-time background trigger test", onRunBackgroundTest)
                AdvancedAction("Enable inexact 3-hour background sync", onEnableBackgroundSync)
                AdvancedAction("Disable background sync", onDisableBackgroundSync)
                DiagnosticOutput("Background schedule", backgroundScheduleStatus, onCopy)
            }

            Text("Raw history stays app-private. Previews never write to Health Connect.")
        }
    }
}

@Composable
private fun AdvancedGroup(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall)
                }
                Text(if (expanded) "Hide" else "View")
            }
            if (expanded) content()
        }
    }
}

@Composable
private fun AdvancedAction(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    var recentlyStarted by remember(label) { mutableStateOf(false) }
    LaunchedEffect(recentlyStarted) {
        if (recentlyStarted) {
            delay(ACTION_CONFIRMATION_MILLIS)
            recentlyStarted = false
        }
    }
    val containerColor by animateColorAsState(
        targetValue = when {
            pressed -> MaterialTheme.colorScheme.secondary
            recentlyStarted -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        label = "Advanced action color",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            pressed -> MaterialTheme.colorScheme.onSecondary
            recentlyStarted -> MaterialTheme.colorScheme.onTertiary
            else -> MaterialTheme.colorScheme.onPrimary
        },
        label = "Advanced action content color",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        label = "Advanced action press scale",
    )
    Button(
        onClick = {
            recentlyStarted = true
            onClick()
        },
        enabled = enabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
        modifier = Modifier.fillMaxWidth().graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        Text(
            when {
                pressed -> "Release to run"
                recentlyStarted -> "Started ✓"
                else -> label
            },
        )
    }
}

private const val ACTION_CONFIRMATION_MILLIS = 1_500L

@Composable
private fun DangerAction(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
    ) { Text(label) }
}

private fun healthConnectLabel(status: Int): String = "Health Connect: " + when (status) {
    HealthConnectClient.SDK_AVAILABLE -> "available"
    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update required"
    else -> "unavailable"
}

@Composable
private fun DiagnosticOutput(label: String, value: String, onCopy: (String) -> Unit) {
    var expanded by rememberSaveable(label) { mutableStateOf(false) }
    val visualState = diagnosticVisualState(value)
    val indicatorColor by animateColorAsState(
        targetValue = when (visualState) {
            DiagnosticVisualState.IDLE -> MaterialTheme.colorScheme.surfaceVariant
            DiagnosticVisualState.RUNNING -> MaterialTheme.colorScheme.tertiaryContainer
            DiagnosticVisualState.ACTIVE -> MaterialTheme.colorScheme.secondaryContainer
            DiagnosticVisualState.COMPLETE -> MaterialTheme.colorScheme.primaryContainer
            DiagnosticVisualState.ATTENTION -> MaterialTheme.colorScheme.errorContainer
        },
        label = "Diagnostic state color",
    )
    val indicatorContentColor = when (visualState) {
        DiagnosticVisualState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        DiagnosticVisualState.RUNNING -> MaterialTheme.colorScheme.onTertiaryContainer
        DiagnosticVisualState.ACTIVE -> MaterialTheme.colorScheme.onSecondaryContainer
        DiagnosticVisualState.COMPLETE -> MaterialTheme.colorScheme.onPrimaryContainer
        DiagnosticVisualState.ATTENTION -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Surface(
                color = indicatorColor,
                contentColor = indicatorContentColor,
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    visualState.label,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide details" else "View details")
                }
                TextButton(onClick = { onCopy("$label: $value") }) {
                    Text("Copy")
                }
            }
            if (expanded) {
                SelectionContainer {
                    Text(value)
                }
            }
        }
    }
}

@Composable
private fun OperationStatusLine(label: String, value: String) {
    val state = diagnosticVisualState(value)
    val color = when (state) {
        DiagnosticVisualState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        DiagnosticVisualState.RUNNING -> MaterialTheme.colorScheme.tertiary
        DiagnosticVisualState.ACTIVE -> MaterialTheme.colorScheme.secondary
        DiagnosticVisualState.COMPLETE -> MaterialTheme.colorScheme.primary
        DiagnosticVisualState.ATTENTION -> MaterialTheme.colorScheme.error
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$label · ${state.label}", color = color, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

internal enum class DiagnosticVisualState(val label: String) {
    IDLE("Not run"),
    RUNNING("Running…"),
    ACTIVE("Active"),
    COMPLETE("Complete ✓"),
    ATTENTION("Needs attention"),
}

internal fun diagnosticVisualState(value: String): DiagnosticVisualState {
    val normalized = value.trim().lowercase(Locale.US)
    if (normalized.isEmpty() || normalized in setOf(
            "not run",
            "not generated",
            "not tested",
            "not synced",
            "not prepared",
            "not verified",
            "not checked",
            "not associated",
            "no date selected",
            "no publication attempted",
            "no controlled trial started",
            "no foreground sync/publish result yet",
        )
    ) return DiagnosticVisualState.IDLE

    if (listOf(
            "failed",
            "could not",
            "rejected",
            "unavailable",
            "incomplete",
            "error",
            "needs attention",
            "did not finish",
        ).any(normalized::contains)
    ) return DiagnosticVisualState.ATTENTION

    if (listOf(
            "preparing",
            "reading",
            "checking",
            "connecting",
            "capturing",
            "syncing",
            "publishing",
            "writing",
            "deleting",
            "repairing",
            "loading",
            "waiting for",
            "history drained",
            "in progress",
            "already active",
        ).any(normalized::contains)
    ) return DiagnosticVisualState.RUNNING

    if (listOf("trial started", "walk end marked", "experiment active").any(normalized::contains)) {
        return DiagnosticVisualState.ACTIVE
    }
    return DiagnosticVisualState.COMPLETE
}

private fun MainActivity.requestAssociation() {
    val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )
    if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
        associateOrBond()
    } else {
        associationStatus = "Waiting for Nearby Devices permission"
        nearbyPermissionLauncher.launch(permissions)
    }
}

private fun MainActivity.associateOrBond() {
    val existingAssociation = getSystemService(CompanionDeviceManager::class.java)
        .myAssociations
        .maxByOrNull(AssociationInfo::getId)
    if (existingAssociation != null) {
        associationStatus = "Using existing association; requesting Bluetooth bond"
        requestBond(existingAssociation)
    } else {
        beginAssociation()
    }
}

private fun MainActivity.beginAssociation() {
    associationStatus = "Looking for an Oura ring…"
    val scanFilter = ScanFilter.Builder()
        .setServiceUuid(ParcelUuid(OuraGatt.service))
        .build()
    val deviceFilter = BluetoothLeDeviceFilter.Builder()
        .setNamePattern(Pattern.compile("Oura.*", Pattern.CASE_INSENSITIVE))
        .setScanFilter(scanFilter)
        .build()
    val request = AssociationRequest.Builder()
        .addDeviceFilter(deviceFilter)
        .setSingleDevice(true)
        .build()
    val deviceManager = getSystemService(CompanionDeviceManager::class.java)

    deviceManager.associate(
        request,
        mainExecutor,
        object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: android.content.IntentSender) {
                associationStatus = "Waiting for your confirmation"
                startIntentSenderForResult(intentSender, ASSOCIATION_REQUEST, null, 0, 0, 0)
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                getSharedPreferences(ASSOCIATION_PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(ASSOCIATION_ID, associationInfo.id)
                    .apply()
                associationStatus = "Associated; requesting Bluetooth bond"
                requestBond(associationInfo)
            }

            override fun onFailure(errorMessage: CharSequence?) {
                associationStatus = errorMessage?.toString()?.take(120) ?: "Association failed"
            }
        },
    )
}

private fun MainActivity.requestBond(associationInfo: AssociationInfo) {
    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        associationStatus = "Associated, but Bluetooth Connect permission is missing"
        return
    }
    val device = bluetoothDevice(associationInfo)
    if (device == null) {
        associationStatus = "Associated, but Android did not return a usable BLE device"
        return
    }

    associationStatus = when (device.bondState) {
        BluetoothDevice.BOND_BONDED -> "Associated and Bluetooth bonded"
        BluetoothDevice.BOND_BONDING -> "Associated; Bluetooth bonding is in progress"
        else -> runCatching { device.createBond() }.fold(
        onSuccess = { bondStarted ->
            if (bondStarted) {
                "Associated; approve the Bluetooth pairing prompt"
            } else {
                "Associated; Android could not start Bluetooth bonding"
            }
        },
        onFailure = { error ->
            "Associated; bond request failed (${error.javaClass.simpleName})"
        },
        )
    }
}

private fun MainActivity.testRingConnection() {
    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        connectionStatus = "Bluetooth Connect permission is missing"
        return
    }
    val association = getSystemService(CompanionDeviceManager::class.java)
        .myAssociations
        .maxByOrNull(AssociationInfo::getId)
    val device = association?.let(::bluetoothDevice)
    if (device == null) {
        connectionStatus = "Associate the ring first"
        return
    }
    if (device.bondState != BluetoothDevice.BOND_BONDED) {
        connectionStatus = "Bluetooth bond is not complete"
        return
    }

    smokeTest?.close()
    connectionStatus = "Connecting to bonded ring…"
    smokeTest = RingConnectionSmokeTest(
        context = this,
        onStatus = { status -> runOnUiThread { connectionStatus = status } },
    ).also { it.start(device) }
}

private fun MainActivity.checkRealStepsStatus() {
    val device = bondedAssociatedDevice() ?: run {
        realStepsStatus = "Associate and bond the ring first; nothing changed."
        return
    }
    smokeTest?.close()
    realStepsStatus = "Connecting for read-only REAL_STEPS status..."
    smokeTest = RingConnectionSmokeTest(
        context = this,
        onStatus = { status -> runOnUiThread { realStepsStatus = status } },
    ).also { it.startFeatureStatus(device) }
}

private fun MainActivity.enableRealStepsExperiment() {
    val device = bondedAssociatedDevice() ?: run {
        realStepsStatus = "Associate and bond the ring first; nothing changed."
        return
    }
    smokeTest?.close()
    realStepsStatus = "Preparing controlled REAL_STEPS enable..."
    smokeTest = RingConnectionSmokeTest(
        context = this,
        onStatus = { status ->
            runOnUiThread {
                realStepsStatus = status
                realStepsExperimentState = RealStepsExperimentStore(this).statusText()
            }
        },
    ).also { it.startRealStepsEnable(device) }
}

private fun MainActivity.rollbackRealStepsExperiment() {
    val device = bondedAssociatedDevice() ?: run {
        realStepsStatus = "Associate and bond the ring first; rollback was not attempted."
        return
    }
    smokeTest?.close()
    realStepsStatus = "Preparing REAL_STEPS rollback..."
    smokeTest = RingConnectionSmokeTest(
        context = this,
        onStatus = { status ->
            runOnUiThread {
                realStepsStatus = status
                realStepsExperimentState = RealStepsExperimentStore(this).statusText()
            }
        },
    ).also { it.startRealStepsRollback(device) }
}

private fun MainActivity.syncRingHistory() {
    val device = bondedAssociatedDevice() ?: run {
        historyStatus = "Associate and bond the ring first"
        return
    }
    smokeTest?.close()
    historyStatus = "Starting bounded local history sync…"
    smokeTest = RingConnectionSmokeTest(
        context = this,
        onStatus = { status -> runOnUiThread { historyStatus = status } },
    ).also { it.startHistorySync(device) }
}

private fun MainActivity.buildDryRunPreview() {
    previewStatus = "Decoding app-private history…"
    Thread {
        val result = runCatching {
            HistoryStore(this).use { store ->
                HistoryDryRunPreviewBuilder.build(store.loadRawEvents()).statusText()
            }
        }.getOrElse { "Could not build the local preview (${it.javaClass.simpleName})" }
        runOnUiThread { previewStatus = result }
    }.start()
}

private fun MainActivity.buildRecordDryRunPreview() {
    recordPreviewStatus = "Reconstructing private record candidates…"
    Thread {
        val result = runCatching {
            HistoryStore(this).use { store ->
                RecordDryRunPreviewBuilder.build(store.loadRawEvents()).statusText()
            }
        }.getOrElse { "Could not build the record preview (${it.javaClass.simpleName})" }
        runOnUiThread { recordPreviewStatus = result }
    }.start()
}

private fun MainActivity.buildHealthConnectCandidatePreview() {
    candidatePreviewStatus = "Deduplicating and shaping private candidates..."
    Thread {
        val result = runCatching {
            HistoryStore(this).use { store ->
                HealthConnectCandidatePreviewBuilder.build(store.loadRawEvents()).preview.statusText()
            }
        }.getOrElse { "Could not build candidate preview (${it.javaClass.simpleName})" }
        runOnUiThread { candidatePreviewStatus = result }
    }.start()
}

private fun MainActivity.prepareOneHourTest() {
    oneHourTestStatus = "Selecting the latest completed HR/HRV hour..."
    Thread {
        val selection = runCatching {
            HistoryStore(this).use { store ->
                OneHourTestSelector.select(
                    HealthConnectCandidatePreviewBuilder.build(store.loadRawEvents()),
                    System.currentTimeMillis(),
                )
            }
        }.getOrNull()
        runOnUiThread {
            oneHourSelection = selection
            oneHourTestStatus = selection?.previewText()
                ?: "No completed hour with valid HR candidates is available; nothing written."
        }
    }.start()
}

private fun MainActivity.requestOneHourAction(action: OneHourWriteAction) {
    if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
        oneHourTestStatus = "Health Connect is unavailable; nothing changed."
        return
    }
    pendingWriteAction = action
    resolveOneHourSelection { selection ->
        if (selection == null) {
            oneHourTestStatus = if (action == OneHourWriteAction.DELETE) {
                "No previously written one-hour test selection is available; nothing deleted."
            } else {
                "Prepare the one-hour test first; nothing written."
            }
            return@resolveOneHourSelection
        }
        oneHourSelection = selection
        lifecycleScope.launch {
            val client = HealthConnectClient.getOrCreate(this@requestOneHourAction)
            val granted = runCatching { client.permissionController.getGrantedPermissions() }
                .getOrElse {
                    oneHourTestStatus = "Could not check write permissions (${it.javaClass.simpleName})"
                    return@launch
                }
            if (granted.containsAll(OneHourTestPublisher.WRITE_PERMISSIONS)) {
                executeOneHourAction(action)
            } else {
                oneHourTestStatus = "Waiting for HR and HRV write permission; nothing written yet."
                writePermissionLauncher.launch(OneHourTestPublisher.WRITE_PERMISSIONS)
            }
        }
    }
}

private fun MainActivity.executeOneHourAction(action: OneHourWriteAction) {
    val selection = oneHourSelection ?: run {
        oneHourTestStatus = "The one-hour selection is unavailable; nothing changed."
        return
    }
    oneHourTestStatus = if (action == OneHourWriteAction.WRITE) {
        "Writing exactly ${selection.allClientRecordIds.size} selected records..."
    } else {
        "Deleting only the selected deterministic client IDs..."
    }
    lifecycleScope.launch {
        val publisher = OneHourTestPublisher(HealthConnectClient.getOrCreate(this@executeOneHourAction))
        val result = runCatching {
            if (action == OneHourWriteAction.WRITE) publisher.write(selection) else publisher.delete(selection)
        }.getOrElse {
            "One-hour ${action.label} failed (${it.javaClass.simpleName}); " +
                "${if (action == OneHourWriteAction.WRITE) "transaction wrote nothing if rejected" else "no broad deletion attempted"}."
        }
        if (action == OneHourWriteAction.WRITE && result.startsWith("One-hour write passed")) {
            getSharedPreferences(HEALTH_CONNECT_TEST_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putLong(WRITTEN_TEST_HOUR_START, selection.heartRate.startUnixMillis)
                .apply()
        } else if (action == OneHourWriteAction.DELETE && result.startsWith("One-hour test deletion passed")) {
            getSharedPreferences(HEALTH_CONNECT_TEST_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .remove(WRITTEN_TEST_HOUR_START)
                .apply()
        }
        oneHourTestStatus = result
    }
}

private fun MainActivity.verifyOneHourTest() {
    if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
        oneHourVerificationStatus = "Health Connect is unavailable."
        return
    }
    oneHourVerificationStatus = "Reading back only this app's selected HR/HRV records..."
    resolveOneHourSelection { selection ->
        if (selection == null) {
            oneHourVerificationStatus = "No written one-hour selection is available to verify."
            return@resolveOneHourSelection
        }
        lifecycleScope.launch {
            val client = HealthConnectClient.getOrCreate(this@verifyOneHourTest)
            val granted = runCatching { client.permissionController.getGrantedPermissions() }
                .getOrElse {
                    oneHourVerificationStatus = "Could not check read permissions (${it.javaClass.simpleName})"
                    return@launch
                }
            if (!granted.containsAll(OneHourTestReadBackVerifier.READ_PERMISSIONS)) {
                oneHourVerificationStatus = "HR and HRV read permissions are required; no records changed."
                return@launch
            }
            oneHourVerificationStatus = runCatching {
                OneHourTestReadBackVerifier(client, packageName).verify(selection).statusText()
            }.getOrElse { "One-hour read-back failed (${it.javaClass.simpleName}); no records changed." }
        }
    }
}

private fun MainActivity.loadDailyDate(direction: Int, selectLatest: Boolean) {
    dailyPreviewStatus = "Building local-date candidates..."
    Thread {
        val zoneId = ZoneId.systemDefault()
        val result = runCatching {
            HistoryStore(this).use { store ->
                val candidates = HealthConnectCandidatePreviewBuilder.build(store.loadRawEvents(), zoneId)
                val dates = DailyPublicationSelector.availableDates(candidates, zoneId)
                if (dates.isEmpty()) return@use Triple(emptyList<LocalDate>(), null, null)
                val current = dailySelection?.localDate
                val index = if (selectLatest || current == null || current !in dates) {
                    dates.lastIndex
                } else {
                    (dates.indexOf(current) + direction).coerceIn(0, dates.lastIndex)
                }
                val selection = DailyPublicationSelector.select(candidates, dates[index], zoneId)
                val state = DailyPublicationStateStore(this).state(selection)
                Triple(dates, selection, selection.previewText(state))
            }
        }.getOrElse { Triple(emptyList(), null, "Could not build date preview (${it.javaClass.simpleName})") }
        runOnUiThread {
            availableDailyDates = result.first
            dailySelection = result.second
            dailyDateStatus = result.second?.let {
                "${it.localDate} (${availableDailyDates.indexOf(it.localDate) + 1}/${availableDailyDates.size}, ${it.zoneId.id})"
            } ?: "No date selected"
            dailyPreviewStatus = result.third ?: "No publishable local dates are available"
        }
    }.start()
}

private fun MainActivity.requestDailyAction(action: DailyPublicationAction) {
    val selection = dailySelection ?: run {
        dailyPublicationStatus = "Load and inspect a date preview first; nothing changed."
        return
    }
    if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
        dailyPublicationStatus = "Health Connect is unavailable; nothing changed."
        return
    }
    pendingDailyAction = action
    lifecycleScope.launch {
        val client = HealthConnectClient.getOrCreate(this@requestDailyAction)
        val required = dailyPermissions(action)
        val granted = runCatching { client.permissionController.getGrantedPermissions() }
            .getOrElse {
                dailyPublicationStatus = "Could not check permissions (${it.javaClass.simpleName})"
                return@launch
            }
        if (granted.containsAll(required)) {
            executeDailyAction(action)
        } else {
            dailyPublicationStatus = "Waiting for selected date permissions; nothing written yet."
            dailyPermissionLauncher.launch(required)
        }
    }
}

private fun MainActivity.executeDailyAction(action: DailyPublicationAction) {
    val selection = dailySelection ?: run {
        dailyPublicationStatus = "Selected date candidates are unavailable; nothing changed."
        return
    }
    dailyPublicationStatus = "${action.progressLabel} ${selection.localDate}..."
    lifecycleScope.launch {
        val publisher = DailyHealthConnectPublisher(
            HealthConnectClient.getOrCreate(this@executeDailyAction),
            packageName,
        )
        val stateStore = DailyPublicationStateStore(this@executeDailyAction)
        val result = runCatching {
            when (action) {
                DailyPublicationAction.PUBLISH_HR_HRV -> {
                    val verification = publisher.publishHrHrv(selection)
                    stateStore.setHrHrvVerified(selection, verification.passed)
                    verification.hrHrvStatusText(selection.localDate)
                }
                DailyPublicationAction.PUBLISH_SLEEP -> {
                    val verification = publisher.publishSleep(selection)
                    stateStore.setSleepVerified(selection, verification.passed)
                    verification.sleepStatusText(selection.localDate)
                }
                DailyPublicationAction.REPAIR_DATE -> {
                    val parts = mutableListOf<String>()
                    if (selection.hrHrvRecordCount > 0) {
                        val verification = publisher.publishHrHrv(selection)
                        stateStore.setHrHrvVerified(selection, verification.passed)
                        parts += verification.hrHrvStatusText(selection.localDate)
                    }
                    if (selection.sleep.isNotEmpty()) {
                        val verification = publisher.publishSleep(selection)
                        stateStore.setSleepVerified(selection, verification.passed)
                        parts += verification.sleepStatusText(selection.localDate)
                    }
                    parts.joinToString(" ").ifEmpty { "No candidates exist for the selected date." }
                }
                DailyPublicationAction.DELETE_DATE -> {
                    publisher.deleteDate(selection)
                    val absent = publisher.verifyDateAbsent(selection)
                    if (absent) {
                        stateStore.clear(selection.localDate)
                        "Date deletion passed for ${selection.localDate} - all ${selection.allRecordCount} " +
                            "selected app records are absent by exact client ID."
                    } else {
                        "Date deletion verification failed for ${selection.localDate}; repair/delete can be retried."
                    }
                }
            }
        }.getOrElse {
            "${action.failureLabel} (${it.javaClass.simpleName}); deterministic repair remains available."
        }
        dailyPublicationStatus = result
        dailyPreviewStatus = selection.previewText(stateStore.state(selection))
    }
}

private fun MainActivity.dailyPermissions(action: DailyPublicationAction): Set<String> = when (action) {
    DailyPublicationAction.PUBLISH_HR_HRV -> DailyHealthConnectPublisher.HR_HRV_PERMISSIONS
    DailyPublicationAction.PUBLISH_SLEEP -> DailyHealthConnectPublisher.SLEEP_PERMISSIONS
    DailyPublicationAction.REPAIR_DATE, DailyPublicationAction.DELETE_DATE ->
        DailyHealthConnectPublisher.ALL_PERMISSIONS
}

private fun MainActivity.resolveOneHourSelection(callback: (OneHourTestSelection?) -> Unit) {
    oneHourSelection?.let {
        callback(it)
        return
    }
    val storedHour = getSharedPreferences(HEALTH_CONNECT_TEST_PREFERENCES, Context.MODE_PRIVATE)
        .takeIf { it.contains(WRITTEN_TEST_HOUR_START) }
        ?.getLong(WRITTEN_TEST_HOUR_START, 0L)
    if (storedHour == null) {
        callback(null)
        return
    }
    Thread {
        val selection = runCatching {
            HistoryStore(this).use { store ->
                OneHourTestSelector.selectHour(
                    HealthConnectCandidatePreviewBuilder.build(store.loadRawEvents()),
                    storedHour,
                )
            }
        }.getOrNull()
        runOnUiThread { callback(selection) }
    }.start()
}

private fun MainActivity.requestHistoricalAudit(source: HistoricalDataSource) {
    pendingHistoricalSource = source
    if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
        setHistoricalAuditStatus(source, "Health Connect is unavailable")
        return
    }
    setHistoricalAuditStatus(source, "Checking read permissions...")
    lifecycleScope.launch {
        val granted = runCatching {
            HealthConnectClient.getOrCreate(this@requestHistoricalAudit)
                .permissionController.getGrantedPermissions()
        }.getOrElse {
            setHistoricalAuditStatus(
                source,
                "Could not check Health Connect permissions (${it.javaClass.simpleName})",
            )
            return@launch
        }
        if (granted.containsAll(HistoricalHealthConnectAudit.REQUIRED_PERMISSIONS)) {
            runHistoricalAudit(source)
        } else {
            setHistoricalAuditStatus(source, "Waiting for selected read permissions and Access past data")
            healthPermissionLauncher.launch(HistoricalHealthConnectAudit.REQUIRED_PERMISSIONS)
        }
    }
}

private fun MainActivity.runHistoricalAudit(source: HistoricalDataSource) {
    setHistoricalAuditStatus(source, "Reading only official ${source.label}-origin record structure...")
    lifecycleScope.launch {
        val result = runCatching {
            HistoricalHealthConnectAudit(
                HealthConnectClient.getOrCreate(this@runHistoricalAudit),
                source,
            )
                .read()
                .statusText()
        }.getOrElse { "Could not audit historical ${source.label} data (${it.javaClass.simpleName})" }
        setHistoricalAuditStatus(source, result)
    }
}

private fun MainActivity.setHistoricalAuditStatus(source: HistoricalDataSource, status: String) {
    when (source) {
        HistoricalDataSource.OURA -> historicalAuditStatus = status
        HistoricalDataSource.SAMSUNG_HEALTH -> samsungAuditStatus = status
    }
}

private fun MainActivity.requestPrivateStepResearchAudit() {
    requestStepReadAction(StepReadAction.PRIVATE_AUDIT)
}

private fun MainActivity.requestStepTimeWindowAudit(dateText: String, timeText: String) {
    val zoneId = ZoneId.systemDefault()
    val start = runCatching {
        LocalDateTime.of(LocalDate.parse(dateText.trim()), LocalTime.parse(timeText.trim()))
            .atZone(zoneId)
            .toInstant()
    }.getOrElse {
        stepTimeWindowStatus = "Enter a valid local date (YYYY-MM-DD) and 24-hour time (HH:MM)."
        return
    }
    pendingStepResearchWindow = StepResearchWindow(start, Duration.ofMinutes(6))
    requestStepReadAction(StepReadAction.TIME_WINDOW_AUDIT)
}

private fun MainActivity.requestLastCompletedStepWindowAudit() {
    val completed = StepTrialStore(this).lastCompleted() ?: run {
        stepTimeWindowStatus = "No timestamped completed trial is available. Complete a new trial first."
        return
    }
    pendingStepResearchWindow = StepResearchWindow(
        walkStart = java.time.Instant.ofEpochMilli(completed.startedUnixMillis),
        walkDuration = Duration.ofMillis(completed.finishedUnixMillis - completed.startedUnixMillis),
    )
    requestStepReadAction(StepReadAction.TIME_WINDOW_AUDIT)
}

private fun MainActivity.requestStepReadAction(action: StepReadAction, manualSteps: Int? = null) {
    pendingStepReadAction = action
    pendingManualStepCount = manualSteps
    if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
        setStepReadActionStatus(action, "Health Connect is unavailable; nothing changed.")
        return
    }
    setStepReadActionStatus(action, "Checking Steps read permission...")
    lifecycleScope.launch {
        val granted = runCatching {
            HealthConnectClient.getOrCreate(this@requestStepReadAction)
                .permissionController.getGrantedPermissions()
        }.getOrElse {
            setStepReadActionStatus(
                action,
                "Could not check Health Connect permissions (${it.javaClass.simpleName}); nothing changed.",
            )
            return@launch
        }
        if (granted.containsAll(PrivateStepResearchAudit.REQUIRED_PERMISSIONS)) {
            executeStepReadAction(action, manualSteps)
        } else {
            setStepReadActionStatus(action, "Waiting for Steps read permission and Access past data...")
            stepResearchPermissionLauncher.launch(PrivateStepResearchAudit.REQUIRED_PERMISSIONS)
        }
    }
}

private fun MainActivity.executeStepReadAction(action: StepReadAction, manualSteps: Int?) {
    when (action) {
        StepReadAction.PRIVATE_AUDIT -> runPrivateStepResearchAudit()
        StepReadAction.TIME_WINDOW_AUDIT -> runStepTimeWindowAudit()
        StepReadAction.START_TRIAL -> startStepTrial()
        StepReadAction.FINISH_TRIAL -> finishStepTrial()
    }
}

private fun MainActivity.setStepReadActionStatus(action: StepReadAction, status: String) {
    when (action) {
        StepReadAction.PRIVATE_AUDIT -> stepResearchStatus = status
        StepReadAction.TIME_WINDOW_AUDIT -> stepTimeWindowStatus = status
        StepReadAction.START_TRIAL, StepReadAction.FINISH_TRIAL -> stepTrialStatus = status
    }
}

private fun MainActivity.runPrivateStepResearchAudit() {
    stepResearchStatus = "Reading aggregate ring tag structure and Health Connect step sources locally..."
    lifecycleScope.launch {
        val result = runCatching {
            val events = withContext(Dispatchers.IO) {
                HistoryStore(this@runPrivateStepResearchAudit).use {
                    it.loadRawEventsForTags(RingStepInventoryBuilder.REQUIRED_TAGS)
                }
            }
            PrivateStepResearchAudit(
                HealthConnectClient.getOrCreate(this@runPrivateStepResearchAudit),
            ).read(events).statusText()
        }.getOrElse {
            "Could not complete private step-source audit (${it.javaClass.simpleName}); " +
                "nothing exported or written."
        }
        stepResearchStatus = result
    }
}

private fun MainActivity.runStepTimeWindowAudit() {
    val window = pendingStepResearchWindow ?: run {
        stepTimeWindowStatus = "No valid walk time is selected; nothing changed."
        return
    }
    stepTimeWindowStatus = "Reading the private ring and Health Connect timeline around the walk..."
    lifecycleScope.launch {
        val result = runCatching {
            val events = withContext(Dispatchers.IO) {
                HistoryStore(this@runStepTimeWindowAudit).use {
                    it.loadRawEventsForTags(setOf(0x42, 0x47, 0x50, 0x51, 0x52, 0x6b, 0x7e, 0x7f))
                }
            }
            StepTimeWindowAudit(HealthConnectClient.getOrCreate(this@runStepTimeWindowAudit))
                .read(events, window, ZoneId.systemDefault())
                .statusText()
        }.getOrElse {
            "Could not complete private step time-window audit (${it.javaClass.simpleName}); " +
                "nothing exported or written."
        }
        stepTimeWindowStatus = result
    }
}

private fun MainActivity.startStepTrial() {
    stepTrialStatus = "Capturing private aggregate baseline counters..."
    lifecycleScope.launch {
        val client = HealthConnectClient.getOrCreate(this@startStepTrial)
        val result = runCatching {
            withContext(Dispatchers.IO) {
                StepTrialReader(this@startStepTrial, client).start()
            }
        }
        result.onSuccess { baseline ->
            val status = StepTrialSummarizer.startedText(baseline)
            StepTrialStore(this@startStepTrial).save(baseline, status)
            stepTrialStatus = status
        }.onFailure {
            stepTrialStatus =
                "Could not start controlled step trial (${it.javaClass.simpleName}); nothing exported or written."
        }
    }
}

private fun MainActivity.markStepTrialWalkFinished(manualSteps: Int?) {
    if (manualSteps == null || manualSteps <= 0) {
        stepTrialStatus = "Enter the positive number of manually counted steps before marking the end."
        return
    }
    val store = StepTrialStore(this)
    val baseline = store.active() ?: run {
        stepTrialStatus = "No active step trial. Start a trial before marking the end."
        return
    }
    store.markedEnd()?.let {
        stepTrialStatus = StepTrialSummarizer.markedText(baseline, it)
        return
    }
    val marker = MarkedStepTrialEnd(java.time.Instant.now().toEpochMilli(), manualSteps)
    val status = StepTrialSummarizer.markedText(baseline, marker)
    store.markWalkFinished(marker, status)
    stepTrialStatus = status
}

private fun MainActivity.finishStepTrial() {
    val store = StepTrialStore(this)
    val baseline = store.active() ?: run {
        stepTrialStatus = "No active step trial. Start a trial before finalizing it."
        return
    }
    val marker = store.markedEnd() ?: run {
        stepTrialStatus = "Mark the walk finished before syncing and finalizing the trial."
        return
    }
    stepTrialStatus = "Reading final aggregate counters..."
    lifecycleScope.launch {
        val client = HealthConnectClient.getOrCreate(this@finishStepTrial)
        val snapshotTime = java.time.Instant.now()
        val result = runCatching {
            withContext(Dispatchers.IO) {
                StepTrialReader(this@finishStepTrial, client).snapshot(
                    java.time.Instant.ofEpochMilli(baseline.healthWindowStartUnixMillis),
                    snapshotTime,
                )
            }
        }
        result.onSuccess { snapshot ->
            val status = StepTrialSummarizer.finishedText(
                baseline = baseline,
                finishedUnixMillis = marker.finishedUnixMillis,
                manualSteps = marker.manualSteps,
                snapshot = snapshot,
            )
            store.finish(
                statusText = status,
                baseline = baseline,
                finishedUnixMillis = marker.finishedUnixMillis,
                manualSteps = marker.manualSteps,
            )
            stepTrialStatus = status
        }.onFailure {
            stepTrialStatus =
                "Could not finish controlled step trial (${it.javaClass.simpleName}); baseline retained for retry."
        }
    }
}

private fun MainActivity.requestForegroundSyncPublish() {
    if (foregroundOperationActive) {
        foregroundSyncStatus = "A foreground sync/publish operation is already running."
        return
    }
    if (bondedAssociatedDevice() == null) {
        foregroundSyncStatus = "Associate and bond the ring first; nothing changed."
        return
    }
    if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
        foregroundSyncStatus = "Health Connect is unavailable; nothing changed."
        return
    }
    if (!notificationPermissionResolvedForRun &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        foregroundSyncStatus = "Notification permission is requested for screen-off sync progress."
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return
    }
    lifecycleScope.launch {
        val client = HealthConnectClient.getOrCreate(this@requestForegroundSyncPublish)
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrElse {
            foregroundSyncStatus = "Could not check Health Connect permissions (${it.javaClass.simpleName})."
            return@launch
        }
        if (granted.containsAll(DailyHealthConnectPublisher.ALL_PERMISSIONS)) {
            startLifecycleSafeForegroundSync()
        } else {
            foregroundSyncStatus = "Waiting for HR, HRV, and sleep permissions; nothing written yet."
            foregroundPermissionLauncher.launch(DailyHealthConnectPublisher.ALL_PERMISSIONS)
        }
    }
}

private fun MainActivity.startLifecycleSafeForegroundSync() {
    notificationPermissionResolvedForRun = false
    ForegroundSyncService.start(this)
    foregroundSyncStatus = "Starting lifecycle-safe foreground sync/publish..."
}

private fun MainActivity.refreshForegroundStatus() {
    ForegroundRunStateStore(this).also {
        foregroundSyncStatus = it.currentStatusText()
        foregroundRunHistory = it.statusText()
    }
    backgroundScheduleStatus = BackgroundScheduleStateStore(this).statusText()
    refreshPrimeUiState()
}

private fun MainActivity.enqueueBackgroundTriggerTest() {
    BackgroundSyncScheduler(this).enqueueOneTimeTest()
    backgroundScheduleStatus = BackgroundScheduleStateStore(this).statusText()
    refreshPrimeUiState()
}

private fun MainActivity.enableBackgroundSync() {
    BackgroundSyncScheduler(this).enablePeriodic()
    backgroundScheduleStatus = BackgroundScheduleStateStore(this).statusText()
    refreshPrimeUiState()
}

private fun MainActivity.disableBackgroundSync() {
    BackgroundSyncScheduler(this).disablePeriodic()
    backgroundScheduleStatus = BackgroundScheduleStateStore(this).statusText()
    refreshPrimeUiState()
}

private fun MainActivity.handlePrimeUiEvent(event: PrimeUiEvent) {
    when (event) {
        PrimeUiEvent.SyncNow -> requestForegroundSyncPublish()
        PrimeUiEvent.AssociateRing -> requestAssociation()
        is PrimeUiEvent.SetBackgroundSync -> {
            if (event.enabled) enableBackgroundSync() else disableBackgroundSync()
        }
        is PrimeUiEvent.SetRingDisplayName -> {
            RingDisplayNameStore(this).save(event.name)
            refreshPrimeUiState()
        }
    }
}

private fun MainActivity.refreshHealthConnectAccess() {
    val sdkStatus = HealthConnectClient.getSdkStatus(this)
    if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
        healthConnectPermissionStateKnown = true
        healthConnectWriteReady = false
        refreshPrimeUiState()
        return
    }
    lifecycleScope.launch {
        val granted = runCatching {
            HealthConnectClient.getOrCreate(this@refreshHealthConnectAccess)
                .permissionController.getGrantedPermissions()
        }.getOrNull()
        healthConnectPermissionStateKnown = granted != null
        healthConnectWriteReady = granted?.containsAll(DailyHealthConnectPublisher.ALL_PERMISSIONS) == true
        refreshPrimeUiState()
    }
}

private fun MainActivity.refreshPrimeUiState(nowUnixMillis: Long = System.currentTimeMillis()) {
    val manager = getSystemService(CompanionDeviceManager::class.java)
    val associated = manager.myAssociations.isNotEmpty()
    val bonded = bondedAssociatedDevice() != null
    val sdkStatus = HealthConnectClient.getSdkStatus(this)
    primeUiState = PrimeUiState(
        ringDisplayName = RingDisplayNameStore(this).load(),
        keyPresent = RingKeyStore(this).hasKey(),
        associated = associated,
        bonded = bonded,
        healthConnect = when {
            sdkStatus != HealthConnectClient.SDK_AVAILABLE -> HealthConnectState.UNAVAILABLE
            healthConnectPermissionStateKnown && healthConnectWriteReady -> HealthConnectState.READY
            else -> HealthConnectState.PERMISSIONS_NEEDED
        },
        run = ForegroundRunStateStore(this).uiSnapshot(),
        schedule = BackgroundScheduleStateStore(this).uiSnapshot(),
        metrics = LatestLocalMetricsStore(this).load(),
        nowUnixMillis = nowUnixMillis,
    )
}

private fun MainActivity.beginForegroundSyncPublish() {
    if (foregroundOperationActive) return
    val device = bondedAssociatedDevice() ?: run {
        foregroundSyncStatus = "Associate and bond the ring first; nothing changed."
        return
    }
    foregroundOperationActive = true
    val stateStore = ForegroundRunStateStore(this)
    stateStore.markAttempt(System.currentTimeMillis())
    foregroundRunHistory = stateStore.statusText()
    foregroundSyncStatus = "Preparing the before-sync candidate snapshot..."
    Thread {
        val zoneId = ZoneId.systemDefault()
        val before = runCatching {
            HistoryStore(this).use { store ->
                HealthConnectCandidatePreviewBuilder.build(store.loadRawEvents(), zoneId)
            }
        }.getOrElse {
            runOnUiThread {
                finishForegroundRun(
                    ForegroundRunReport(
                        passed = false,
                        syncSessions = 0,
                        receivedEvents = 0,
                        addedEvents = 0,
                        storedEvents = null,
                        affectedDates = 0,
                        heartRateRecords = 0,
                        heartRateSamples = 0,
                        hrvRecords = 0,
                        sleepRecords = 0,
                        obsoleteRecordsDeleted = 0,
                        deferredRecentSleepRecords = 0,
                        detail = "before-sync candidate build failed (${it.javaClass.simpleName}); nothing written",
                    ),
                )
            }
            return@Thread
        }
        runOnUiThread {
            foregroundRun = ForegroundRunContext(device, before, zoneId)
            startForegroundHistorySession()
        }
    }.start()
}

private fun MainActivity.startForegroundHistorySession() {
    val run = foregroundRun ?: return
    run.syncSessions++
    foregroundSyncStatus = "Foreground ring sync session ${run.syncSessions} starting..."
    smokeTest?.close()
    smokeTest = RingConnectionSmokeTest(
        context = this,
        onStatus = { status ->
            runOnUiThread {
                historyStatus = status
                foregroundSyncStatus = "Foreground session ${run.syncSessions}: $status"
            }
        },
        onHistoryFinished = { result -> runOnUiThread { handleForegroundHistoryResult(result) } },
    ).also {
        it.startHistorySync(
            run.device,
            maxBatches = FOREGROUND_BATCHES_PER_CONNECTION,
            timeoutMs = FOREGROUND_HISTORY_TIMEOUT_MS,
        )
    }
}

private fun MainActivity.handleForegroundHistoryResult(result: HistorySyncResult) {
    val run = foregroundRun ?: return
    run.receivedEvents += result.received
    run.addedEvents += result.inserted
    if (result.storedTotal != null) run.storedEvents = result.storedTotal
    if (!result.passed) {
        if (result.retryableConnectionFailure && run.consecutiveConnectionFailures < MAX_CONNECTION_RETRIES) {
            run.consecutiveConnectionFailures++
            run.connectionRetries++
            foregroundSyncStatus = "Transient Bluetooth failure; cooling down before retry " +
                "${run.consecutiveConnectionFailures}/$MAX_CONNECTION_RETRIES..."
            window.decorView.postDelayed(::startForegroundHistorySession, CONNECTION_RETRY_DELAY_MS)
            return
        }
        finishForegroundFailure("ring sync failed; ${result.message}")
        return
    }
    run.consecutiveConnectionFailures = 0
    if (result.moreRemains) {
        if (run.syncSessions >= MAX_FOREGROUND_SYNC_SESSIONS) {
            finishForegroundFailure("history remains after the safety limit; nothing published")
        } else {
            foregroundSyncStatus = "More ring history remains; continuing automatically..."
            window.decorView.postDelayed(::startForegroundHistorySession, FOREGROUND_RECONNECT_DELAY_MS)
        }
        return
    }
    foregroundSyncStatus = "Ring history drained; rebuilding candidates and affected dates..."
    Thread {
        val after = runCatching {
            HistoryStore(this).use { store ->
                run.storedEvents = store.stats().eventCount
                HealthConnectCandidatePreviewBuilder.build(store.loadRawEvents(), run.zoneId)
            }
        }.getOrElse {
            runOnUiThread { finishForegroundFailure("after-sync candidate build failed (${it.javaClass.simpleName})") }
            return@Thread
        }
        val changedPlan = ForegroundPublicationPlanner.plan(
            before = run.beforeCandidates,
            after = after,
            zoneId = run.zoneId,
            nowUnixMillis = System.currentTimeMillis(),
        )
        val publicationState = DailyPublicationStateStore(this)
        val plan = ForegroundPublicationPlanner.includePendingPublication(
            base = changedPlan,
            currentCandidates = after,
            zoneId = run.zoneId,
            nowUnixMillis = System.currentTimeMillis(),
            state = publicationState::state,
        )
        runOnUiThread { publishForegroundPlan(after, plan) }
    }.start()
}

private fun MainActivity.publishForegroundPlan(
    after: HealthConnectCandidateSet,
    plan: ForegroundPublicationPlan,
) {
    val run = foregroundRun ?: return
    run.affectedDates = plan.affectedDateCount
    run.deferredRecentSleepRecords = plan.deferredRecentSleepRecords
    if (plan.dates.isEmpty()) {
        finishForegroundRun(run.report(passed = true, plan = plan))
        return
    }
    foregroundSyncStatus = "Publishing and exactly verifying ${plan.affectedDateCount} affected date(s)..."
    lifecycleScope.launch {
        val publisher = DailyHealthConnectPublisher(
            HealthConnectClient.getOrCreate(this@publishForegroundPlan),
            packageName,
        )
        val publicationState = DailyPublicationStateStore(this@publishForegroundPlan)
        val failure = runCatching {
            plan.dates.forEach { affected ->
                val current = affected.current
                val previouslyPublished = publicationState.clientIds(current.localDate)
                if (affected.publishHrHrv) {
                    val verification = publisher.publishHrHrv(current)
                    check(verification.passed) { "HR/HRV exact read-back mismatch" }
                    run.obsoleteRecordsDeleted += publisher.deleteObsolete(
                        previous = affected.previous,
                        current = current,
                        includeHrHrv = true,
                        includeSleep = false,
                        previouslyPublished = previouslyPublished,
                    )
                    publicationState.setHrHrvVerified(current, true)
                    run.heartRateRecords += current.heartRate.size
                    run.heartRateSamples += current.heartRateSamples
                    run.hrvRecords += current.hrv.size
                }
                if (affected.publishSleep) {
                    val verification = publisher.publishSleep(current)
                    check(verification.passed) { "sleep exact read-back mismatch" }
                    run.obsoleteRecordsDeleted += publisher.deleteObsolete(
                        previous = affected.previous,
                        current = current,
                        includeHrHrv = false,
                        includeSleep = true,
                        previouslyPublished = previouslyPublished,
                    )
                    publicationState.setSleepVerified(current, true)
                    run.sleepRecords += current.sleep.size
                }
            }
        }.exceptionOrNull()
        if (failure != null) {
            finishForegroundFailure("publication/verification failed (${failure.javaClass.simpleName}); deterministic retry is safe")
            return@launch
        }
        plan.dates.lastOrNull()?.current?.let { selection ->
            dailySelection = selection
            availableDailyDates = DailyPublicationSelector.availableDates(after, run.zoneId)
            dailyDateStatus = "${selection.localDate} (${availableDailyDates.indexOf(selection.localDate) + 1}/" +
                "${availableDailyDates.size}, ${selection.zoneId.id})"
            dailyPreviewStatus = selection.previewText(publicationState.state(selection))
        }
        finishForegroundRun(run.report(passed = true, plan = plan, detail = "exact read-back verified"))
    }
}

private fun MainActivity.finishForegroundFailure(detail: String) {
    val run = foregroundRun
    finishForegroundRun(
        run?.report(passed = false, detail = detail) ?: ForegroundRunReport(
            passed = false,
            syncSessions = 0,
            receivedEvents = 0,
            addedEvents = 0,
            storedEvents = null,
            affectedDates = 0,
            heartRateRecords = 0,
            heartRateSamples = 0,
            hrvRecords = 0,
            sleepRecords = 0,
            obsoleteRecordsDeleted = 0,
            deferredRecentSleepRecords = 0,
            detail = detail,
        ),
    )
}

private fun MainActivity.finishForegroundRun(report: ForegroundRunReport) {
    foregroundSyncStatus = report.statusText()
    val store = ForegroundRunStateStore(this)
    store.markFinished(System.currentTimeMillis(), report)
    foregroundRunHistory = store.statusText()
    foregroundOperationActive = false
    foregroundRun = null
}

private fun MainActivity.copyDiagnostics(text: String) {
    val clip = ClipData.newPlainText("Ring Health Bridge diagnostics", text)
    clip.description.extras = PersistableBundle().apply {
        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

private fun MainActivity.bondedAssociatedDevice(): BluetoothDevice? {
    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        return null
    }
    val association = getSystemService(CompanionDeviceManager::class.java)
        .myAssociations
        .maxByOrNull(AssociationInfo::getId)
    return association?.let(::bluetoothDevice)?.takeIf { it.bondState == BluetoothDevice.BOND_BONDED }
}

private fun MainActivity.bluetoothDevice(associationInfo: AssociationInfo): BluetoothDevice? {
    associationInfo.associatedDevice?.bleDevice?.device?.let { return it }
    val address = associationInfo.deviceMacAddress
        ?.toString()
        ?.uppercase(Locale.ROOT)
        ?.takeIf(BluetoothAdapter::checkBluetoothAddress)
        ?: return null
    return getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
}

private const val ASSOCIATION_REQUEST = 1001
private const val ASSOCIATION_PREFERENCES = "ring-association-v1"
private const val ASSOCIATION_ID = "association-id"
private const val HEALTH_CONNECT_TEST_PREFERENCES = "health-connect-test-v1"
private const val WRITTEN_TEST_HOUR_START = "written-hour-start"
private const val MAX_FOREGROUND_SYNC_SESSIONS = 6
private const val FOREGROUND_RECONNECT_DELAY_MS = 3_000L
private const val CONNECTION_RETRY_DELAY_MS = 8_000L
private const val MAX_CONNECTION_RETRIES = 3
private const val FOREGROUND_BATCHES_PER_CONNECTION = 80
private const val FOREGROUND_HISTORY_TIMEOUT_MS = 5L * 60L * 1_000L

internal data class ForegroundRunContext(
    val device: BluetoothDevice,
    val beforeCandidates: HealthConnectCandidateSet,
    val zoneId: ZoneId,
    var syncSessions: Int = 0,
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
    var connectionRetries: Int = 0,
    var consecutiveConnectionFailures: Int = 0,
) {
    fun report(
        passed: Boolean,
        plan: ForegroundPublicationPlan? = null,
        detail: String? = null,
    ): ForegroundRunReport = ForegroundRunReport(
        passed = passed,
        syncSessions = syncSessions,
        receivedEvents = receivedEvents,
        addedEvents = addedEvents,
        storedEvents = storedEvents,
        affectedDates = plan?.affectedDateCount ?: affectedDates,
        heartRateRecords = heartRateRecords,
        heartRateSamples = heartRateSamples,
        hrvRecords = hrvRecords,
        sleepRecords = sleepRecords,
        obsoleteRecordsDeleted = obsoleteRecordsDeleted,
        deferredRecentSleepRecords = plan?.deferredRecentSleepRecords ?: deferredRecentSleepRecords,
        connectionRetries = connectionRetries,
        detail = detail,
    )
}

internal enum class OneHourWriteAction(val label: String) {
    WRITE("write"),
    DELETE("deletion"),
}

internal enum class StepReadAction {
    PRIVATE_AUDIT,
    TIME_WINDOW_AUDIT,
    START_TRIAL,
    FINISH_TRIAL,
}

internal enum class DailyPublicationAction(
    val progressLabel: String,
    val failureLabel: String,
) {
    PUBLISH_HR_HRV("Publishing and verifying HR/HRV for", "Date HR/HRV publication failed"),
    PUBLISH_SLEEP("Publishing and verifying completed sleep for", "Date sleep publication failed"),
    REPAIR_DATE("Repairing and verifying", "Date repair failed"),
    DELETE_DATE("Deleting exact app record IDs for", "Date deletion failed"),
}
