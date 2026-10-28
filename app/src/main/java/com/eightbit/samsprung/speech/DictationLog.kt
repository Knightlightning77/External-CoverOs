package com.eightbit.samsprung.speech

import android.content.Context
import com.eightbit.samsprung.settings.Preferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny persistent log of what the dictation microphone is doing, so that problems can be
 * diagnosed on a phone without a computer. Shown from the settings screen (long press on the
 * dictation option).
 */
object DictationLog {
    private const val KEY = "dictationLog"
    private const val MAX_LINES = 40

    @Synchronized
    fun add(context: Context, message: String) {
        try {
            val prefs = context.getSharedPreferences(Preferences.prefsValue, Context.MODE_PRIVATE)
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val lines = (prefs.getString(KEY, "") ?: "").split('\n').filter { it.isNotBlank() }
            // Do not repeat the exact same message over and over
            if (lines.lastOrNull()?.substringAfter(' ') == message) return
            val kept = (lines + "$time $message").takeLast(MAX_LINES)
            prefs.edit().putString(KEY, kept.joinToString("\n")).apply()
        } catch (_: Exception) { }
    }

    fun read(context: Context): String {
        return context.getSharedPreferences(Preferences.prefsValue, Context.MODE_PRIVATE)
            .getString(KEY, "")?.takeIf { it.isNotBlank() } ?: "(vacío)"
    }

    fun clear(context: Context) {
        context.getSharedPreferences(Preferences.prefsValue, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
