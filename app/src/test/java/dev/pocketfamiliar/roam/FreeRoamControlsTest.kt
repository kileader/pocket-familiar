package dev.pocketfamiliar.roam

import org.junit.Assert.*
import org.junit.Test

class FreeRoamControlsTest {
    @Test fun defaultsNeverGrantExecutionOrWebPermission() {
        val controls = RoamControls().validated()
        assertFalse(controls.enabled)
        assertFalse(controls.webEnabled)
        assertEquals(500, controls.monthlyBudgetCents)
    }

    @Test fun editorNormalizesDomainsAndConvertsExactDollarAmounts() {
        val controls = editedControls(RoamControls(), "NASA.GOV,\nscience.nasa.gov", "12", "2", "3.05", true)
        assertEquals(listOf("nasa.gov", "science.nasa.gov"), controls.allowedDomains)
        assertEquals(305, controls.monthlyBudgetCents)
        assertFalse(controls.enabled)
    }

    @Test fun editorPreservesPausedStateAndRejectsOverprecisionOrInvalidLimits() {
        for (dollars in listOf("1.001", "1e2", "-1", "100.01", "NaN")) {
            assertThrows(IllegalArgumentException::class.java) { editedControls(RoamControls(), "", "6", "4", dollars, false) }
        }
        assertThrows(IllegalArgumentException::class.java) { editedControls(RoamControls(), "", "5", "4", "5", false) }
        assertThrows(IllegalArgumentException::class.java) { editedControls(RoamControls(), "", "6", "5", "5", false) }
        assertEquals(0, editedControls(RoamControls(), "", "6", "4", "0", false).monthlyBudgetCents)
    }

    @Test fun webRequiresPublicUniqueDomains() {
        for (domains in listOf(emptyList(), listOf("https://nasa.gov"), listOf("*.nasa.gov"),
            listOf("127.0.0.1"), listOf("host.internal"), listOf("nasa.gov", "NASA.GOV"), listOf("nasa.gov/path"))) {
            assertThrows(IllegalArgumentException::class.java) { RoamControls(webEnabled = true, allowedDomains = domains).validated() }
        }
    }

    @Test fun sourceLinksRejectUnsafeSchemesCredentialsAndAmbiguousHosts() {
        for (url in listOf("http://nasa.gov/", "https://user@nasa.gov/", "https://nasa.gov:8080/",
            "https://evil.test\\.nasa.gov/", "https://127.0.0.1/", "javascript:alert(1)")) {
            assertThrows(Exception::class.java) { RoamSource("Source", url) }
        }
        assertEquals("https://science.nasa.gov/", RoamSource("NASA", "https://science.nasa.gov/").url)
        // Python's URL parser accepts case-insensitive schemes; keep the client compatible.
        assertEquals("HTTPS://NASA.GOV/", RoamSource("NASA", "HTTPS://NASA.GOV/").url)
    }

    @Test fun bindingIdsRequireCanonicalV4Uuids() {
        assertThrows(IllegalArgumentException::class.java) { uuid("1") }
        assertThrows(IllegalArgumentException::class.java) { uuid("c4265be1-60a0-4197-04d9-bf1c37fda7bc") }
        assertEquals("c4265be1-60a0-4197-94d9-bf1c37fda7bc", uuid("c4265be1-60a0-4197-94d9-bf1c37fda7bc"))
    }

    @Test fun pairingUsesTheOriginalCreationAndCuriosityOnly() {
        val profile = RoamProfile("familiar", "pairing", 100L, 0.65f)
        assertTrue(profile.matches(RoamIdentity(100L, 0.65f)))
        assertFalse(profile.matches(RoamIdentity(101L, 0.65f)))
        assertFalse(profile.matches(RoamIdentity(100L, 0.7f)))
    }
}
