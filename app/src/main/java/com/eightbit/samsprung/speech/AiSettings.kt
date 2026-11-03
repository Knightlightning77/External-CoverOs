package com.eightbit.samsprung.speech

import android.content.Context

/**
 * Settings of the dictation with AI. They live in their own preferences file, which is left out
 * of the backups so the API key never leaves this phone.
 */
object AiSettings {
    private const val FILE = "ai_dictation"
    private const val KEY = "geminiKey"
    private const val MODEL = "geminiModel"
    private const val AI_MODE = "aiMode"

    /** Alias that always points to the current Flash model of Gemini. */
    const val DEFAULT_MODEL = "gemini-flash-latest"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun apiKey(context: Context): String = prefs(context).getString(KEY, "")?.trim() ?: ""

    fun model(context: Context): String =
        prefs(context).getString(MODEL, "")?.trim()?.removePrefix("models/")
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_MODEL

    fun savedModel(context: Context): String = prefs(context).getString(MODEL, "") ?: ""

    /** Which microphone was used last: the one with AI (true, the default) or the normal one. */
    fun aiMode(context: Context): Boolean = prefs(context).getBoolean(AI_MODE, true)

    fun setAiMode(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(AI_MODE, on).apply()
    }

    fun save(context: Context, key: String, model: String) {
        prefs(context).edit().putString(KEY, key.trim()).putString(MODEL, model.trim()).apply()
    }
}
