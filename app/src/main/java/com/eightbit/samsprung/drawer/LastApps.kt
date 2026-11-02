package com.eightbit.samsprung.drawer

import android.content.SharedPreferences
import com.eightbit.samsprung.settings.Preferences

/**
 * The last apps opened on the cover display (most recent first), kept so they can be
 * resumed with the fingerprint button when the screen turns on again.
 */
object LastApps {
    const val MAX = 3

    fun list(prefs: SharedPreferences): List<Pair<String, String?>> {
        val saved = prefs.getString(Preferences.prefLastApps, "") ?: ""
        return saved.split('\n').filter { it.isNotEmpty() }.map {
            it.substringBefore('/') to it.substringAfter('/', "").ifEmpty { null }
        }
    }

    private fun save(prefs: SharedPreferences, apps: List<Pair<String, String?>>) {
        prefs.edit().putString(
            Preferences.prefLastApps,
            apps.joinToString("\n") { "${it.first}/${it.second ?: ""}" }
        ).apply()
    }

    fun add(prefs: SharedPreferences, pkg: String, cls: String?) {
        val apps = list(prefs).filter { it.first != pkg }.toMutableList()
        apps.add(0, pkg to cls)
        save(prefs, apps.take(MAX))
    }

    fun remove(prefs: SharedPreferences, pkg: String) {
        save(prefs, list(prefs).filter { it.first != pkg })
    }

    fun clear(prefs: SharedPreferences) {
        prefs.edit().remove(Preferences.prefLastApps).apply()
    }
}
