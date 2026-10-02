package dev.pocketfamiliar.brain

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pocketfamiliar.perception.AppUsageObservation
import dev.pocketfamiliar.perception.EnvironmentSnapshot
import dev.pocketfamiliar.perception.ObservedAppUsage
import dev.pocketfamiliar.perception.TimeOfDay
import dev.pocketfamiliar.simulation.CreatureState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime

/** Exercises the actual outbound serializer without networking or an API key. */
@RunWith(AndroidJUnit4::class)
class HostedContextTest {
    private val creature = CreatureState.initial(1_800_000_111_000L).copy(
        lastUpdatedAt = 1_800_000_222_000L,
        lastInteractionAt = 1_800_000_211_000L,
        interactionCount = 42L,
    )

    private fun environment(usage: AppUsageObservation? = null) = EnvironmentSnapshot(
        observedAtMillis = 1_800_000_333_000L,
        localTime = LocalTime.of(19, 25),
        timeOfDay = TimeOfDay.EVENING,
        batteryPercent = 64,
        isCharging = false,
        appUsage = usage,
    )

    @Test fun absentAppObservationOmitsTheFieldEntirely() {
        val payload = contextPayload(CreatureContext(creature, environment()))
        val outboundEnvironment = payload.getJSONObject("environment")
        assertFalse(outboundEnvironment.has("appUsage"))
        assertEquals(setOf("timeOfDay", "localTime", "batteryPercent", "isCharging"),
            outboundEnvironment.fieldNames())
    }

    @Test fun appObservationIncludesOnlySelectedNamesAndApproximateMinutes() {
        val apps = listOf(ObservedAppUsage("Video", 25), ObservedAppUsage("Puzzle", 1), ObservedAppUsage("Browser", 60))
        val payload = contextPayload(CreatureContext(creature, environment(AppUsageObservation(60, apps))))
        val usage = payload.getJSONObject("environment").getJSONObject("appUsage")
        assertEquals(setOf("windowMinutes", "apps"), usage.fieldNames())
        assertEquals(60, usage.getInt("windowMinutes"))
        val outgoingApps = usage.getJSONArray("apps")
        assertEquals(apps.size, outgoingApps.length())
        for (index in apps.indices) {
            val app = outgoingApps.getJSONObject(index)
            assertEquals(setOf("appName", "approximateMinutes"), app.fieldNames())
            assertEquals(apps[index].appName, app.getString("appName"))
            assertEquals(apps[index].approximateMinutes, app.getInt("approximateMinutes"))
            assertTrue(app.getInt("approximateMinutes") in 1..usage.getInt("windowMinutes"))
        }
    }

    @Test fun payloadExcludesCanonicalTimestampsInteractionsAndRawObservationFields() {
        val payload = contextPayload(CreatureContext(creature, environment(AppUsageObservation(60,
            listOf(ObservedAppUsage("Browser", 12))))))
        assertEquals(setOf("discoveryVersion", "recentDiscoveryIds", "creature", "environment"), payload.fieldNames())
        assertEquals(setOf("energy", "stimulation", "mode", "curiosity", "behavior"),
            payload.getJSONObject("creature").fieldNames())
        assertEquals(setOf("timeOfDay", "localTime", "batteryPercent", "isCharging", "appUsage"),
            payload.getJSONObject("environment").fieldNames())
        val json = payload.toString()
        for (excluded in listOf("createdAt", "lastUpdatedAt", "lastInteractionAt", "interactionCount",
            "observedAtMillis", "packageName", "selectedPackages", "className", "rawEvents")) {
            assertFalse("Unexpected outbound field: $excluded", json.contains("\"$excluded\""))
        }
        for (timestamp in listOf(creature.createdAt, creature.lastUpdatedAt, creature.lastInteractionAt!!,
            environment().observedAtMillis)) {
            assertFalse("Unexpected outbound timestamp", json.contains(timestamp.toString()))
        }
    }

    @Test fun recentDiscoveryIdsAreAnExplicitBoundedMaterialList() {
        val history = listOf("bee-flower-learning", "moon-footprints", "time-book-chapter")
        val payload = contextPayload(CreatureContext(creature, environment(), history))
        assertEquals(1, payload.getInt("discoveryVersion"))
        val outgoing = payload.getJSONArray("recentDiscoveryIds")
        assertEquals(history.size, outgoing.length())
        assertEquals(history, (0 until outgoing.length()).map(outgoing::getString))
    }

    @Test fun emptyObservedHourDoesNotInventAnyAppEntries() {
        val payload = contextPayload(CreatureContext(creature, environment(AppUsageObservation(60, emptyList()))))
        val usage = payload.getJSONObject("environment").getJSONObject("appUsage")
        assertEquals(60, usage.getInt("windowMinutes"))
        assertEquals(0, usage.getJSONArray("apps").length())
    }

    @Test fun unavailableEnvironmentRemainsExplicitlyNull() {
        val payload = contextPayload(CreatureContext(creature, null))
        assertTrue(payload.isNull("environment"))
        assertEquals(0, payload.getJSONArray("recentDiscoveryIds").length())
    }

    @Test fun largestNormalUnicodeSummaryFitsBackendRequestLimit() {
        // CJK names have 80 UTF-16 units; emoji names have 40 intact surrogate pairs.
        val apps = (1..5).map {
            val name = when (it) {
                4 -> "\uD83D\uDE00".repeat(40)
                5 -> "\uD83D\uDE42".repeat(40)
                else -> "界".repeat(79) + it
            }
            ObservedAppUsage(name, 60)
        }
        val history = (1..8).map { "a".repeat(62) + "-$it" }
        val payload = contextPayload(CreatureContext(creature, environment(AppUsageObservation(60, apps)), history))
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        assertTrue("Outbound payload exceeds the backend's 4096-byte request limit: ${bytes.size}", bytes.size <= 4096)
        assertEquals(5, payload.getJSONObject("environment").getJSONObject("appUsage").getJSONArray("apps").length())
        assertEquals(8, payload.getJSONArray("recentDiscoveryIds").length())
        assertEquals(apps.first().appName, JSONObject(String(bytes, Charsets.UTF_8))
            .getJSONObject("environment").getJSONObject("appUsage").getJSONArray("apps")
            .getJSONObject(0).getString("appName"))
    }

    private fun JSONObject.fieldNames(): Set<String> = keys().asSequence().toSet()
}
