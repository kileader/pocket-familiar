package dev.pocketfamiliar.brain

import dev.pocketfamiliar.simulation.CreatureMode
import dev.pocketfamiliar.simulation.CreatureState
import dev.pocketfamiliar.simulation.Behavior
import org.junit.Assert.*
import org.junit.Test

class CreatureBrainTest {
    @Test fun `context derives resting behavior without changing canonical state`() {
        val state = CreatureState.initial(1000).copy(mode = CreatureMode.RESTING, energy = 30f)
        val context = CreatureContext(state, null)
        assertEquals(Behavior.RESTING, context.behavior)
        assertSame(state, context.creature)
        assertNull(context.environment)
    }

    @Test fun `settings accept only a secure backend and separate access token`() {
        val token = "a".repeat(32)
        assertEquals("https://example.com", BrainSettings(" https://example.com/ ", token).validated().endpoint)
        for (url in listOf("http://example.com", "https://user:pass@example.com", "https://example.com?a=b",
            "https://example.com#x", "https://bad host", "https://example.com:99999")) {
            assertThrows(IllegalArgumentException::class.java) { BrainSettings(url, token).validated() }
        }
        assertThrows(IllegalArgumentException::class.java) { BrainSettings("https://example.com", "sk-" + token).validated() }
        assertThrows(IllegalArgumentException::class.java) { BrainSettings("https://example.com", "tiny").validated() }
    }

    @Test fun `brain response cannot carry a long or blank thought`() {
        assertEquals("Dozing.", BrainResponse("Dozing.").text)
        assertThrows(IllegalArgumentException::class.java) { BrainResponse(" ") }
        assertThrows(IllegalArgumentException::class.java) { BrainResponse("x".repeat(701)) }
        assertThrows(IllegalArgumentException::class.java) { BrainResponse("A thought", "bad id") }
        assertThrows(IllegalArgumentException::class.java) { DiscoverySource("Source", "http://example.com") }
    }
}
