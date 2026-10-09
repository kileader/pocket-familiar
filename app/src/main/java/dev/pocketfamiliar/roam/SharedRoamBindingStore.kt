package dev.pocketfamiliar.roam

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/** Stable retries survive process death; no tokens, journal content, or phone observations. */
class SharedRoamBindingStore(context: Context, name: String = "free-roam-pairing") : RoamBindingStore {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun key(endpoint: String, createdAt: Long): String = MessageDigest.getInstance("SHA-256")
        .digest(endpoint.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + "-$createdAt"

    override fun read(endpoint: String, createdAt: Long): RoamBinding? =
        preferences.getString(key(endpoint, createdAt), null)?.let {
            val json = JSONObject(it)
            RoamBinding(uuid(json.getString("pairingId")), json.optString("familiarId").takeIf(String::isNotBlank)?.let(::uuid))
        }

    override fun save(endpoint: String, createdAt: Long, binding: RoamBinding) {
        val json = JSONObject().put("pairingId", uuid(binding.pairingId))
        binding.familiarId?.let { json.put("familiarId", uuid(it)) }
        check(preferences.edit().putString(key(endpoint, createdAt), json.toString()).commit()) {
            "The pairing could not be saved."
        }
    }
}
