package dev.pocketfamiliar.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.pocketfamiliar.MainActivity
import dev.pocketfamiliar.brain.BrainResponse
import dev.pocketfamiliar.brain.DiscoverySource
import dev.pocketfamiliar.perception.EnvironmentSnapshot
import dev.pocketfamiliar.perception.TimeOfDay
import dev.pocketfamiliar.simulation.Behavior
import dev.pocketfamiliar.simulation.CreatureMode
import dev.pocketfamiliar.simulation.CreatureState
import dev.pocketfamiliar.simulation.SimulationUpdate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic screenshots and accessibility checks; no seeded state is written to Room. */
@RunWith(AndroidJUnit4::class)
class SnapshotPresentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val pokes = AtomicInteger()
    private val listens = AtomicInteger()

    @Test fun snapshotShowsEveryPoseAndPreservesActions() {
        ActivityScenario.launch(MainActivity::class.java).use {
            for (behavior in Behavior.entries) {
                render(fixture(behavior))
                assertVisible(behavior.label())
                assertVisible("Evening · 7:42 PM")
                assertVisible("Battery 82% · charging")
                capture("snapshot-${behavior.name.lowercase()}")
            }
            // Return to the representative screenshot before exercising the existing callbacks.
            render(fixture(Behavior.WATCHFUL))
            assertVisible("18 gentle pokes remembered")
            click(find("Listen"))
            assertEquals(1, listens.get())
            val creature = nodes().first { it.contentDescription?.contains("watchful creature") == true }
            click(creature)
            assertEquals(1, pokes.get())
        }
    }

    @Test fun narrowLargeTextKeepsFullDiscoverySourceAndDetailsReachable() {
        val response = BrainResponse(
            "A thought can be a little longer, and it should still have room here. " +
                "The creature keeps watching while the phone rests on its charger. " +
                "There is no need to squeeze away the words just to make a picture. " +
                "Even the very last sentence stays readable.",
            "snapshot-test", DiscoverySource("Test source", "https://example.com/"),
        )
        ActivityScenario.launch(MainActivity::class.java).use {
            render(fixture(Behavior.RESTING).copy(thought = response.text, thoughtSource = response.source),
                narrow = true)
            capture("snapshot-narrow-top")
            scrollUntil("Source: Test source")
            assertEquals(response.text, find("Even the very last sentence stays readable.").text.toString())
            capture("snapshot-narrow-thought")
            scrollUntil("Show developer details")
            click(find("Show developer details"))
            find("Hide developer details") // Wait for expansion before the next swipe.
            scrollUntil("Energy")
            assertVisible("Energy")
        }
    }

    @Test fun absentContextAndVoiceFailureStayHonestAndUsable() {
        ActivityScenario.launch(MainActivity::class.java).use {
            render(fixture(Behavior.DROWSY).copy(environment = null, thought = null,
                voiceError = "The voice service is unavailable. Try again later."))
            assertVisible("Phone context unavailable")
            assertVisible("The voice service is unavailable. Try again later.")
            click(find("Listen"))
            assertEquals(1, listens.get())
            capture("snapshot-unavailable")
            render(fixture(Behavior.DROWSY).copy(thought = null, isThinking = true))
            assertVisible("A thought is taking shape…")
            val button = generateSequence(find("Listening…")) { it.parent }.first { it.isClickable }
            assertFalse(button.isEnabled)
        }
    }

    @Test fun phoneReactionsKeepAwakeAndRestingPosesReadable() {
        ActivityScenario.launch(MainActivity::class.java).use {
            for (behavior in listOf(Behavior.WATCHFUL, Behavior.RESTING)) {
                val state = fixture(behavior)
                for (reaction in PhoneReaction.entries) {
                    val environment = state.environment!!.copy(
                        localTime = if (reaction == PhoneReaction.NIGHT) LocalTime.of(23, 0) else LocalTime.NOON,
                        timeOfDay = if (reaction == PhoneReaction.NIGHT) TimeOfDay.NIGHT else TimeOfDay.AFTERNOON,
                        batteryPercent = if (reaction == PhoneReaction.LOW_BATTERY) 15 else 70,
                        isCharging = reaction == PhoneReaction.CHARGING,
                    )
                    render(state.copy(environment = environment))
                    assertVisible(behavior.label())
                    assertVisible(reaction.captionFor(behavior))
                    val description = if (reaction == PhoneReaction.LOW_BATTERY && behavior == Behavior.RESTING)
                        "Low-battery reminder" else reaction.description
                    assertTrue(nodes().any { it.contentDescription?.contains(description) == true })
                    capture("phone-${reaction.name.lowercase()}-${behavior.name.lowercase()}")
                }
            }
        }
    }

    private fun render(state: FamiliarUiState, narrow: Boolean = false) {
        // An animated screen may never make the main queue idle on a software emulator.
        // Post directly, then use the bounded accessibility checks below to await the frame.
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
            activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB6D4C4),
                    background = Color(0xFF151C20), surface = Color(0xFF151C20),
                    surfaceContainer = Color(0xFF202B30), onBackground = Color(0xFFE6EEE9),
                    onSurface = Color(0xFFE6EEE9))) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density,
                        fontScale = if (narrow) 1.4f else 1f)) {
                        key(state, narrow) {
                            Surface {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                    Box(if (narrow) Modifier.widthIn(max = 320.dp) else Modifier) {
                                        FamiliarScreen(state, { pokes.incrementAndGet() }, {},
                                            { listens.incrementAndGet() }, { _, _ -> })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        SystemClock.sleep(700) // Allow the Compose frame and accessibility tree to arrive.
    }

    private fun fixture(behavior: Behavior): FamiliarUiState {
        val now = 1_791_000_000_000L
        val creature = CreatureState.initial(now - 3 * 86_400_000).copy(
            energy = when (behavior) { Behavior.RESTING -> 45f; Behavior.DROWSY -> 30f; else -> 75f },
            stimulation = when (behavior) { Behavior.RESTLESS -> 22f; Behavior.LIVELY -> 80f; else -> 50f },
            mode = if (behavior == Behavior.RESTING) CreatureMode.RESTING else CreatureMode.AWAKE,
            lastUpdatedAt = now, lastInteractionAt = now - 3_600_000, interactionCount = 18,
        )
        return FamiliarUiState(update = SimulationUpdate(creature, 0),
            environment = EnvironmentSnapshot(now, LocalTime.of(19, 42), TimeOfDay.EVENING, 82, true),
            isLoading = false, brainConfigured = true,
            thought = BrainResponse("I’m watching the evening settle. Your phone is gathering a little energy; " +
                "I’m happy to keep it company.").text)
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        // setContent replaces virtual Compose nodes; discard cached nodes before reading a new frame.
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        fun walk(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
            (0 until node.childCount).flatMap { index -> node.getChild(index)?.let(::walk) ?: emptyList() }
        return automation.rootInActiveWindow?.also { it.refresh() }?.let(::walk) ?: emptyList()
    }

    private fun find(text: String): AccessibilityNodeInfo {
        repeat(40) {
            nodes().firstOrNull { it.text?.contains(text) == true }?.let { return it }
            SystemClock.sleep(100)
        }
        capture("snapshot-failure")
        error("Text not found: $text; visible text: ${nodes().mapNotNull { it.text }}")
    }

    private fun assertVisible(text: String) = assertTrue("Not visible: $text", find(text).isVisibleToUser)

    private fun click(node: AccessibilityNodeInfo) {
        val button = generateSequence(node) { it.parent }.first { it.isClickable }
        assertTrue(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.runOnMainSync {} // Wait for the click callback, without waiting for idle animation.
    }

    private fun scrollUntil(text: String) {
        repeat(12) {
            if (nodes().any { it.text?.contains(text) == true && it.isVisibleToUser }) return
            val metrics = instrumentation.targetContext.resources.displayMetrics
            val x = metrics.widthPixels / 2
            val start = (metrics.heightPixels * 0.72f).toInt()
            val end = (metrics.heightPixels * 0.50f).toInt()
            // Exercise a real swipe; Compose scroll accessibility actions can report false while settling.
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input swipe $x $start $x $end 250"))
                .use { it.readBytes() }
            SystemClock.sleep(600)
        }
        capture("snapshot-scroll-failure")
        error("Could not scroll to $text; visible text: ${nodes().mapNotNull { it.text }}")
    }

    private fun capture(name: String) {
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "snapshot-captures")
        directory.mkdirs()
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
