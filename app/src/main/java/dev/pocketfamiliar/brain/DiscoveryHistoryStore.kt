package dev.pocketfamiliar.brain

import android.content.Context

/** Presentation history only. No conversations, user activity, or canonical creature fields. */
class DiscoveryHistoryStore(context: Context, name: String = "discovery-history") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    fun read(): List<String> = preferences.getString("recent-ids", "").orEmpty()
        .split(',').filter { it.matches(Regex("[a-z0-9-]{1,64}")) }.distinct().takeLast(8)

    fun remember(id: String) {
        require(id.matches(Regex("[a-z0-9-]{1,64}")))
        preferences.edit().putString("recent-ids", (read().filterNot { it == id } + id).takeLast(8).joinToString(",")).apply()
    }
}
