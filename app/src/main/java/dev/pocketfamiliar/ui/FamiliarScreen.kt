package dev.pocketfamiliar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pocketfamiliar.perception.EnvironmentSnapshot
import dev.pocketfamiliar.simulation.SimulationUpdate
import dev.pocketfamiliar.simulation.deriveBehavior
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun FamiliarScreen(
    state: FamiliarUiState,
    onPoke: () -> Unit,
    onRefresh: () -> Unit,
    onListen: () -> Unit,
    onSaveBrainSettings: (String, String) -> Unit,
) {
    var debugExpanded by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "POCKET FAMILIAR",
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 3.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Familiar Snapshot", style = MaterialTheme.typography.headlineLarge,
                fontFamily = FontFamily.Serif, modifier = Modifier.padding(top = 6.dp), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            FamiliarSnapshot(state, onPoke, onListen)
            state.error?.let { error ->
                Spacer(Modifier.height(24.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        TextButton(onClick = onRefresh) { Text("Retry loading") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { debugExpanded = !debugExpanded }) {
                Text(if (debugExpanded) "Hide developer details" else "Show developer details")
            }
            if (debugExpanded) {
                state.update?.let { DebugPanel(it, state.environment) }
                    ?: Text("No saved state has been loaded yet.", style = MaterialTheme.typography.bodySmall)
                AppObservationSettings(onChanged = onRefresh)
                VoiceSettings(state.brainConfigured, onSaveBrainSettings)
            }
        }
    }
}

@Composable
private fun VoiceSettings(configured: Boolean, onSave: (String, String) -> Unit) {
    var endpoint by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (configured) "Voice service configured" else "Voice service setup", style = MaterialTheme.typography.titleSmall)
        Text("Listen sends creature state and enabled phone observations to your backend and OpenAI. " +
            "Only the last eight discovery IDs are saved locally and sent to your backend to reduce repeats; conversations are not saved.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = endpoint, onValueChange = { endpoint = it },
            label = { Text("Backend URL (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = token, onValueChange = { token = it },
            label = { Text("Backend access token · not an OpenAI key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { onSave(endpoint, token); token = "" }, enabled = endpoint.isNotBlank() && token.isNotBlank()) {
            Text("Save voice settings")
        }
    }
}

@Composable
private fun DebugPanel(update: SimulationUpdate, environment: EnvironmentSnapshot?) {
    val creature = update.state
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DebugValue("Energy", creature.energy.decimal())
            DebugValue("Stimulation", creature.stimulation.decimal())
            DebugValue("Mode", creature.mode.name)
            DebugValue("Curiosity", creature.curiosity.decimal())
            DebugValue("Derived behavior", deriveBehavior(creature).name)
            DebugValue("Created at", creature.createdAt.timestamp())
            DebugValue("Last updated at", creature.lastUpdatedAt.timestamp())
            DebugValue("Last interaction at", creature.lastInteractionAt?.timestamp() ?: "None yet")
            DebugValue("Interaction count", creature.interactionCount.toString())
            DebugValue(
                "Elapsed in latest update",
                "${update.elapsedMillis} ms (${String.format(Locale.ROOT, "%.3f", update.elapsedMillis / 3_600_000.0)} h)",
            )
            environment?.let {
                DebugValue("Environment observed at", it.observedAtMillis.timestamp())
                DebugValue("Selected app observations · past hour", it.appUsage?.apps?.let { apps ->
                    if (apps.isEmpty()) "No recent selected app use observed"
                    else apps.joinToString("\n") { app -> "${app.appName}: about ${app.approximateMinutes} min" }
                } ?: "Off or unavailable")
            }
        }
    }
}

@Composable
private fun DebugValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private fun Float.decimal(): String = String.format(Locale.ROOT, "%.2f", this)

private fun Long.timestamp(): String = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss.SSS z")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(this))
