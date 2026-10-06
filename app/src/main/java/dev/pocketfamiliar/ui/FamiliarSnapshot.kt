package dev.pocketfamiliar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketfamiliar.perception.EnvironmentSnapshot
import dev.pocketfamiliar.perception.TimeOfDay
import dev.pocketfamiliar.simulation.Behavior
import dev.pocketfamiliar.simulation.deriveBehavior
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SnapshotInk = Color(0xFFE6EEE9)
private val SnapshotMuted = Color(0xFFABBCB6)
private val SnapshotMint = Color(0xFFB6D4C4)
private val SnapshotLine = Color(0xFF344745)

/** A read-only composition of canon, observed context, and the current Listen response. */
@Composable
internal fun FamiliarSnapshot(state: FamiliarUiState, onPoke: () -> Unit, onListen: () -> Unit) {
    val creature = state.update?.state
    val behavior = creature?.let(::deriveBehavior)
    val phoneReaction = phoneReaction(state.environment)
    val uriHandler = LocalUriHandler.current
    val shape = RoundedCornerShape(32.dp)

    Surface(
        modifier = Modifier.fillMaxWidth().border(1.dp, SnapshotLine, shape),
        shape = shape,
        color = Color(0xFF1C292B),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xFF263B3A), Color(0xFF1C292B)))),
                contentAlignment = Alignment.Center,
            ) {
                SnapshotBackdrop(state.environment?.timeOfDay, Modifier.fillMaxWidth().height(260.dp))
                if (creature != null) {
                    FamiliarCreature(behavior, state.pokeReaction, true, onPoke, Modifier.size(260.dp), phoneReaction)
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (state.isLoading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (state.isLoading) "Loading your familiar…" else "Your familiar is waiting to be loaded.",
                            color = SnapshotMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                }
            }
            behavior?.let {
                Text(it.label(), style = MaterialTheme.typography.headlineMedium, color = SnapshotInk,
                    fontFamily = FontFamily.Serif)
                Text(it.poseDescription(), style = MaterialTheme.typography.bodyMedium, color = SnapshotMuted,
                    modifier = Modifier.padding(top = 4.dp, start = 20.dp, end = 20.dp), textAlign = TextAlign.Center)
                phoneReaction?.let { reaction ->
                    Text(reaction.captionFor(behavior), style = MaterialTheme.typography.bodySmall, color = SnapshotMint,
                        modifier = Modifier.padding(top = 8.dp, start = 20.dp, end = 20.dp), textAlign = TextAlign.Center)
                }
                Text("Tap gently to poke", style = MaterialTheme.typography.labelSmall, color = SnapshotMuted,
                    modifier = Modifier.padding(top = 12.dp))
            }
            SnapshotContext(state.environment)
            if (creature != null) {
                Surface(
                    color = Color(0xFF243335),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("A LITTLE THOUGHT", modifier = Modifier.weight(1f), color = SnapshotMint,
                                style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp)
                            TextButton(onClick = onListen, enabled = !state.isThinking && !state.isLoading) {
                                Text(if (state.isThinking) "Listening…" else "Listen")
                            }
                        }
                        Text(
                            state.thought ?: when {
                                state.isThinking -> "A thought is taking shape…"
                                !state.brainConfigured -> "A quiet moment. Set up the voice service in developer details to listen."
                                else -> "A quiet moment. Tap Listen to hear what’s on its mind."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            // Keep the whole existing discovery readable, including facts and attribution.
                            color = if (state.thought != null) SnapshotInk else SnapshotMuted,
                        )
                        state.thoughtSource?.let { source ->
                            TextButton(onClick = { runCatching { uriHandler.openUri(source.url) } }) {
                                Text("Source: ${source.title}", style = MaterialTheme.typography.bodySmall,
                                    color = SnapshotMint)
                            }
                        }
                        state.voiceError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val since = DateTimeFormatter.ofPattern("MMM d, yyyy")
                        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(creature.createdAt))
                    Text("Together since $since", color = SnapshotMuted, style = MaterialTheme.typography.bodySmall)
                    Text(when (creature.interactionCount) {
                        0L -> "Your first gentle poke is still to come"
                        1L -> "1 gentle poke remembered"
                        else -> "${creature.interactionCount} gentle pokes remembered"
                    }, color = SnapshotMuted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnapshotContext(environment: EnvironmentSnapshot?) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (environment == null) {
            ContextCue("Phone context unavailable")
        } else {
            val period = environment.timeOfDay.name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
            ContextCue("$period · ${environment.localTime.format(DateTimeFormatter.ofPattern("h:mm a"))}")
            val battery = environment.batteryPercent?.let { "Battery $it%" } ?: "Battery unknown"
            ContextCue(listOfNotNull(battery, when (environment.isCharging) {
                true -> "charging"
                false -> "unplugged"
                null -> null
            }).joinToString(" · "))
        }
    }
}

@Composable
private fun ContextCue(text: String) {
    Text(text, modifier = Modifier.background(Color(0xFF2A3B3C), RoundedCornerShape(50))
        .padding(horizontal = 12.dp, vertical = 8.dp), color = SnapshotInk,
        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Normal)
}

/** Decorative light follows the already observed time of day; it is not a new environment input. */
@Composable
private fun SnapshotBackdrop(timeOfDay: TimeOfDay?, modifier: Modifier) {
    Canvas(modifier) {
        val night = timeOfDay == TimeOfDay.NIGHT || timeOfDay == TimeOfDay.EVENING
        val glow = when {
            timeOfDay == null -> SnapshotMint
            night -> Color(0xFF96BCCC)
            else -> Color(0xFFE9CF9B)
        }
        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.13f), Color.Transparent),
            center = Offset(size.width * 0.5f, size.height * 0.52f), radius = size.width * 0.48f),
            radius = size.width * 0.48f, center = Offset(size.width * 0.5f, size.height * 0.52f))
        drawOval(SnapshotMint.copy(alpha = 0.12f), topLeft = Offset(size.width * 0.12f, size.height * 0.82f),
            size = Size(size.width * 0.76f, size.height * 0.13f), style = Stroke(1.dp.toPx()))
        // Unknown time gets only the neutral halo, rather than an invented sun or moon.
        if (timeOfDay != null) {
            val light = Offset(size.width * 0.84f, size.height * 0.17f)
            drawCircle(glow.copy(alpha = 0.8f), 11.dp.toPx(), light)
            if (night) drawCircle(Color(0xFF263B3A), 10.dp.toPx(), light + Offset(5.dp.toPx(), -4.dp.toPx()))
            for ((x, y) in listOf(0.16f to 0.29f, 0.77f to 0.46f, 0.23f to 0.13f)) {
                drawCircle(glow.copy(alpha = 0.45f), 1.5.dp.toPx(), Offset(size.width * x, size.height * y))
            }
        }
    }
}

internal fun Behavior.label(): String = name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

private fun Behavior.poseDescription(): String = when (this) {
    Behavior.RESTING -> "Curled up. Letting the world wait."
    Behavior.DROWSY -> "Heavy eyelids. A slower kind of company."
    Behavior.RESTLESS -> "A little fidgety. Looking this way and that."
    Behavior.WATCHFUL -> "Bright eyes. Taking it all in."
    Behavior.LIVELY -> "A small smile. A little bounce."
}
