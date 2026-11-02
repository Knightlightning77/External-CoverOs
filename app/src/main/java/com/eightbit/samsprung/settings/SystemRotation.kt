package com.eightbit.samsprung.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.eightbit.samsprung.speech.DictationLog

/**
 * Forces the screen rotation through the system settings ("Modify system settings"
 * permission) while an app with its own orientation is open, and restores it afterwards.
 */
object SystemRotation {
    private const val SAVED = "rotationSaved"
    private const val SAVED_AUTO = "rotationSavedAuto"
    private const val SAVED_USER = "rotationSavedUser"

    fun canWrite(context: Context) = Settings.System.canWrite(context)

    fun requestPermission(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) { }
    }

    /** [mode] is AppOrientation.VERTICAL or HORIZONTAL. */
    fun force(context: Context, mode: Int) {
        val prefs = context.getSharedPreferences(Preferences.prefsValue, Context.MODE_PRIVATE)
        if (!canWrite(context)) {
            DictationLog.add(context, "rotación: falta el permiso de modificar ajustes del sistema")
            return
        }
        try {
            val resolver = context.contentResolver
            if (!prefs.getBoolean(SAVED, false)) {
                prefs.edit()
                    .putBoolean(SAVED, true)
                    .putInt(SAVED_AUTO, Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 1))
                    .putInt(SAVED_USER, Settings.System.getInt(resolver, Settings.System.USER_ROTATION, 0))
                    .apply()
            }
            val rotation = if (mode == AppOrientation.HORIZONTAL) 1 else 0
            Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(resolver, Settings.System.USER_ROTATION, rotation)
            DictationLog.add(context, "rotación: ajustes del sistema user_rotation=$rotation")
        } catch (ex: Exception) {
            DictationLog.add(context, "rotación: ERROR ${ex.javaClass.simpleName}: ${ex.message}")
        }
    }

    fun restore(context: Context) {
        val prefs = context.getSharedPreferences(Preferences.prefsValue, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(SAVED, false)) return
        try {
            if (canWrite(context)) {
                val resolver = context.contentResolver
                Settings.System.putInt(resolver, Settings.System.USER_ROTATION, prefs.getInt(SAVED_USER, 0))
                Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, prefs.getInt(SAVED_AUTO, 1))
                DictationLog.add(context, "rotación: ajustes restaurados")
            }
        } catch (_: Exception) { }
        prefs.edit().putBoolean(SAVED, false).apply()
    }
}
