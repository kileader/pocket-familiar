package dev.pocketfamiliar.brain

import android.content.Context

/** Private app settings; excluded from backup along with the rest of this prototype. */
class BrainSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("brain-settings", Context.MODE_PRIVATE)
    fun read() = BrainSettings(
        preferences.getString("endpoint", "").orEmpty(),
        preferences.getString("access-token", "").orEmpty(),
    )
    fun save(settings: BrainSettings) {
        val valid = settings.validated()
        preferences.edit().putString("endpoint", valid.endpoint)
            .putString("access-token", valid.accessToken).apply()
    }
}
