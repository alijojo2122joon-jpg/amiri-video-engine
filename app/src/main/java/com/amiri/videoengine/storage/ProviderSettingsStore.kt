package com.amiri.videoengine.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A Hugging Face Space the user added by hand in Settings. */
data class CustomSpace(val spaceId: String, val name: String)

/** Non-secret provider settings (on/off toggles, custom Spaces). */
class ProviderSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("amiri_providers", Context.MODE_PRIVATE)

    fun isEnabled(providerId: String, default: Boolean): Boolean =
        prefs.getBoolean("enabled_$providerId", default)

    fun setEnabled(providerId: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_$providerId", enabled).apply()
    }

    fun customSpaces(): List<CustomSpace> {
        val raw = prefs.getString("custom_spaces", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isBlank()) null else CustomSpace(id, o.optString("name", id))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addCustomSpace(spaceId: String) {
        val id = normalizeSpaceId(spaceId) ?: return
        val list = customSpaces().filter { it.spaceId != id } + CustomSpace(id, id.substringAfter('/'))
        save(list)
    }

    fun removeCustomSpace(spaceId: String) {
        save(customSpaces().filter { it.spaceId != spaceId })
    }

    private fun save(list: List<CustomSpace>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.spaceId).put("name", it.name)) }
        prefs.edit().putString("custom_spaces", arr.toString()).apply()
    }

    companion object {
        /** Accepts "owner/name" or a full huggingface.co/spaces/owner/name link. */
        fun normalizeSpaceId(input: String): String? {
            var s = input.trim().removeSuffix("/")
            val marker = "huggingface.co/spaces/"
            val idx = s.indexOf(marker)
            if (idx >= 0) s = s.substring(idx + marker.length)
            val parts = s.split('/').filter { it.isNotBlank() }
            if (parts.size < 2) return null
            val owner = parts[0]
            val name = parts[1]
            val valid = Regex("^[A-Za-z0-9._-]+$")
            if (!valid.matches(owner) || !valid.matches(name)) return null
            return "$owner/$name"
        }
    }
}
