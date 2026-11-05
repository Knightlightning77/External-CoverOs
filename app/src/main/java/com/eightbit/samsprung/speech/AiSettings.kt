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
    private const val OPENAI_KEY = "openAiKey"
    private const val OPENAI_MODEL = "openAiModel"

    /** Alias that always points to the current Flash model of Gemini. */
    const val DEFAULT_MODEL = "gemini-flash-latest"

    /** Model of OpenAI that understands audio, used when Gemini fails. */
    const val DEFAULT_OPENAI_MODEL = "gpt-audio-1.5"

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

    fun openAiKey(context: Context): String = prefs(context).getString(OPENAI_KEY, "")?.trim() ?: ""

    fun openAiModel(context: Context): String =
        prefs(context).getString(OPENAI_MODEL, "")?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_OPENAI_MODEL

    fun savedOpenAiModel(context: Context): String = prefs(context).getString(OPENAI_MODEL, "") ?: ""

    fun save(context: Context, key: String, model: String, openAiKey: String, openAiModel: String) {
        prefs(context).edit()
            .putString(KEY, key.trim())
            .putString(MODEL, model.trim())
            .putString(OPENAI_KEY, openAiKey.trim())
            .putString(OPENAI_MODEL, openAiModel.trim())
            .apply()
    }
}
