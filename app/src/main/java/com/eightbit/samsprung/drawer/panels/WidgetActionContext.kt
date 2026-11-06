package com.eightbit.samsprung.drawer.panels

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import com.eightbit.samsprung.speech.DictationLog

/**
 * Context given to the widget host views. The cover display context is not an Activity, and
 * widgets send the action of their buttons with [Context.startIntentSender], which only
 * an Activity implements: on a plain context it fails silently and the widget seems dead.
 * Here those calls are handed to the [activity] that owns the window.
 */
class WidgetActionContext(base: Context, private val activity: Activity) : ContextWrapper(base) {

    override fun startIntentSender(
        intent: IntentSender?, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int,
        extraFlags: Int
    ) {
        startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, null)
    }

    override fun startIntentSender(
        intent: IntentSender?, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int,
        extraFlags: Int, options: Bundle?
    ) {
        try {
            val launch = try {
                val displayId = activity.display?.displayId ?: 0
                (if (options != null) ActivityOptions.fromBundle(options)
                else ActivityOptions.makeBasic()).also {
                    if (displayId > 0) it.launchDisplayId = displayId
                }.toBundle()
            } catch (_: Exception) {
                options
            }
            activity.startIntentSender(
                intent, fillInIntent, flagsMask, flagsValues, extraFlags, launch
            )
        } catch (ex: Exception) {
            DictationLog.add(activity, "Widget: acción fallida (${ex.javaClass.simpleName})")
            throw ex
        }
    }
}
