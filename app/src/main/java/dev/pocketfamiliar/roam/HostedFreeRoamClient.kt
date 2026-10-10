package dev.pocketfamiliar.roam

import dev.pocketfamiliar.brain.BrainSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

class RoamApiException(message: String, val conflict: Boolean = false) : IOException(message)

class HostedFreeRoamClient(
    private val open: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) : FreeRoamClient {
    override suspend fun snapshot(settings: BrainSettings) =
        parseSnapshot(request(settings, "GET", "/free-roam"))

    override suspend fun journal(settings: BrainSettings, before: Long): RoamJournalPage =
        parseJournal(request(settings, "GET", "/free-roam/journal?before=$before&limit=20"), before)

    override suspend fun pair(settings: BrainSettings, identity: RoamIdentity, pairingId: String) =
        parseSnapshot(request(settings, "POST", "/free-roam/pair", pairingPayload(identity, pairingId)))

    override suspend fun controls(settings: BrainSettings, revision: Long, controls: RoamControls) {
        request(settings, "POST", "/free-roam/controls", controlsPayload(revision, controls))
    }

    override suspend fun forget(settings: BrainSettings, revision: Long) {
        request(settings, "POST", "/free-roam/forget", JSONObject().put("expectedRevision", revision)
            .put("confirmation", "forget-cognitive-memory"))
    }

    override suspend fun deleteEntry(settings: BrainSettings, revision: Long, id: String) {
        request(settings, "DELETE", "/free-roam/journal/" + uuid(id), JSONObject().put("expectedRevision", revision))
    }

    private suspend fun request(settings: BrainSettings, method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val config = settings.validated()
            val connection = open(URI(config.endpoint + path))
            try {
                connection.requestMethod = method
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Authorization", "Bearer " + config.accessToken)
                connection.setRequestProperty("Cache-Control", "no-store")
                if (body != null) {
                    val bytes = body.toString().toByteArray(Charsets.UTF_8)
                    require(bytes.size <= 16_384)
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                when (connection.responseCode) {
                    200 -> Unit
                    401 -> throw RoamApiException("Check the backend access token in developer details.")
                    409 -> throw RoamApiException("The server changed. Refresh and review before trying again.", conflict = true)
                    400 -> throw RoamApiException("The Free Roam request was rejected. Review the settings.")
                    404 -> throw RoamApiException("Update the backend to support the Free Roam journal.")
                    503 -> throw RoamApiException("Free Roam storage is unavailable. Check its persistent volume and migration.")
                    else -> throw RoamApiException("The Free Roam service is unavailable. Try again later.")
                }
                val bytes = connection.inputStream.use { stream ->
                    val buffer = ByteArray(1_048_577)
                    var size = 0
                    while (size < buffer.size) {
                        val count = stream.read(buffer, size, buffer.size - size)
                        if (count == -1) break
                        size += count
                    }
                    if (size > 1_048_576) throw RoamApiException("The Free Roam response is too large.")
                    String(buffer, 0, size, Charsets.UTF_8)
                }
                JSONObject(bytes)
            } catch (error: RoamApiException) {
                throw error
            } catch (error: IOException) {
                throw RoamApiException("Could not reach Free Roam. Check your connection and refresh.")
            } finally {
                connection.disconnect()
            }
        }
}

/** Pairing is the only identity upload; neither serializer accepts CreatureContext. */
internal fun pairingPayload(identity: RoamIdentity, pairingId: String): JSONObject {
    require(identity.createdAt >= 0 && identity.curiosity.isFinite() && identity.curiosity in 0f..1f)
    return JSONObject().put("pairingId", uuid(pairingId)).put("createdAt", identity.createdAt)
        .put("curiosity", identity.curiosity)
}

internal fun controlsPayload(revision: Long, controls: RoamControls): JSONObject {
    require(revision >= 0)
    val valid = controls.validated()
    return JSONObject().put("expectedRevision", revision).put("enabled", valid.enabled)
        .put("webEnabled", valid.webEnabled).put("allowedDomains", JSONArray(valid.allowedDomains))
        .put("wakeIntervalHours", valid.wakeIntervalHours).put("dailyWakeLimit", valid.dailyWakeLimit)
        .put("monthlyBudgetCents", valid.monthlyBudgetCents)
}

private fun JSONObject.strictLong(key: String): Long {
    val value = get(key)
    require(value is Int || value is Long)
    return (value as Number).toLong().also { require(it >= 0) }
}
private fun JSONObject.optionalLong(key: String): Long? = if (isNull(key)) null else strictLong(key)
private fun JSONObject.strictBoolean(key: String) = (get(key) as? Boolean) ?: error("Invalid boolean")
private fun JSONObject.string(key: String, max: Int): String = (get(key) as? String)?.also {
    require(it.codePointCount(0, it.length) <= max && it.none { char -> char.code < 32 && char !in "\n\t" })
} ?: error("Invalid string")
private fun JSONObject.optionalString(key: String, max: Int) = if (isNull(key)) null else string(key, max)
private fun <T> JSONArray.items(max: Int, transform: (JSONObject) -> T): List<T> {
    require(length() <= max)
    return (0 until length()).map { transform(getJSONObject(it)) }
}

internal fun parseSnapshot(raw: JSONObject): RoamSnapshot {
    val profile = if (raw.isNull("profile")) null else raw.getJSONObject("profile").let {
        val curiosity = (it.get("curiosity") as? Number)?.toFloat() ?: error("Invalid curiosity")
        require(curiosity.isFinite() && curiosity in 0f..1f)
        RoamProfile(uuid(it.string("familiarId", 36)), uuid(it.string("pairingId", 36)), it.strictLong("createdAt"), curiosity)
    }
    val controls = raw.getJSONObject("controls").let {
        RoamControls(it.strictBoolean("enabled"), it.strictBoolean("webEnabled"),
            it.getJSONArray("allowedDomains").let { array ->
                require(array.length() <= 20)
                (0 until array.length()).map { index -> array.get(index) as String }
            },
            it.strictLong("wakeIntervalHours").also { value -> require(value <= 168) }.toInt(),
            it.strictLong("dailyWakeLimit").also { value -> require(value <= 4) }.toInt(),
            it.strictLong("monthlyBudgetCents").also { value -> require(value <= 10_000) }.toInt()).validated()
    }
    val runtime = raw.getJSONObject("runtime")
    val execution = raw.optJSONObject("execution")
    return RoamSnapshot(raw.strictLong("revision"), profile, controls,
        runtime.strictBoolean("autonomousExecutionAvailable"), runtime.strictBoolean("webToolsAvailable"),
        execution?.optionalLong("lastWakeAt"), execution?.optionalLong("nextWakeAt"),
        execution?.getJSONArray("monthlyCharges")?.items(12) {
            val month = it.string("month", 7)
            require(month.matches(Regex("[0-9]{4}-(0[1-9]|1[0-2])")))
            RoamCharge(month, it.strictLong("chargedMicrousd"))
        } ?: emptyList(),
        raw.getJSONArray("interests").items(10) { it.string("topic", 80) },
        raw.getJSONArray("goals").items(100) {
            val status = it.string("status", 12)
            require(status in listOf("active", "paused", "completed"))
            RoamGoal(it.string("topic", 80), it.string("question", 500), status)
        }, if (runtime.has("wakeReservationMicrousd")) runtime.strictLong("wakeReservationMicrousd").also {
            require(it in 1..100_000_000)
        } else 50_000, runtime.optionalString("model", 100), runtime.optionalString("modelStatus", 24).also {
            require(it == null || it in listOf("ready", "unsupported_model"))
        })
}

internal fun parseJournal(raw: JSONObject, before: Long): RoamJournalPage {
    val items = raw.getJSONArray("items").items(20) {
        val status = it.string("status", 12)
        val action = it.optionalString("action", 12)
        require(status in listOf("running", "completed", "failed", "cancelled", "interrupted"))
        require(action == null || action in listOf("explore", "research", "reflect", "rest"))
        RoamJournalEntry(it.strictLong("sequence"), uuid(it.string("id", 36)), it.strictLong("startedAt"),
            it.optionalLong("finishedAt"), status, action, it.optionalString("topic", 80),
            it.optionalString("reason", 300), it.optionalString("question", 300), it.optionalString("summary", 2000),
            it.getJSONArray("sources").items(6) { source -> RoamSource(source.string("title", 200), source.string("url", 2048)) },
            it.optionalString("errorCode", 80), it.strictLong("modelCalls").also { count -> require(count <= 2) }.toInt(),
            it.strictLong("chargedMicrousd"))
    }
    require(items.all { it.sequence in 1 until before } && items.zipWithNext().all { (newer, older) -> newer.sequence > older.sequence })
    val cursor = raw.optionalLong("nextCursor")
    require(cursor == null || (items.isNotEmpty() && cursor == items.last().sequence))
    return RoamJournalPage(raw.strictLong("revision"), items, cursor)
}
