package com.anythingwithawire.core

import android.content.Context
import android.content.SharedPreferences

/** Settings every app needs, kept in the app's private SharedPreferences. Apps subclass to add their own. */
open class Settings(context: Context) {
    protected val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "")!!
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL)!!
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    var language: String
        get() = prefs.getString("language", DEFAULT_LANGUAGE)!!
        set(value) = prefs.edit().putString("language", value.trim()).apply()

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        const val DEFAULT_LANGUAGE = "en-AU"
    }
}
