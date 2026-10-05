package dev.local.ourahealthbridge.ui

import android.text.format.DateUtils
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.local.ourahealthbridge.BackgroundScheduleUiSnapshot
import dev.local.ourahealthbridge.PresenceObservationState
import dev.local.ourahealthbridge.analysis.LatestLocalMetrics
import dev.local.ourahealthbridge.healthconnect.ForegroundRunOutcome
import dev.local.ourahealthbridge.healthconnect.ForegroundRunUiSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

enum class PrimeDestination { HOME, SETTINGS, ADVANCED_LEGACY }
internal fun backDestination(destination: PrimeDestination): PrimeDestination? = when (destination) {
    PrimeDestination.ADVANCED_LEGACY -> PrimeDestination.SETTINGS
    PrimeDestination.SETTINGS -> PrimeDestination.HOME
    PrimeDestination.HOME -> null
}
enum class HealthConnectState { READY, PERMISSIONS_NEEDED, UNAVAILABLE }
enum class HeartRateFreshness { RECENT, STALE, UNAVAILABLE }

data class PrimeUiState(
    val ringDisplayName: String = "Oura Ring",
    val keyPresent: Boolean = false,
    val associated: Boolean = false,
    val bonded: Boolean = false,
    val healthConnect: HealthConnectState = HealthConnectState.UNAVAILABLE,
    val run: ForegroundRunUiSnapshot? = null,
    val schedule: BackgroundScheduleUiSnapshot? = null,
    val metrics: LatestLocalMetrics = LatestLocalMetrics(null, null, null, null, null, null),
    val nowUnixMillis: Long = System.currentTimeMillis(),
)

sealed interface PrimeUiEvent {
    data object SyncNow : PrimeUiEvent
    data object AssociateRing : PrimeUiEvent
    data class SetBackgroundSync(val enabled: Boolean) : PrimeUiEvent
    data class SetRingDisplayName(val name: String) : PrimeUiEvent
}

data class HeartRatePresentation(
    val freshness: HeartRateFreshness,
    val bpm: Int?,
    val measuredUnixMillis: Long?,
)

fun heartRatePresentation(
    metrics: LatestLocalMetrics,
    nowUnixMillis: Long,
): HeartRatePresentation {
    val bpm = metrics.heartRateBpm
    val measured = metrics.heartRateUnixMillis
    if (bpm == null || measured == null) {
        return HeartRatePresentation(HeartRateFreshness.UNAVAILABLE, null, null)
    }
    val age = nowUnixMillis - measured
    val freshness = if (age in 0..HEART_RATE_RECENT_MILLIS) {
        HeartRateFreshness.RECENT
    } else {
        HeartRateFreshness.STALE
    }
    return HeartRatePresentation(freshness, bpm.roundToInt(), measured)
}

@Composable
fun PrimeBridgeApp(
    state: PrimeUiState,
    onEvent: (PrimeUiEvent) -> Unit,
    legacyContent: @Composable () -> Unit,
) {
    var destinationName by rememberSaveable { mutableStateOf(PrimeDestination.HOME.name) }
    var displayNow by remember(state.nowUnixMillis) { mutableLongStateOf(state.nowUnixMillis) }
    LaunchedEffect(state.nowUnixMillis) {
        while (true) {
            displayNow = System.currentTimeMillis()
            delay(60_000L)
        }
    }
    val displayState = state.copy(nowUnixMillis = displayNow)
    val destination = runCatching { PrimeDestination.valueOf(destinationName) }
        .getOrDefault(PrimeDestination.HOME)
    BackHandler(enabled = destination != PrimeDestination.HOME) {
        destinationName = backDestination(destination)?.name ?: PrimeDestination.HOME.name
    }
    BridgeTheme {
        when (destination) {
            PrimeDestination.HOME -> HomeScreen(
                state = displayState,
                onSync = { onEvent(PrimeUiEvent.SyncNow) },
                onSettings = { destinationName = PrimeDestination.SETTINGS.name },
            )
            PrimeDestination.SETTINGS -> SettingsScreen(
                state = displayState,
                onBack = { destinationName = PrimeDestination.HOME.name },
                onAdvanced = { destinationName = PrimeDestination.ADVANCED_LEGACY.name },
                onEvent = onEvent,
            )
            PrimeDestination.ADVANCED_LEGACY -> AdvancedLegacyScreen(
                onBack = { destinationName = PrimeDestination.SETTINGS.name },
                legacyContent = legacyContent,
            )
        }
    }
}

@Composable
fun BridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Lime,
            onPrimary = Color.Black,
            secondary = Teal,
            background = Color.Black,
            onBackground = TextPrimary,
            surface = SurfaceDark,
            onSurface = TextPrimary,
            surfaceVariant = SurfaceRaised,
            onSurfaceVariant = TextSecondary,
            error = ErrorRed,
        ),
        content = content,
    )
}

@Composable
private fun HomeScreen(
    state: PrimeUiState,
    onSync: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(containerColor = Color.Black) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("OURA HEALTH BRIDGE", color = Lime, style = MaterialTheme.typography.labelLarge)
                    Text("Direct. Private. Local.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onSettings) { Text("Settings") }
            }

            Column(modifier = Modifier.semantics { heading() }) {
                Text(state.ringDisplayName, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                Text(ringStatusText(state), color = TextSecondary)
            }

            val largeText = LocalDensity.current.fontScale >= 1.5f
            val batteryCard: @Composable (Modifier) -> Unit = { modifier ->
                StatusCard(
                    modifier = modifier,
                    label = "Ring battery",
                    value = state.run?.ringBatteryPercent?.let { "$it%" } ?: "Unknown",
                    detail = batteryDetail(state),
                    accent = batteryColor(state.run?.ringBatteryPercent),
                )
            }
            val healthCard: @Composable (Modifier) -> Unit = { modifier ->
                StatusCard(
                    modifier = modifier,
                    label = "Health Connect",
                    value = when (state.healthConnect) {
                        HealthConnectState.READY -> "Ready"
                        HealthConnectState.PERMISSIONS_NEEDED -> "Needs access"
                        HealthConnectState.UNAVAILABLE -> "Unavailable"
                    },
                    detail = when (state.healthConnect) {
                        HealthConnectState.READY -> "Exact verification enabled"
                        HealthConnectState.PERMISSIONS_NEEDED -> "Sync now will request access"
                        HealthConnectState.UNAVAILABLE -> "Provider unavailable"
                    },
                    accent = if (state.healthConnect == HealthConnectState.READY) Teal else Amber,
                )
            }
            if (largeText) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    batteryCard(Modifier.fillMaxWidth())
                    healthCard(Modifier.fillMaxWidth())
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    batteryCard(Modifier.weight(1f))
                    healthCard(Modifier.weight(1f))
                }
            }

            HeartRateCard(state)

            val running = state.run?.outcome == ForegroundRunOutcome.RUNNING
            Button(
                onClick = onSync,
                enabled = !running && state.keyPresent && state.bonded &&
                    state.healthConnect != HealthConnectState.UNAVAILABLE,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Color.Black),
            ) {
                Text(if (running) "Syncing…" else "Sync now", fontWeight = FontWeight.Bold)
            }

            SyncResultCard(state.run)
            ScheduleCard(state.schedule)

            Spacer(Modifier.height(8.dp))
            Text(
                "Raw history stays on this phone. No Oura Cloud or account.",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatusCard(
    modifier: Modifier,
    label: String,
    value: String,
    detail: String,
    accent: Color,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = TextSecondary, style = MaterialTheme.typography.labelLarge)
            Text(value, color = accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(detail, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HeartRateCard(state: PrimeUiState) {
    val presentation = heartRatePresentation(state.metrics, state.nowUnixMillis)
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceRaised)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("HEART RATE", color = TextSecondary, style = MaterialTheme.typography.labelLarge)
            Text(
                presentation.bpm?.let { "$it bpm" } ?: "Unavailable",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                when (presentation.freshness) {
                    HeartRateFreshness.RECENT -> "Measured ${relativeTime(presentation.measuredUnixMillis, state.nowUnixMillis)}"
                    HeartRateFreshness.STALE -> "Last measured ${relativeTime(presentation.measuredUnixMillis, state.nowUnixMillis)}"
                    HeartRateFreshness.UNAVAILABLE -> "No validated local measurement yet"
                },
                color = if (presentation.freshness == HeartRateFreshness.STALE) Amber else TextSecondary,
            )
            presentation.measuredUnixMillis?.let {
                Text(formatTime(it), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SyncResultCard(run: ForegroundRunUiSnapshot?) {
    val outcome = run?.outcome ?: ForegroundRunOutcome.NEVER
    val (title, detail, accent) = when (outcome) {
        ForegroundRunOutcome.RUNNING -> Triple("Sync in progress", run?.currentStage ?: "Preparing…", Lime)
        ForegroundRunOutcome.PASSED if run?.hasTypedSummary != true -> Triple(
            "Last sync passed",
            "Detailed result retained in Advanced",
            Teal,
        )
        ForegroundRunOutcome.PASSED -> Triple(
            if ((run?.affectedDates ?: 0) > 0) "${run?.affectedDates} date(s) updated" else "Everything is up to date",
            "${run?.heartRateSamples ?: 0} HR samples • ${run?.hrvRecords ?: 0} HRV • ${run?.sleepRecords ?: 0} sleep",
            Teal,
        )
        ForegroundRunOutcome.DEFERRED -> Triple("Ring unavailable", "Waiting for return-to-range or delayed retry", Amber)
        ForegroundRunOutcome.SKIPPED -> Triple("Automatic sync skipped", run?.detail ?: "A safety policy prevented this run", Amber)
        ForegroundRunOutcome.FAILED -> Triple("Sync needs attention", run?.detail ?: "Open Advanced for technical details", ErrorRed)
        ForegroundRunOutcome.UNKNOWN -> Triple("Previous result available", "Open Advanced for the retained technical result", TextSecondary)
        ForegroundRunOutcome.NEVER -> Triple("Ready to sync", "No completed run recorded yet", TextSecondary)
    }
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, color = TextSecondary)
            run?.lastFinishedMillis?.let { Text("Finished ${relativeTime(it)}", color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ScheduleCard(schedule: BackgroundScheduleUiSnapshot?) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                if (schedule?.enabled == true) "Background sync on" else "Background sync off",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (schedule?.enabled == true) "About every 3 hours; Android may delay the window" else "Enable it in Settings",
                color = TextSecondary,
            )
            if (schedule?.enabled == true) {
                Text(
                    when (schedule.presenceObservation) {
                        PresenceObservationState.ENABLED -> "Return-to-range recovery enabled"
                        PresenceObservationState.UNAVAILABLE -> "Return-to-range recovery unavailable"
                        PresenceObservationState.UNKNOWN -> "Return-to-range registration pending"
                    },
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                schedule.nextNominalDispatchMillis?.let {
                    Text("Next nominal window ${formatTime(it)}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: PrimeUiState,
    onBack: () -> Unit,
    onAdvanced: () -> Unit,
    onEvent: (PrimeUiEvent) -> Unit,
) {
    var editingName by remember { mutableStateOf(false) }
    var draftName by remember(state.ringDisplayName) { mutableStateOf(state.ringDisplayName) }
    Scaffold(containerColor = Color.Black) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Home") }
            Text("Settings", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })

            SettingsCard("Ring", state.ringDisplayName, ringStatusText(state)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editingName = true }) { Text("Rename") }
                    OutlinedButton(onClick = { onEvent(PrimeUiEvent.AssociateRing) }) { Text("Associate") }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Background sync", style = MaterialTheme.typography.titleMedium)
                        Text("Periodic, return-to-range, and delayed recovery", color = TextSecondary)
                    }
                    Switch(
                        checked = state.schedule?.enabled == true,
                        onCheckedChange = { onEvent(PrimeUiEvent.SetBackgroundSync(it)) },
                    )
                }
            }

            SettingsCard(
                "Health Connect",
                when (state.healthConnect) {
                    HealthConnectState.READY -> "Ready"
                    HealthConnectState.PERMISSIONS_NEEDED -> "Permissions needed"
                    HealthConnectState.UNAVAILABLE -> "Unavailable"
                },
                "HR, HRV, and completed sleep duration",
            )

            Card(colors = CardDefaults.cardColors(containerColor = SurfaceRaised)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Advanced", style = MaterialTheme.typography.titleMedium)
                    Text("Protocol tools, previews, audits, exact tests, and diagnostics", color = TextSecondary)
                    Button(onClick = onAdvanced) { Text("Open Advanced (legacy)") }
                }
            }
        }
    }
    if (editingName) {
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Ring display name") },
            text = {
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { if (it.length <= 40) draftName = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draftName.isNotBlank(),
                    onClick = {
                        onEvent(PrimeUiEvent.SetRingDisplayName(draftName.trim()))
                        editingName = false
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    value: String,
    detail: String,
    actions: (@Composable () -> Unit)? = null,
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(value, color = Lime, style = MaterialTheme.typography.titleLarge)
            Text(detail, color = TextSecondary)
            actions?.invoke()
        }
    }
}

@Composable
private fun AdvancedLegacyScreen(onBack: () -> Unit, legacyContent: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().background(SurfaceDark).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack) { Text("← Settings") }
            Column {
                Text("Advanced", fontWeight = FontWeight.SemiBold)
                Text("Local diagnostic tools", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        Box(Modifier.fillMaxSize()) { legacyContent() }
    }
}

private fun ringStatusText(state: PrimeUiState): String = when {
    state.associated && state.bonded -> "Associated and bonded"
    state.associated -> "Associated; Bluetooth bond needs attention"
    !state.keyPresent -> "Secure ring key not imported"
    else -> "Not associated"
}

private fun batteryDetail(state: PrimeUiState): String = state.run?.ringBatteryMeasuredMillis
    ?.let { "Measured ${relativeTime(it, state.nowUnixMillis)}" }
    ?: "Available after a ring sync"

private fun batteryColor(percent: Int?): Color = when {
    percent == null -> TextSecondary
    percent < 10 -> ErrorRed
    percent < 20 -> Amber
    else -> Teal
}

private fun relativeTime(time: Long?, now: Long = System.currentTimeMillis()): String = time?.let {
    DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString().lowercase()
} ?: "unknown"

private fun formatTime(time: Long): String = Instant.ofEpochMilli(time)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))

private const val HEART_RATE_RECENT_MILLIS = 30L * 60L * 1_000L
private val Lime = Color(0xFFB8F34A)
private val Teal = Color(0xFF66D9C4)
private val Amber = Color(0xFFFFC857)
private val ErrorRed = Color(0xFFFF7B72)
private val SurfaceDark = Color(0xFF121514)
private val SurfaceRaised = Color(0xFF1B201E)
private val TextPrimary = Color(0xFFF2F5F3)
private val TextSecondary = Color(0xFFAAB2AE)

class RingDisplayNameStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): String = preferences.getString(DISPLAY_NAME, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "Oura Ring"

    fun save(name: String) {
        val normalized = name.trim().take(40)
        if (normalized.isNotEmpty()) preferences.edit().putString(DISPLAY_NAME, normalized).apply()
    }

    private companion object {
        const val PREFERENCES = "ring-display-name-v1"
        const val DISPLAY_NAME = "display-name"
    }
}
