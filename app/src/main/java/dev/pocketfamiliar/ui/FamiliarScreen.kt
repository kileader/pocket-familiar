package dev.pocketfamiliar.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
private fun FamiliarCreature(behavior: Behavior?, reaction: Long, enabled: Boolean, onPoke: () -> Unit) {
    val twitch = remember { Animatable(0f) }
    var observedReaction by remember { mutableStateOf(reaction) }
    LaunchedEffect(reaction) {
        if (reaction != observedReaction) {
            observedReaction = reaction
            twitch.snapTo(0f)
            twitch.animateTo(1f, tween(70))
            twitch.animateTo(0f, tween(130))
        }
    }
    val resting = behavior == Behavior.RESTING
    val sleepy = resting || behavior == Behavior.DROWSY

    Box(
        modifier = Modifier
            .size(240.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onPoke)
            .semantics {
                contentDescription = if (behavior == null) {
                    "Creature loading"
                } else {
                    "${behavior.label()} creature. Poke gently."
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .size(220.dp)
                .graphicsLayer {
                    rotationZ = twitch.value * 3f
                    translationY = -twitch.value * 3.dp.toPx()
                    scaleX = 1f + twitch.value * 0.02f
                    scaleY = 1f - twitch.value * 0.01f
                },
        ) {
            withTransform({ scale(size.width / 220f, size.height / 220f, pivot = Offset.Zero) }) {
                drawOval(
                    color = Color(0xFF080E10).copy(alpha = 0.4f),
                    topLeft = Offset(39f, 185f),
                    size = Size(142f, 15f),
                )
                val body = Path().apply {
                    moveTo(37f, 158f)
                    cubicTo(29f, 129f, 36f, 94f, 58f, 75f)
                    cubicTo(54f, 57f, 59f, 44f, 65f, 43f)
                    cubicTo(71f, 42f, 81f, 53f, 87f, 61f)
                    cubicTo(100f, 57f, 119f, 57f, 132f, 61f)
                    cubicTo(138f, 53f, 149f, 42f, 156f, 44f)
                    cubicTo(162f, 46f, 165f, 61f, 160f, 78f)
                    cubicTo(181f, 102f, 190f, 135f, 180f, 162f)
                    cubicTo(173f, 186f, 144f, 191f, 111f, 190f)
                    cubicTo(76f, 191f, 45f, 184f, 37f, 158f)
                    close()
                }
                drawPath(body, Color(if (resting) 0xFFA7B9B0 else 0xFFB7D5BA))
                val face = Color(0xFF2D4440)
                if (sleepy) {
                    drawLine(face, Offset(79f, 122f), Offset(93f, 122f), 3.5f, StrokeCap.Round)
                    drawLine(face, Offset(128f, 122f), Offset(142f, 122f), 3.5f, StrokeCap.Round)
                } else {
                    drawOval(face, Offset(80f, 113f), Size(9f, 14f))
                    drawOval(face, Offset(131f, 113f), Size(9f, 14f))
                }
                drawLine(face.copy(alpha = 0.7f), Offset(107f, 137f), Offset(114f, 137f), 2.4f, StrokeCap.Round)
                drawCircle(Color(0xFFDCA99B).copy(alpha = 0.35f), 8f, Offset(67f, 136f))
                drawCircle(Color(0xFFDCA99B).copy(alpha = 0.35f), 8f, Offset(154f, 136f))
            }
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
