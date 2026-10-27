package com.anythingwithawire.bsdetector

import android.content.Context
import com.anythingwithawire.core.Settings

class DetectorSettings(context: Context) : Settings(context) {

    /** 0 (flag only blatant bullshit) to 100 (flag the faintest whiff). */
    var sensitivity: Int
        get() = prefs.getInt("sensitivity", DEFAULT_SENSITIVITY)
        set(value) = prefs.edit().putInt("sensitivity", value).apply()

    companion object {
        const val DEFAULT_SENSITIVITY = 50

        /** Score at or above which the screen flashes: sensitivity 0 needs 90, 100 needs 10. */
        fun threshold(sensitivity: Int): Int = 90 - (sensitivity * 80) / 100
    }
}
