package dev.pocketfamiliar.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.pocketfamiliar.MainActivity
import dev.pocketfamiliar.roam.RoamFixtures
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Read-only synthetic journal; actions are captured callbacks, not server requests. */
@RunWith(AndroidJUnit4::class)
class FreeRoamPresentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val starts = AtomicInteger()
    private val forgets = AtomicInteger()
    private var openedUri: String? = null
    private var renderCount = 0
    private val fixture get() = FreeRoamUiState(configured = true, snapshot = RoamFixtures.state(),
        entries = RoamFixtures.journal().items, connected = true, fresh = true, loadedAt = 1_800_000_000_000)

    @Test fun actualSnapshotOpensJournalAndBackReturnsToFamiliar() {
        val preferences = instrumentation.targetContext.getSharedPreferences("brain-settings", 0)
        val endpoint = preferences.getString("endpoint", null)
        val token = preferences.getString("access-token", null)
        preferences.edit().clear().commit() // No backend configuration, so no real network request.
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                scrollUntil("Free Roam journal")
                click(find("Free Roam journal"))
                find("Connect your backend first")
                assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                find("Familiar Snapshot")
            }
        } finally {
            preferences.edit().putString("endpoint", endpoint).putString("access-token", token).commit()
        }
    }

    @Test fun startRequiresPaidConsentAndPauseIsAnImmediateOwnerAction() {
        ActivityScenario.launch(MainActivity::class.java).use {
            render(fixture, narrow = true)
            scrollUntil("Start Free Roam")
            click(find("Start Free Roam"))
            find("Start paid server wakes?")
            find("This permits sending curiosity")
            capture("journal-consent")
            assertEquals(0, starts.get())
            click(find("Cancel"))
            assertEquals(0, starts.get())
            click(find("Start Free Roam"))
            find("Start paid server wakes?")
            click(find("Start Free Roam"))
            assertEquals(1, starts.get())
            render(fixture.copy(snapshot = fixture.snapshot!!.copy(controls = fixture.snapshot!!.controls.copy(enabled = true))))
            click(find("Pause Free Roam"))
            assertEquals(2, starts.get())
        }
    }

    @Test fun narrowLargeTextKeepsSourcesControlsAndForgetReachable() {
        ActivityScenario.launch(MainActivity::class.java).use {
            render(fixture, narrow = true)
            capture("journal-narrow-top")
            scrollUntil("Source: Navigation research")
            click(find("Source: Navigation research"))
            assertEquals("https://science.nasa.gov/", openedUri)
            capture("journal-narrow-entry")
            scrollUntil("Forget this entry")
            click(find("Forget this entry"))
            find("Forget this journal entry?")
            assertEquals(0, forgets.get())
            click(find("Forget", exact = true))
            assertEquals(1, forgets.get())
            render(fixture, narrow = true)
            scrollUntil("Permissions and limits")
            click(find("Permissions and limits"))
            scrollUntil("Allow web search")
            capture("journal-narrow-controls")
            scrollUntil("Monthly budget (USD)")
            capture("journal-narrow-budget")
            scrollUntil("Forget all Free Roam memory")
            click(find("Forget all Free Roam memory"))
            find("This clears all cognitive content")
            click(find("Cancel"))
            assertEquals(1, forgets.get())
        }
    }

    @Test fun staleAndUnpairedScreensCannotStartPaidWakes() {
        ActivityScenario.launch(MainActivity::class.java).use {
            render(fixture.copy(fresh = false, error = "Offline"))
            val start = generateSequence(find("Start Free Roam")) { it.parent }.first { it.isClickable }
            assertFalse(start.isEnabled)
            capture("journal-stale")
            render(fixture.copy(connected = false, entries = emptyList(), snapshot = fixture.snapshot!!.copy(profile = null)))
            find("Pair existing familiar")
            click(find("Pair existing familiar"))
            find("Only the creation time and curiosity are uploaded.")
            capture("journal-pairing")
            click(find("Cancel"))
            assertEquals(0, starts.get())
            render(FreeRoamUiState())
            find("Connect your backend first")
            capture("journal-unconfigured")
        }
    }

    private fun render(state: FreeRoamUiState, narrow: Boolean = false) {
        // Equal fixtures still need a new composition: setContent can retain scroll state.
        val renderKey = ++renderCount
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
            activity.setContent {
                MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB6D4C4),
                    background = Color(0xFF151C20), surface = Color(0xFF151C20),
                    surfaceContainer = Color(0xFF202B30), onBackground = Color(0xFFE6EEE9), onSurface = Color(0xFFE6EEE9))) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, if (narrow) 1.4f else 1f),
                        LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { openedUri = uri } }) {
                        key(renderKey) { Surface {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Box(if (narrow) Modifier.widthIn(max = 320.dp) else Modifier) {
                                    FreeRoamScreen(state, {}, {}, {}, { starts.incrementAndGet() }, {},
                                        { forgets.incrementAndGet() }, { forgets.incrementAndGet() })
                                }
                            }
                        } }
                    }
                }
            }
        }
        SystemClock.sleep(500)
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        fun walk(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
            (0 until node.childCount).flatMap { node.getChild(it)?.let(::walk) ?: emptyList() }
        return automation.rootInActiveWindow?.also { it.refresh() }?.let(::walk) ?: emptyList()
    }
    private fun find(text: String, exact: Boolean = false): AccessibilityNodeInfo {
        repeat(40) {
            nodes().firstOrNull { if (exact) it.text?.toString() == text else it.text?.contains(text) == true }?.let { return it }
            SystemClock.sleep(100)
        }
        capture("journal-failure")
        error("Text not found: $text; visible: ${nodes().mapNotNull { it.text }}")
    }
    private fun click(node: AccessibilityNodeInfo) {
        assertTrue(generateSequence(node) { it.parent }.first { it.isClickable }
            .performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.runOnMainSync {}
        SystemClock.sleep(200)
    }
    private fun scrollUntil(text: String) {
        repeat(20) {
            if (nodes().any { it.text?.contains(text) == true && it.isVisibleToUser }) return
            val metrics = instrumentation.targetContext.resources.displayMetrics
            val x = metrics.widthPixels / 2
            val start = (metrics.heightPixels * 0.75f).toInt()
            val end = (metrics.heightPixels * 0.5f).toInt()
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input swipe $x $start $x $end 250"))
                .use { it.readBytes() }
            SystemClock.sleep(450)
        }
        capture("journal-scroll-failure")
        error("Could not scroll to $text; visible: ${nodes().mapNotNull { it.text }}")
    }
    private fun capture(name: String) {
        SystemClock.sleep(350) // Let dialog/window fade-in finish before visual inspection.
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "free-roam-captures")
        directory.mkdirs()
        val bitmap = requireNotNull(automation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
