package dev.pocketfamiliar.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import dev.pocketfamiliar.roam.RoamBinding
import dev.pocketfamiliar.roam.uuid

class HostedCreatureBrain(
    private val settings: () -> BrainSettings,
    private val binding: (String, Long) -> RoamBinding? = { _, _ -> null },
) : CreatureBrain {
    override suspend fun think(context: CreatureContext): BrainResponse = withContext(Dispatchers.IO) {
        val config = settings().validated()
        val connection = URI("${config.endpoint}/think").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.accessToken}")
            val payload = contextPayload(context, binding(config.endpoint, context.creature.createdAt))
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            when (connection.responseCode) {
                200 -> Unit
                401 -> throw IOException("Check the backend access token in developer details.")
                409 -> throw IOException("The Free Roam memory or pairing changed. Refresh the journal and try again.")
                503 -> throw IOException("Free Roam memory is unavailable. Check the backend storage.")
                429 -> throw IOException("The familiar needs a moment. Try listening later.")
                else -> throw IOException("The voice service is unavailable. Try again later.")
            }
            val body = connection.inputStream.use { it.readBytesBounded() }
            val response = JSONObject(body)
            BrainResponse(
                text = response.getString("text").trim(),
                discoveryId = response.optString("discoveryId").takeIf { it.isNotBlank() },
                source = if (response.has("sourceTitle") && response.has("sourceUrl")) {
                    DiscoverySource(response.getString("sourceTitle"), response.getString("sourceUrl"))
                } else null,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun java.io.InputStream.readBytesBounded(): String {
        // One saved research URL can be 2048 bytes, alongside Unicode voice text.
        val buffer = ByteArray(8193)
        var size = 0
        while (size < buffer.size) {
            val count = read(buffer, size, buffer.size - size)
            if (count == -1) break
            size += count
        }
        if (size > 8192) throw IOException("The voice service returned an invalid response.")
        return String(buffer, 0, size, Charsets.UTF_8)
    }
}

/** Sends an existing pairing reference when connected; birth time and raw phone events stay local. */
internal fun contextPayload(context: CreatureContext, binding: RoamBinding? = null): JSONObject {
    val creature = context.creature
    return JSONObject().apply {
        binding?.familiarId?.let { familiarId ->
            put("freeRoam", JSONObject().put("pairingId", uuid(binding.pairingId)).put("familiarId", uuid(familiarId)))
        }
    }.put("discoveryVersion", 1)
        .put("recentDiscoveryIds", JSONArray(context.recentDiscoveryIds))
        .put("creature", JSONObject()
            .put("energy", creature.energy).put("stimulation", creature.stimulation)
            .put("mode", creature.mode.name).put("curiosity", creature.curiosity)
            .put("behavior", context.behavior.name))
        .put("environment", context.environment?.let { environment ->
            JSONObject().put("timeOfDay", environment.timeOfDay.name)
                .put("localTime", environment.localTime.toString())
                .put("batteryPercent", environment.batteryPercent ?: JSONObject.NULL)
                .put("isCharging", environment.isCharging ?: JSONObject.NULL)
                .apply {
                    environment.appUsage?.let { usage ->
                        put("appUsage", JSONObject().put("windowMinutes", usage.windowMinutes)
                            .put("apps", JSONArray().apply {
                                usage.apps.forEach { app ->
                                    put(JSONObject().put("appName", app.appName)
                                        .put("approximateMinutes", app.approximateMinutes))
                                }
                            }))
                    }
                }
        } ?: JSONObject.NULL)
}
