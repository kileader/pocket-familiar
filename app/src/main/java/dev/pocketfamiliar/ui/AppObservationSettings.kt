package dev.pocketfamiliar.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.pocketfamiliar.perception.AppUsageSettings
import dev.pocketfamiliar.perception.ObservableApp
import dev.pocketfamiliar.perception.hasAppUsageAccess
import dev.pocketfamiliar.perception.listObservableApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** User-controlled perception preferences; never modifies the canonical creature. */
@Composable
fun AppObservationSettings(onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val settings = remember(context) { AppUsageSettings(context) }
    val notifyChanged by rememberUpdatedState(onChanged)
    var enabled by remember(settings) { mutableStateOf(settings.enabled) }
    var selected by remember(settings) { mutableStateOf(settings.selectedPackages) }
    var accessGranted by remember(context) { mutableStateOf(hasAppUsageAccess(context)) }
    var showChooser by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf<String?>(null) }

    LifecycleResumeEffect(settings) {
        enabled = settings.enabled
        selected = settings.selectedPackages
        accessGranted = hasAppUsageAccess(context)
        notifyChanged()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("App observations · optional", style = MaterialTheme.typography.titleSmall)
        Text(
            "When you tap Listen, selected app names and approximate foreground minutes from the past hour " +
                "can be sent to your backend and OpenAI. Screen contents, messages, and web pages are not read.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Include selected apps", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                settings.setEnabled(it)
                notifyChanged()
            })
        }
        Text(
            when {
                !enabled -> "Off · app activity is not shared."
                selected.isEmpty() -> "Choose apps below. Nothing is shared until you select at least one."
                !accessGranted -> "Usage Access is needed before your familiar can notice these apps."
                else -> "Ready · ${selected.size} selected ${if (selected.size == 1) "app" else "apps"}."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Usage Access: ${if (accessGranted) "granted" else "not granted"}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            settingsError = null
            try {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Exception) {
                settingsError = "Usage Access settings could not be opened on this phone."
            }
        }) { Text(if (accessGranted) "Manage Usage Access" else "Open Usage Access settings") }
        if (!accessGranted) {
            Text("Find Pocket Familiar in Android settings, allow usage access, then return here.",
                style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { showChooser = true }) { Text("Choose apps (${selected.size}/${AppUsageSettings.MAX_SELECTED_APPS})") }
        settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }

    if (showChooser) {
        AppChooser(selected, onDismiss = { showChooser = false }, onSave = {
            selected = it
            settings.setSelectedPackages(it)
            showChooser = false
            notifyChanged()
        })
    }
}

@Composable
private fun AppChooser(selected: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    val context = LocalContext.current
    var choices by remember { mutableStateOf<List<ObservableApp>?>(null) }
    var draft by remember { mutableStateOf(selected) }
    var search by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(context) {
        try {
            choices = withContext(Dispatchers.IO) { listObservableApps(context) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            error = "The app list could not be loaded."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose apps to notice") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Up to ${AppUsageSettings.MAX_SELECTED_APPS} apps. Only your selections are shared on Listen.")
                OutlinedTextField(search, onValueChange = { search = it }, label = { Text("Find an app") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("${draft.size}/${AppUsageSettings.MAX_SELECTED_APPS} selected", style = MaterialTheme.typography.labelMedium)
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    choices == null -> Text("Loading apps…")
                    choices!!.isEmpty() -> Text("No launchable apps were found.")
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(choices!!.filter { it.displayName.contains(search, ignoreCase = true) }, key = { it.packageName }) { app ->
                            val checked = app.packageName in draft
                            val selectable = checked || draft.size < AppUsageSettings.MAX_SELECTED_APPS
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = selectable) {
                                    draft = if (checked) draft - app.packageName else draft + app.packageName
                                }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked, onCheckedChange = null, enabled = selectable)
                                Text(app.displayName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                TextButton(onClick = { draft = emptySet() }) { Text("Clear selections") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("Save selections") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
