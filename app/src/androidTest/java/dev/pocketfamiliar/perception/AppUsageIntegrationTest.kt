package dev.pocketfamiliar.perception

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses isolated preferences and never grants Usage Access or changes device AppOps. */
@RunWith(AndroidJUnit4::class)
class AppUsageIntegrationTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val context = ObservationTestContext(target)
    private lateinit var settings: AppUsageSettings

    @Before fun setUp() {
        assertTrue(context.getSharedPreferences("app_observations", Context.MODE_PRIVATE).edit().clear().commit())
        settings = AppUsageSettings(context)
    }

    @After fun tearDown() {
        context.getSharedPreferences("app_observations", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun observationsDefaultToOffWithoutSelectedApps() {
        assertFalse(settings.enabled)
        assertTrue(settings.selectedPackages.isEmpty())
        assertNull(AppUsagePerceptionSource(context).observe(System.currentTimeMillis()))
    }

    @Test fun selectionsAndEnabledStateSurviveStoreRecreation() {
        val selections = setOf("example.video", "example.browser")
        settings.setSelectedPackages(selections)
        settings.setEnabled(true)
        // Flush pending preference writes before constructing another application context.
        assertTrue(context.getSharedPreferences("app_observations", Context.MODE_PRIVATE)
            .edit().putBoolean("test_flush", true).commit())
        val reopened = AppUsageSettings(ObservationTestContext(target))
        assertTrue(reopened.enabled)
        assertEquals(selections, reopened.selectedPackages)
    }

    @Test fun disablingStopsObservationsWithoutDiscardingSelections() {
        val selections = setOf("example.video")
        settings.setSelectedPackages(selections)
        settings.setEnabled(true)
        settings.setEnabled(false)
        assertNull(AppUsagePerceptionSource(context).observe(System.currentTimeMillis()))
        assertEquals(selections, AppUsageSettings(context).selectedPackages)
    }

    @Test fun enablingWithNoSelectedAppsDoesNotObserveActivity() {
        settings.setEnabled(true)
        assertNull(AppUsagePerceptionSource(context).observe(System.currentTimeMillis()))
    }

    @Test fun missingUsageAccessReturnsNoObservationForSelectedApps() {
        // A device where the user already granted access is left untouched.
        assumeFalse("This case requires Usage Access to be absent", hasAppUsageAccess(context))
        settings.setEnabled(true)
        settings.setSelectedPackages(setOf("example.video"))
        assertNull(AppUsagePerceptionSource(context).observe(System.currentTimeMillis()))
    }

    @Test fun moreThanEightSelectionsAreRejectedWithoutReplacingPreviousChoices() {
        val allowed = (1..AppUsageSettings.MAX_SELECTED_APPS).map { "example.app$it" }.toSet()
        settings.setSelectedPackages(allowed)
        try {
            settings.setSelectedPackages(allowed + "example.ninth")
            fail("More than eight app selections must be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(allowed, settings.selectedPackages)
        }
    }

    @Test fun callerCannotMutateStoredSelectionsThroughSetReferences() {
        val chosen = mutableSetOf("example.video")
        settings.setSelectedPackages(chosen)
        chosen += "example.private"
        assertEquals(setOf("example.video"), settings.selectedPackages)
        val returned = settings.selectedPackages.toMutableSet()
        returned.clear()
        assertEquals(setOf("example.video"), settings.selectedPackages)
    }

    @Test fun appLabelsAreSafeBoundedDisplayText() {
        assertEquals("My App Name", normalizeAppName("  My\nApp\tName\u0000 "))
        assertEquals("Unnamed app", normalizeAppName("\n\t"))
        assertEquals(80, normalizeAppName("a".repeat(100)).length)
    }

    private class ObservationTestContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("test-app-observations-$name", mode)
    }
}
