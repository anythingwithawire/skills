package com.anythingwithawire.commentator

import android.content.Context
import com.anythingwithawire.core.Settings

class CommentatorSettings(context: Context) : Settings(context) {

    var intervalSeconds: Int
        get() = prefs.getInt("interval_seconds", DEFAULT_INTERVAL_SECONDS)
        set(value) = prefs.edit().putInt("interval_seconds", value).apply()

    var jurisdiction: String
        get() = prefs.getString("jurisdiction", DEFAULT_JURISDICTION)!!
        set(value) = prefs.edit().putString("jurisdiction", value.trim()).apply()

    /** Ephemeral: each transcript chunk is discarded once sent; Claude keeps only its own comments. */
    var ephemeral: Boolean
        get() = prefs.getBoolean("ephemeral", true)
        set(value) = prefs.edit().putBoolean("ephemeral", value).apply()

    var lastContext: String
        get() = prefs.getString("last_context", "")!!
        set(value) = prefs.edit().putString("last_context", value).apply()

    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 25
        const val MIN_INTERVAL_SECONDS = 10
        const val DEFAULT_JURISDICTION = "Western Australia, Australia"
    }
}
