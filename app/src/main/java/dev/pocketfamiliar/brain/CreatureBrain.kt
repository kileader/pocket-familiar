package dev.pocketfamiliar.brain

import dev.pocketfamiliar.perception.EnvironmentSnapshot
import dev.pocketfamiliar.simulation.CreatureState
import dev.pocketfamiliar.simulation.deriveBehavior

/** Interpretation only: no repository, mutation commands, or simulation authority. */
interface CreatureBrain {
    suspend fun think(context: CreatureContext): BrainResponse
}

data class CreatureContext(val creature: CreatureState, val environment: EnvironmentSnapshot?) {
    val behavior get() = deriveBehavior(creature)
}

data class BrainResponse(val text: String) {
    init {
        require(text.isNotBlank() && text.length <= 280) { "Expected a short creature thought" }
    }
}

data class BrainSettings(val endpoint: String = "", val accessToken: String = "") {
    val configured get() = endpoint.isNotBlank() && accessToken.isNotBlank()

    fun validated(): BrainSettings {
        val uri = try { java.net.URI(endpoint.trim()) } catch (_: java.net.URISyntaxException) {
            throw IllegalArgumentException("Use an HTTPS backend URL")
        }
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535)) { "Use an HTTPS backend URL" }
        require(accessToken.trim().length >= 32 && accessToken.trim().all { it.code in 33..126 }) {
            "Use the backend access token (at least 32 characters), not an OpenAI key"
        }
        require(!accessToken.trim().startsWith("sk-")) { "OpenAI keys belong on the server" }
        return copy(endpoint = endpoint.trim().trimEnd('/'), accessToken = accessToken.trim())
    }
}
