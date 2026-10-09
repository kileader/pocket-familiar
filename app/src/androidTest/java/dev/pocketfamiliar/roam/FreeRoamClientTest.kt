package dev.pocketfamiliar.roam

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pocketfamiliar.brain.BrainSettings
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal object RoamFixtures {
    const val PAIR = "c4265be1-60a0-4197-94d9-bf1c37fda7bc"
    const val FAMILIAR = "dd3d7992-b4a3-42ba-8e37-5d57be3d6b5d"
    const val ENTRY = "a5e01d5c-62c8-470c-bfd1-634391817f33"
    val identity = RoamIdentity(1_790_000_000_000, 0.65f)
    fun snapshot(profile: Boolean = true): JSONObject = JSONObject().put("revision", 7)
        .put("profile", if (profile) JSONObject().put("familiarId", FAMILIAR).put("pairingId", PAIR)
            .put("createdAt", identity.createdAt).put("curiosity", 0.65) else JSONObject.NULL)
        .put("controls", controlsPayload(7, RoamControls()).apply { remove("expectedRevision") })
        .put("runtime", JSONObject().put("autonomousExecutionAvailable", true).put("webToolsAvailable", true))
        .put("execution", JSONObject().put("lastWakeAt", JSONObject.NULL).put("nextWakeAt", JSONObject.NULL).put("monthlyCharges", JSONArray()))
        .put("interests", JSONArray()).put("goals", JSONArray())
    fun entry(sequence: Long = 100): JSONObject = JSONObject().put("sequence", sequence).put("id", ENTRY)
        .put("startedAt", 1_800_000_000_000L).put("finishedAt", 1_800_000_001_000L)
        .put("status", "completed").put("action", "research").put("topic", "Animal navigation")
        .put("reason", "I wanted to follow my earlier question.").put("question", "How do animals find their way?")
        .put("summary", "I found a description of navigation research. The mechanisms remain uncertain.")
        .put("sources", JSONArray().put(JSONObject().put("title", "Navigation research").put("url", "https://science.nasa.gov/")))
        .put("errorCode", JSONObject.NULL).put("modelCalls", 2).put("chargedMicrousd", 15_000)
    fun page(): JSONObject = JSONObject().put("revision", 7).put("items", JSONArray().put(entry())).put("nextCursor", JSONObject.NULL)
    fun state() = parseSnapshot(snapshot())
    fun journal() = parseJournal(page(), Long.MAX_VALUE)
}

@RunWith(AndroidJUnit4::class)
class FreeRoamClientTest {
    private val settings = BrainSettings("https://example.com", "test-token-" + "a".repeat(32))

    private class Connection(body: ByteArray, private val code: Int = 200) : HttpURLConnection(URL("https://example.com")) {
        val sent = ByteArrayOutputStream()
        val received = ByteArrayInputStream(body)
        var disconnected = false
        override fun getResponseCode() = code
        override fun getOutputStream() = sent
        override fun getInputStream() = received
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
    }

    @Test fun pairingSerializerUploadsOnlyItsExplicitIdentityMetadata() = runBlocking {
        val connection = Connection(RoamFixtures.snapshot().toString().toByteArray())
        val client = HostedFreeRoamClient { uri ->
            assertEquals("https", uri.scheme)
            assertEquals("/free-roam/pair", uri.path)
            connection
        }
        val result = client.pair(settings, RoamFixtures.identity, RoamFixtures.PAIR)
        val sent = JSONObject(connection.sent.toString("UTF-8"))
        assertEquals(setOf("pairingId", "createdAt", "curiosity"), sent.keys().asSequence().toSet())
        assertEquals(RoamFixtures.identity.createdAt, sent.getLong("createdAt"))
        assertEquals(0.65, sent.getDouble("curiosity"), 0.0)
        assertEquals(RoamFixtures.FAMILIAR, result.profile!!.familiarId)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals("Bearer " + settings.accessToken, connection.getRequestProperty("Authorization"))
        assertTrue(connection.disconnected)
    }

    @Test fun journalUsesNewestFirstCursorAndPreservesSourcesCostsAndProvenance() = runBlocking {
        val connection = Connection(RoamFixtures.page().toString().toByteArray())
        val client = HostedFreeRoamClient { uri ->
            assertEquals("before=" + Long.MAX_VALUE + "&limit=20", uri.query)
            connection
        }
        val result = client.journal(settings)
        assertEquals("research", result.items.single().action)
        assertEquals("https://science.nasa.gov/", result.items.single().sources.single().url)
        assertEquals(15_000L, result.items.single().chargedMicrousd)
        assertEquals("GET", connection.requestMethod)
        assertEquals(0, connection.sent.size())
    }

    @Test fun controlRequestsContainRevisionAndNoIdentityOrPhoneFields() = runBlocking {
        val connection = Connection("{\"revision\":8}".toByteArray())
        HostedFreeRoamClient { connection }.controls(settings, 7, RoamControls(webEnabled = true, allowedDomains = listOf("NASA.GOV")))
        val sent = JSONObject(connection.sent.toString("UTF-8"))
        assertEquals(setOf("expectedRevision", "enabled", "webEnabled", "allowedDomains", "wakeIntervalHours", "dailyWakeLimit", "monthlyBudgetCents"),
            sent.keys().asSequence().toSet())
        assertEquals(7L, sent.getLong("expectedRevision"))
        assertFalse(sent.getBoolean("enabled"))
        assertEquals("nasa.gov", sent.getJSONArray("allowedDomains").getString(0))
    }

    @Test fun responseValidationRejectsUnsafeSourcesBadOrderingAndFractionalRevision() {
        val bad = RoamFixtures.page()
        bad.getJSONArray("items").getJSONObject(0).getJSONArray("sources").getJSONObject(0).put("url", "http://nasa.gov/")
        assertThrows(Exception::class.java) { parseJournal(bad, Long.MAX_VALUE) }
        val ascending = RoamFixtures.page().put("items", JSONArray().put(RoamFixtures.entry(1)).put(RoamFixtures.entry(2)))
        assertThrows(Exception::class.java) { parseJournal(ascending, Long.MAX_VALUE) }
        assertThrows(Exception::class.java) { parseSnapshot(RoamFixtures.snapshot().put("revision", 7.5)) }
        val wrongCursor = RoamFixtures.page().put("nextCursor", 101)
        assertThrows(Exception::class.java) { parseJournal(wrongCursor, Long.MAX_VALUE) }
    }

    @Test fun httpFailuresAreSanitizedAndNeverRetried() = runBlocking {
        for (code in listOf(401, 409, 503, 302)) {
            var calls = 0
            val connection = Connection("private error body and credentials".toByteArray(), code)
            try {
                HostedFreeRoamClient { calls++; connection }.snapshot(settings)
                fail("Expected failure")
            } catch (error: RoamApiException) {
                assertEquals(code == 409, error.conflict)
                assertFalse(error.message.orEmpty().contains("private error body"))
            }
            assertEquals(1, calls)
            assertTrue(connection.disconnected)
            assertEquals("An error body should never be consumed", "private error body and credentials".length, connection.received.available())
        }
    }
}
