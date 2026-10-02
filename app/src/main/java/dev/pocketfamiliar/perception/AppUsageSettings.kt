package dev.pocketfamiliar.perception

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.util.Locale

/** Observation preferences are separate from the Room creature and disabled by default. */
class AppUsageSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("app_observations", Context.MODE_PRIVATE)

    val enabled: Boolean get() = preferences.getBoolean("enabled", false)
    val selectedPackages: Set<String>
        get() = preferences.getStringSet("selected_packages", emptySet())?.toSet().orEmpty().take(MAX_SELECTED_APPS).toSet()

    fun setEnabled(enabled: Boolean) { preferences.edit().putBoolean("enabled", enabled).apply() }

    fun setSelectedPackages(packages: Set<String>) {
        require(packages.size <= MAX_SELECTED_APPS)
        preferences.edit().putStringSet("selected_packages", packages.toSet()).apply()
    }

    companion object { const val MAX_SELECTED_APPS = 8 }
}

data class ObservableApp(val packageName: String, val displayName: String)

/** Only launchable apps are listed; no QUERY_ALL_PACKAGES permission is needed. */
@Suppress("DEPRECATION")
fun listObservableApps(context: Context): List<ObservableApp> {
    val manager = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return manager.queryIntentActivities(launcher, 0)
        .filter { it.activityInfo.packageName != context.packageName }
        .map { ObservableApp(it.activityInfo.packageName, normalizeAppName(it.loadLabel(manager).toString())) }
        .distinctBy { it.packageName }
        .sortedWith(compareBy<ObservableApp> { it.displayName.lowercase(Locale.ROOT) }.thenBy { it.packageName })
}

/** Labels are untrusted display text; keep them within the gateway's JSON contract. */
internal fun normalizeAppName(label: String): String = label
    .replace(Regex("[\\s\\p{Cc}]+"), " ").trim().take(80).ifBlank { "Unnamed app" }

@Suppress("DEPRECATION")
fun hasAppUsageAccess(context: Context): Boolean = try {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps?.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    } else {
        appOps?.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    }
    mode == AppOpsManager.MODE_ALLOWED
} catch (_: Exception) { false }
