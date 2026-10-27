package com.anythingwithawire.commentator

import android.content.Context

/** User settings, kept in the app's private SharedPreferences. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "")!!
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL)!!
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    var intervalSeconds: Int
        get() = prefs.getInt("interval_seconds", DEFAULT_INTERVAL_SECONDS)
        set(value) = prefs.edit().putInt("interval_seconds", value).apply()

    var jurisdiction: String
        get() = prefs.getString("jurisdiction", DEFAULT_JURISDICTION)!!
        set(value) = prefs.edit().putString("jurisdiction", value.trim()).apply()

    var preferOnDevice: Boolean
        get() = prefs.getBoolean("prefer_on_device", true)
        set(value) = prefs.edit().putBoolean("prefer_on_device", value).apply()

    var lastContext: String
        get() = prefs.getString("last_context", "")!!
        set(value) = prefs.edit().putString("last_context", value).apply()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val DEFAULT_INTERVAL_SECONDS = 25
        const val MIN_INTERVAL_SECONDS = 10
        const val DEFAULT_JURISDICTION = "Western Australia, Australia"
    }
}
