package com.eightbit.samsprung.settings

import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Orientation chosen for each app when it is opened on the cover display.
 * [DEFAULT] keeps the normal behavior (the rotation lock of the cover screen).
 */
object AppOrientation {
    const val DEFAULT = 0
    const val VERTICAL = 1
    const val HORIZONTAL = 2

    private const val KEY = "prefAppOrientation"

    private fun read(prefs: SharedPreferences): JSONObject = try {
        JSONObject(prefs.getString(KEY, "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    fun get(prefs: SharedPreferences, packageName: String?): Int {
        if (packageName == null) return DEFAULT
        return read(prefs).optInt(packageName, DEFAULT)
    }

    fun set(prefs: SharedPreferences, packageName: String, mode: Int) {
        val all = read(prefs)
        if (mode == DEFAULT) all.remove(packageName) else all.put(packageName, mode)
        prefs.edit().putString(KEY, all.toString()).apply()
    }
}
