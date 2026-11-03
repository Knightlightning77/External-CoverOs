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

    /** Alias that always points to the current Flash model of Gemini. */
    const val DEFAULT_MODEL = "gemini-flash-latest"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun apiKey(context: Context): String = prefs(context).getString(KEY, "")?.trim() ?: ""

    fun model(context: Context): String =
        prefs(context).getString(MODEL, "")?.trim()?.removePrefix("models/")
            ?.takeIf { it.isNotEmpty() } ?: DEFAULT_MODEL

    fun savedModel(context: Context): String = prefs(context).getString(MODEL, "") ?: ""

    fun save(context: Context, key: String, model: String) {
        prefs(context).edit().putString(KEY, key.trim()).putString(MODEL, model.trim()).apply()
    }
}
