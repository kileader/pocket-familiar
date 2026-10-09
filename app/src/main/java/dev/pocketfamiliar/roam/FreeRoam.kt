package dev.pocketfamiliar.roam

import dev.pocketfamiliar.brain.BrainSettings
import java.net.URI
import java.util.Locale
import java.util.UUID

data class RoamIdentity(val createdAt: Long, val curiosity: Float)
data class RoamBinding(val pairingId: String, val familiarId: String? = null)
data class RoamProfile(val familiarId: String, val pairingId: String, val createdAt: Long, val curiosity: Float) {
    fun matches(identity: RoamIdentity) = createdAt == identity.createdAt && curiosity == identity.curiosity
}

data class RoamControls(
    val enabled: Boolean = false,
    val webEnabled: Boolean = false,
    val allowedDomains: List<String> = emptyList(),
    val wakeIntervalHours: Int = 6,
    val dailyWakeLimit: Int = 4,
    val monthlyBudgetCents: Int = 500,
) {
    fun validated(): RoamControls {
        require(wakeIntervalHours in 6..168) { "Choose a wake interval from 6 to 168 hours." }
        require(dailyWakeLimit in 1..4) { "Choose 1 to 4 wakes per day." }
        require(monthlyBudgetCents in 0..10_000) { "Choose a monthly API budget from $0 to $100." }
        val domains = validateDomains(allowedDomains)
        require(!webEnabled || domains.isNotEmpty()) { "Add an allowed domain before permitting web search." }
        return copy(allowedDomains = domains)
    }
}

data class RoamSource(val title: String, val url: String) {
    init {
        require(title.isNotBlank() && title.codePointCount(0, title.length) <= 200 && url.length <= 2048)
        require(url.all { it.code in 33..126 } && '\\' !in url)
        val uri = URI(url)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.port in listOf(-1, 443))
        validateDomains(listOf(uri.host))
    }
}

data class RoamJournalEntry(
    val sequence: Long, val id: String, val startedAt: Long, val finishedAt: Long?,
    val status: String, val action: String?, val topic: String?, val reason: String?,
    val question: String?, val summary: String?, val sources: List<RoamSource>,
    val errorCode: String?, val modelCalls: Int, val chargedMicrousd: Long,
)

data class RoamGoal(val topic: String, val question: String, val status: String)
data class RoamCharge(val month: String, val chargedMicrousd: Long)
data class RoamSnapshot(
    val revision: Long,
    val profile: RoamProfile?,
    val controls: RoamControls,
    val executionAvailable: Boolean,
    val webAvailable: Boolean,
    val lastWakeAt: Long?,
    val nextWakeAt: Long?,
    val monthlyCharges: List<RoamCharge>,
    val interests: List<String>,
    val goals: List<RoamGoal>,
)
data class RoamJournalPage(val revision: Long, val items: List<RoamJournalEntry>, val nextCursor: Long?)

/** Separate from CreatureBrain: these owner requests never receive body or phone observations. */
interface FreeRoamClient {
    suspend fun snapshot(settings: BrainSettings): RoamSnapshot
    suspend fun journal(settings: BrainSettings, before: Long = Long.MAX_VALUE): RoamJournalPage
    suspend fun pair(settings: BrainSettings, identity: RoamIdentity, pairingId: String): RoamSnapshot
    suspend fun controls(settings: BrainSettings, revision: Long, controls: RoamControls)
    suspend fun forget(settings: BrainSettings, revision: Long)
    suspend fun deleteEntry(settings: BrainSettings, revision: Long, id: String)
}

interface RoamBindingStore {
    fun read(endpoint: String, createdAt: Long): RoamBinding?
    fun save(endpoint: String, createdAt: Long, binding: RoamBinding)
}

internal fun uuid(value: String): String {
    val parsed = UUID.fromString(value)
    require(parsed.version() == 4 && parsed.variant() == 2 && parsed.toString() == value)
    return value
}

internal fun validateDomains(values: List<String>): List<String> {
    require(values.size <= 20) { "Use at most 20 allowed domains." }
    val normalized = values.map { it.lowercase(Locale.ROOT) }
    val label = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    require(normalized.distinct().size == normalized.size) { "List each allowed domain once." }
    for (domain in normalized) {
        val labels = domain.split('.')
        require(domain.length <= 253 && labels.size >= 2 && labels.all { label.matches(it) } &&
            !labels.last().all(Char::isDigit) && !domain.endsWith(".local") &&
            !domain.endsWith(".localhost") && !domain.endsWith(".internal")) {
            "Use public domains such as nasa.gov, without URLs, paths, ports, or wildcards."
        }
    }
    return normalized
}

internal fun editedControls(current: RoamControls, domains: String, hours: String, daily: String, dollars: String, web: Boolean): RoamControls {
    require(dollars.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,2})?"))) { "Enter a dollar amount with at most two decimal places." }
    val cents = java.math.BigDecimal(dollars).movePointRight(2).intValueExact()
    return current.copy(webEnabled = web, allowedDomains = domains.split(',', '\n').map(String::trim).filter(String::isNotEmpty),
        wakeIntervalHours = hours.toIntOrNull() ?: 0, dailyWakeLimit = daily.toIntOrNull() ?: 0,
        monthlyBudgetCents = cents).validated()
}
