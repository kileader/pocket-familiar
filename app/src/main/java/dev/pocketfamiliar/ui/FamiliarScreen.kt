package dev.pocketfamiliar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pocketfamiliar.perception.EnvironmentSnapshot
import dev.pocketfamiliar.simulation.Behavior
import dev.pocketfamiliar.simulation.SimulationUpdate
import dev.pocketfamiliar.simulation.deriveBehavior
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun FamiliarScreen(state: FamiliarUiState, onPoke: () -> Unit, onRefresh: () -> Unit) {
    val behavior = state.update?.state?.let(::deriveBehavior)
    var debugExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Pocket Familiar",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        FamiliarCreature(
            behavior = behavior,
            reaction = state.pokeReaction,
            enabled = state.update != null,
            onPoke = onPoke,
        )
        Spacer(Modifier.height(12.dp))
        if (behavior != null) {
            Text(behavior.label(), style = MaterialTheme.typography.headlineSmall)
        } else if (state.isLoading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.height(12.dp))
            Text("Loading your familiar…", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        state.environment?.let { EnvironmentContext(it) }
        if (state.update != null) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Tap gently to poke.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.error?.let { error ->
            Spacer(Modifier.height(24.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    TextButton(onClick = onRefresh) { Text("Retry loading") }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        TextButton(onClick = { debugExpanded = !debugExpanded }) {
            Text(if (debugExpanded) "Hide developer details" else "Show developer details")
        }
        if (debugExpanded) {
            state.update?.let { DebugPanel(it, state.environment) }
                ?: Text("No saved state has been loaded yet.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EnvironmentContext(environment: EnvironmentSnapshot) {
    val timeOfDay = environment.timeOfDay.name.lowercase(Locale.ROOT)
    val localTime = environment.localTime.format(DateTimeFormatter.ofPattern("h:mm a"))
    Text(
        text = "It’s $timeOfDay · $localTime",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    val batteryParts = listOfNotNull(
        environment.batteryPercent?.let { "Phone battery $it%" },
        when (environment.isCharging) {
            true -> "Charging"
            false -> "On battery"
            null -> null
        },
    )
    if (batteryParts.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = batteryParts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
            environment?.let { DebugValue("Environment observed at", it.observedAtMillis.timestamp()) }
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

private fun Behavior.label(): String = name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

private fun Float.decimal(): String = String.format(Locale.ROOT, "%.2f", this)

private fun Long.timestamp(): String = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss.SSS z")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(this))
