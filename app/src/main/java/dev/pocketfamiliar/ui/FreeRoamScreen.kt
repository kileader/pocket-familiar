package dev.pocketfamiliar.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.pocketfamiliar.roam.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun FreeRoamScreen(
    state: FreeRoamUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onConnect: () -> Unit,
    onSaveControls: (RoamControls) -> Unit,
    onLoadOlder: () -> Unit,
    onForget: () -> Unit,
    onDeleteEntry: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    var controlsOpen by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val snapshot = state.snapshot
    val writable = state.connected && state.fresh && !state.busy

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight().safeDrawingPadding()
            .padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onBack) { Text("← Familiar") }
                    TextButton(onClick = onRefresh, enabled = state.configured && !state.busy) { Text("Refresh journal") }
                }
                Text("Free Roam journal", style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Serif)
                Text("A record of what your familiar chose to do.", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp))
            }
            if (!state.configured) {
                item { JournalCard {
                    Text("Connect your backend first", style = MaterialTheme.typography.titleMedium)
                    Text("Return to the familiar, open developer details, and save your voice service URL and access token. Free Roam uses the same private connection.")
                } }
            }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }
            state.loadedAt?.let { loaded ->
                item { Text((if (state.fresh) "Loaded " else "Last loaded · may be outdated · ") + journalTime(loaded),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (snapshot != null && !state.connected) {
                item { JournalCard {
                    Text("Pair this familiar", style = MaterialTheme.typography.titleMedium)
                    Text("Pairing saves its original creation time and curiosity on your backend. Energy, pokes, and phone activity stay on this phone. Pairing alone does not start paid wakes.")
                    TextButton(onClick = { confirm = "pair" }, enabled = !state.pairingBlocked && state.fresh && !state.busy) {
                        Text(if (snapshot.profile == null) "Pair existing familiar" else "Connect existing pairing")
                    }
                } }
            }
            if (state.connected && snapshot != null) {
                item { JournalCard {
                    Text(if (snapshot.controls.enabled) "Free Roam is enabled" else "Free Roam is paused",
                        style = MaterialTheme.typography.titleMedium)
                    Text("When enabled, server wakes continue while Android is closed. They do not change the creature’s energy or sleep mode.")
                    Text("Every " + snapshot.controls.wakeIntervalHours + " hours · up to " +
                        snapshot.controls.dailyWakeLimit + " wakes/day · " + money(snapshot.controls.monthlyBudgetCents * 10_000L, 2) + "/month API budget",
                        style = MaterialTheme.typography.bodyMedium)
                    Text(if (snapshot.controls.webEnabled) "Web search: " + snapshot.controls.allowedDomains.joinToString()
                        else "Web search is off · reflection and rest only", style = MaterialTheme.typography.bodySmall)
                    if (!snapshot.executionAvailable) Text("Update the backend before starting autonomous wakes.")
                    if (snapshot.controls.monthlyBudgetCents < 5) Text("A wake requires at least $0.05 of available API budget.")
                    snapshot.monthlyCharges.firstOrNull()?.let {
                        Text("Recorded usage (" + it.month + " UTC): " + money(it.chargedMicrousd, 4) +
                            " · includes reservations", style = MaterialTheme.typography.bodySmall)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            if (snapshot.controls.enabled) onSaveControls(snapshot.controls.copy(enabled = false))
                            else confirm = "start"
                        }, enabled = writable && (snapshot.controls.enabled ||
                            (snapshot.executionAvailable && snapshot.controls.monthlyBudgetCents >= 5))) {
                            Text(if (snapshot.controls.enabled) "Pause Free Roam" else "Start Free Roam")
                        }
                        TextButton(onClick = { controlsOpen = !controlsOpen }, enabled = !state.busy) {
                            Text(if (controlsOpen) "Hide controls" else "Permissions and limits")
                        }
                    }
                } }
                if (controlsOpen) {
                    item { key(snapshot.revision) {
                        ControlEditor(snapshot.controls, snapshot.webAvailable, writable, onSaveControls)
                    } }
                    item { JournalCard {
                        if (snapshot.interests.isNotEmpty()) Text("Interests: " + snapshot.interests.joinToString())
                        snapshot.goals.filter { it.status == "active" }.forEach {
                            Text(it.topic + " · " + it.question, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("Forget Free Roam memory", style = MaterialTheme.typography.titleMedium)
                        Text("Erase notes, interests, goals, and journal content. Pairing and local creature state stay intact; accounting stays to preserve limits.")
                        TextButton(onClick = { confirm = "forget" }, enabled = writable) { Text("Forget all Free Roam memory") }
                    } }
                }
                if (state.entries.isEmpty()) item { JournalCard {
                    Text("No activity recorded yet", style = MaterialTheme.typography.titleMedium)
                    Text(if (snapshot.controls.enabled) "The first eligible wake is checked within a minute. Budget and daily limits can prevent a wake."
                        else "Choose your permissions and limits, then start Free Roam when you’re ready.")
                } }
            }
            items(state.entries, key = { it.id }) { entry ->
                JournalEntry(entry, writable) { confirm = entry.id }
            }
            if (state.nextCursor != null) item {
                TextButton(onClick = onLoadOlder, enabled = writable, modifier = Modifier.fillMaxWidth()) { Text("Load older activity") }
            }
            item { Text("Costs are conservative API estimates, not a billing guarantee. Hosting and Listen are separate. Findings can be wrong; source links show what was consulted.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    confirm?.let { action ->
        val title = when (action) { "pair" -> "Connect this familiar?"; "start" -> "Start paid server wakes?"
            "forget" -> "Forget all Free Roam memory?"; else -> "Forget this journal entry?" }
        val description = when (action) {
            "pair" -> "Only the creation time and curiosity are uploaded. The existing familiar and its saved state are preserved."
            "start" -> "This permits sending curiosity, interests, goals, and recent memories to OpenAI. Wakes can incur API charges and continue when the app is closed, within your saved limits. Web search follows its separate permission. You can pause here."
            "forget" -> "This clears all cognitive content and pauses Free Roam. It preserves the familiar’s identity, phone state, and spending receipts. Previously exported copies and backups may retain old content."
            else -> "This removes the journal text and its associated generated memory. Its content-free spending receipt remains."
        }
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(title) },
            text = { Text(description, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(enabled = !state.busy && state.fresh && !state.pairingBlocked &&
                (action == "pair" || writable) && (action != "start" || snapshot?.executionAvailable == true), onClick = {
                when (action) {
                    "pair" -> onConnect()
                    "start" -> snapshot?.let { onSaveControls(it.controls.copy(enabled = true)) }
                    "forget" -> onForget()
                    else -> onDeleteEntry(action)
                }
                confirm = null
            }) { Text(when (action) { "pair" -> "Connect familiar"; "start" -> "Start Free Roam"; else -> "Forget" }) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}

@Composable
private fun JournalCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun ControlEditor(current: RoamControls, webAvailable: Boolean, writable: Boolean, onSave: (RoamControls) -> Unit) {
    var web by remember { mutableStateOf(current.webEnabled) }
    var domains by remember { mutableStateOf(current.allowedDomains.joinToString(", ")) }
    var hours by remember { mutableStateOf(current.wakeIntervalHours.toString()) }
    var daily by remember { mutableStateOf(current.dailyWakeLimit.toString()) }
    var dollars by remember { mutableStateOf("%.2f".format(Locale.US, current.monthlyBudgetCents / 100.0)) }
    var error by remember { mutableStateOf<String?>(null) }
    JournalCard {
        Text("Permissions and limits", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Allow web search", modifier = Modifier.weight(1f))
            Switch(checked = web, onCheckedChange = { web = it }, enabled = writable && webAvailable)
        }
        Text("At most one search per wake, restricted to these domains and their subdomains.")
        OutlinedTextField(value = domains, onValueChange = { domains = it }, label = { Text("Allowed domains") },
            supportingText = { Text("Comma separated · at most 20") },
            enabled = writable, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = hours, onValueChange = { hours = it }, label = { Text("Wake interval (hours)") },
            supportingText = { Text("6–168 hours") },
            singleLine = true, enabled = writable, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = daily, onValueChange = { daily = it }, label = { Text("Daily wakes") },
            supportingText = { Text("1–4 wakes") },
            singleLine = true, enabled = writable, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = dollars, onValueChange = { dollars = it }, label = { Text("Monthly budget (USD)") },
            supportingText = { Text("API calls · $0–$100") },
            singleLine = true, enabled = writable, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Text("Each wake reserves $0.05. Zero budget prevents wakes. A request already sent may finish and remain billable when you pause.")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(enabled = writable, onClick = {
            try { onSave(editedControls(current, domains, hours, daily, dollars, web)); error = null }
            catch (invalid: IllegalArgumentException) { error = invalid.message }
        }) { Text("Save Free Roam controls") }
    }
}

@Composable
private fun JournalEntry(entry: RoamJournalEntry, writable: Boolean, onForget: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    JournalCard {
        Text(journalTime(entry.startedAt), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(entry.topic ?: "A server wake", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
        Text(listOfNotNull(entry.action?.replaceFirstChar(Char::uppercase), entry.status.replaceFirstChar(Char::uppercase)).joinToString(" · "),
            color = MaterialTheme.colorScheme.primary)
        entry.question?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        entry.reason?.let { Text("Why: $it", style = MaterialTheme.typography.bodySmall) }
        entry.summary?.let { Text(it) }
        if (entry.action == "reflect" && entry.summary != null) Text("Reflection · a thought, not a verified finding",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        entry.errorCode?.let { Text(journalError(it), color = MaterialTheme.colorScheme.error) }
        entry.sources.forEach { source ->
            TextButton(onClick = {
                try { uriHandler.openUri(source.url) }
                catch (_: Exception) { Toast.makeText(context, "No browser is available for this source.", Toast.LENGTH_SHORT).show() }
            }) { Text("Source: " + source.title) }
        }
        Text(money(entry.chargedMicrousd, 4) + (if (entry.status == "running") " reserved" else " estimated") +
            " · " + entry.modelCalls + " model calls", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onForget, enabled = writable) { Text("Forget this entry") }
    }
}

internal fun journalTime(millis: Long): String = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.getDefault())
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
internal fun money(microusd: Long, decimals: Int) = "$" + String.format(Locale.US, "%." + decimals + "f", microusd / 1_000_000.0)
internal fun journalError(code: String) = when {
    code.matches(Regex("provider_http_[0-9]{3}")) -> "The provider rejected this wake (HTTP " + code.removePrefix("provider_http_") + ")."
    code == "provider_timeout" -> "The provider timed out. This wake was not retried."
    code == "provider_network_error" -> "The provider could not be reached."
    code == "worker_lost" -> "The worker was interrupted. This wake was not replayed."
    code == "permission_or_context_changed" -> "Permissions or memories changed during this wake."
    else -> "This wake could not record a valid result. No finding was saved."
}
