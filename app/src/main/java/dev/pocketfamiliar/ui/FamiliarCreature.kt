package dev.pocketfamiliar.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.pocketfamiliar.simulation.Behavior
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Presentation of a derived behavior. Animation never advances or writes creature state. */
@Composable
internal fun FamiliarCreature(
    behavior: Behavior?, reaction: Long, enabled: Boolean, onPoke: () -> Unit,
    modifier: Modifier = Modifier,
    phoneReaction: PhoneReaction? = null,
) {
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    val phase = rememberIdlePhase(active = resumed && enabled, behavior = behavior, phoneReaction = phoneReaction)
    val pulse = remember { Animatable(0f) }
    var observedReaction by remember { mutableLongStateOf(reaction) }
    LaunchedEffect(reaction, resumed, behavior) {
        val newPoke = reaction != observedReaction
        observedReaction = reaction
        pulse.snapTo(0f)
        if (resumed && newPoke) {
            pulse.animateTo(1f, tween(80))
            pulse.animateTo(0f, tween(if (behavior == Behavior.RESTING) 240 else 320))
        }
    }

    val width = animateFloatAsState(
        targetValue = when (behavior) {
            Behavior.RESTING -> 1.13f
            Behavior.DROWSY -> 1.04f
            Behavior.LIVELY -> 0.98f
            else -> 1f
        },
        animationSpec = tween(350), label = "pose width",
    )
    val height = animateFloatAsState(
        targetValue = when (behavior) {
            Behavior.RESTING -> 0.78f
            Behavior.DROWSY -> 0.91f
            Behavior.LIVELY -> 1.03f
            else -> 1f
        },
        animationSpec = tween(350), label = "pose height",
    )
    val lean = animateFloatAsState(
        targetValue = when (behavior) {
            Behavior.RESTING -> -8f
            Behavior.DROWSY -> 3f
            Behavior.RESTLESS -> -2f
            else -> 0f
        },
        animationSpec = tween(350), label = "pose lean",
    )
    val body = remember { creatureOutline() }

    Box(
        modifier = modifier
            .size(240.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onPoke)
            .semantics {
                contentDescription = behavior?.let {
                    "${it.name.lowercase(Locale.ROOT)} creature. " +
                        (phoneReaction?.let { cue ->
                            if (cue == PhoneReaction.LOW_BATTERY && behavior == Behavior.RESTING) "Low-battery reminder. "
                            else "${cue.description}. "
                        } ?: "") + "Poke gently."
                } ?: "Creature loading"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            // Read animated values in the draw phase, without recomposing the screen each frame.
            val cycle = phase.value
            val breath = sin(cycle * 2 * PI).toFloat()
            val poke = pulse.value
            val direction = if (reaction % 2L == 0L) 1f else -1f
            val resting = behavior == Behavior.RESTING
            val idleLift = if (behavior == Behavior.LIVELY) abs(breath) * 2.5f else 0f
            val pokeLift = poke * when (behavior) {
                Behavior.RESTING -> 1f
                Behavior.DROWSY -> 2f
                Behavior.LIVELY -> 9f
                else -> 3f
            }
            val fidget = if (behavior == Behavior.RESTLESS) breath * 1.3f else 0f
            val nightSway = if (phoneReaction == PhoneReaction.NIGHT) breath * if (resting) 0.6f else 1.5f else 0f
            val blink = if (cycle > 0.94f) abs(cycle - 0.97f) / 0.03f else 1f
            withTransform({ scale(size.width / 220f, size.height / 220f, pivot = Offset.Zero) }) {
                drawOval(
                    color = Color(0xFF080E10).copy(alpha = 0.4f),
                    topLeft = Offset(39f + idleLift, 185f),
                    size = Size(142f - idleLift * 2, 15f),
                )
                withTransform({
                    translate(top = -idleLift - pokeLift)
                    rotate(lean.value + fidget + nightSway + direction * poke * if (resting) 1.5f else 4f, Offset(110f, 190f))
                    scale(
                        scaleX = width.value + poke * if (resting) 0.008f else 0.018f,
                        scaleY = height.value + breath * 0.012f - poke * 0.008f,
                        pivot = Offset(110f, 190f),
                    )
                }) {
                    drawPath(body, Color(if (resting) 0xFFA7B9B0 else 0xFFB7D5BA))
                    drawFace(behavior, poke, blink.coerceIn(0f, 1f), breath, phoneReaction)
                    drawCircle(Color(0xFFDCA99B).copy(alpha = 0.35f), 8f, Offset(67f, 136f))
                    drawCircle(Color(0xFFDCA99B).copy(alpha = 0.35f), 8f, Offset(154f, 136f))
                }
                drawPhoneCue(phoneReaction, breath)
                if (resting) {
                    val zColor = Color(0xFFB6D4C4).copy(alpha = 0.6f)
                    val y = 77f - breath * 1.5f
                    drawLine(zColor, Offset(167f, y), Offset(177f, y), 2f, StrokeCap.Round)
                    drawLine(zColor, Offset(177f, y), Offset(167f, y + 9f), 2f, StrokeCap.Round)
                    drawLine(zColor, Offset(167f, y + 9f), Offset(177f, y + 9f), 2f, StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
private fun rememberIdlePhase(active: Boolean, behavior: Behavior?, phoneReaction: PhoneReaction?): State<Float> {
    if (!active) return remember { mutableFloatStateOf(0f) }
    return key(behavior, phoneReaction) {
        val idle = rememberInfiniteTransition(label = "creature idle")
        idle.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(
                    durationMillis = if (phoneReaction == PhoneReaction.NIGHT) 7_000 else when (behavior) {
                        Behavior.RESTING, Behavior.DROWSY -> 6_000
                        Behavior.RESTLESS -> 4_500
                        Behavior.LIVELY -> 3_800
                        else -> 5_500
                    },
                    easing = LinearEasing,
                ),
            ),
            label = "breathing and blink phase",
        )
    }
}

private fun DrawScope.drawFace(behavior: Behavior?, poke: Float, blink: Float, breath: Float, phoneReaction: PhoneReaction?) {
    val face = Color(0xFF2D4440)
    if (behavior == Behavior.RESTING) {
        for (x in listOf(78f, 128f)) {
            drawArc(face, 0f, 180f, false, Offset(x, 114f), Size(17f, 6f), style = Stroke(3.5f, cap = StrokeCap.Round))
        }
    } else {
        val eyeHeight = if (behavior == Behavior.DROWSY) 5f + poke * 7f else 14f + poke * 3f
        val glance = if (phoneReaction == PhoneReaction.LOW_BATTERY) (breath + 1f) / 2f else 0f
        val gaze = if (glance > 0f) glance * 3f else if (behavior == Behavior.RESTLESS) breath * 3.5f else 0f
        val opening = eyeHeight * blink
        for (x in listOf(84.5f, 135.5f)) {
            if (opening < 2f) {
                drawLine(face, Offset(x - 6f, 121f), Offset(x + 6f, 121f), 3f, StrokeCap.Round)
            } else {
                drawOval(face, Offset(x - 4.5f + gaze, 121f - opening / 2 - glance * 2f), Size(9f, opening))
            }
        }
        if (behavior == Behavior.DROWSY) {
            drawLine(face, Offset(78f, 117f), Offset(93f, 117f), 2.5f, StrokeCap.Round)
            drawLine(face, Offset(128f, 117f), Offset(143f, 117f), 2.5f, StrokeCap.Round)
        } else if (behavior == Behavior.RESTLESS) {
            drawLine(face, Offset(78f, 106f), Offset(93f, 108f), 2.2f, StrokeCap.Round)
            drawLine(face, Offset(128f, 108f), Offset(143f, 106f), 2.2f, StrokeCap.Round)
        }
    }
    when {
        behavior == Behavior.LIVELY -> drawArc(
            face, 12f, 156f, false, Offset(100f, 128f), Size(20f, 14f), style = Stroke(2.6f, cap = StrokeCap.Round),
        )
        poke > 0.3f && behavior != Behavior.RESTING -> drawOval(face, Offset(108f, 134f), Size(6f, 8f))
        else -> drawLine(face.copy(alpha = 0.7f), Offset(107f, 137f), Offset(114f, 137f), 2.4f, StrokeCap.Round)
    }
}

private fun DrawScope.drawPhoneCue(reaction: PhoneReaction?, breath: Float) {
    when (reaction) {
        PhoneReaction.CHARGING -> {
            val glow = Color(0xFFE9CF9B).copy(alpha = 0.65f + breath * 0.2f)
            for ((center, radius) in listOf(Offset(32f, 93f) to 5f, Offset(186f, 72f) to 7f)) {
                drawLine(glow, center - Offset(radius, 0f), center + Offset(radius, 0f), 2f, StrokeCap.Round)
                drawLine(glow, center - Offset(0f, radius), center + Offset(0f, radius), 2f, StrokeCap.Round)
            }
        }
        PhoneReaction.LOW_BATTERY -> {
            val amber = Color(0xFFE9CF9B).copy(alpha = 0.75f)
            drawRoundRect(amber, Offset(183f, 91f), Size(20f, 12f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f), style = Stroke(1.5f))
            drawLine(amber, Offset(205f, 95f), Offset(205f, 99f), 2f, StrokeCap.Round)
            drawRect(amber, Offset(186f, 94f), Size(3f, 6f))
        }
        else -> Unit
    }
}

private fun creatureOutline() = Path().apply {
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
