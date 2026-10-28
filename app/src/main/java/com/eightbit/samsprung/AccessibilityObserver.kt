/*
 * ====================================================================
 * Copyright (c) 2021-2023 AbandonedCart.  All rights reserved.
 *
 * See https://github.com/SamSprung/.github/blob/main/LICENSE#L5
 * ====================================================================
 *
 * The license and distribution terms for any publicly available version or
 * derivative of this code cannot be changed.  i.e. this code cannot simply be
 * copied and put under another distribution license
 * [including the GNU Public License.] Content not subject to these terms is
 * subject to to the terms and conditions of the Apache License, Version 2.0.
 */

package com.eightbit.samsprung

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.eightbit.samsprung.settings.Preferences
import com.eightbit.samsprung.speech.DictationLog
import com.eightbit.samsprung.speech.SystemDictation
import java.io.File

class AccessibilityObserver : AccessibilityService() {

    companion object {
        private lateinit var observerInstance: AccessibilityObserver
        private var isConnected: Boolean = false
        fun getInstance() : AccessibilityObserver? {
            return if (isConnected) observerInstance else null
        }
        fun hasEnabledService(context: Context): Boolean {
            if (BuildConfig.GOOGLE_PLAY) return false
            val serviceString = Settings.Secure.getString(context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            return serviceString?.contains(context.packageName
                    + File.separator + AccessibilityObserver::class.java.name) == true
        }
        fun performBackAction() {
            getInstance()?.performGlobalAction(GLOBAL_ACTION_BACK)
        }

        /** Applies the current "dictation microphone" setting to the running service. */
        fun refreshServiceInfo() {
            getInstance()?.applyServiceInfo()
        }

        fun getWindowManager() : WindowManager {
            return getInstance()?.getSystemService(WINDOW_SERVICE) as WindowManager
        }
    }

    init {
        observerInstance = this
    }

    private var dictation: SystemDictation? = null

    private fun isDictationEnabled(): Boolean {
        return !BuildConfig.GOOGLE_PLAY && getSharedPreferences(
            Preferences.prefsValue, Context.MODE_PRIVATE
        ).getBoolean(Preferences.prefDictation, false)
    }

    /**
     * With dictation off the service only watches this app, exactly as before. With it on it
     * also needs to see focus changes in every app to know when a text field is selected.
     */
    private fun applyServiceInfo() {
        val info = serviceInfo ?: AccessibilityServiceInfo()
        if (isDictationEnabled()) {
            info.packageNames = null
            info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            // Needed to find out in which display (main or cover) a window is shown
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            if (null == dictation) dictation = SystemDictation(this)
        } else {
            info.packageNames = arrayOf(packageName)
            info.eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED
            dictation?.destroy()
            dictation = null
        }
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 100
        serviceInfo = info
        DictationLog.add(this, "servicio configurado, dictado=${isDictationEnabled()}")
    }

    override fun onServiceConnected() {
        applyServiceInfo()
        isConnected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val mic = dictation ?: return
        if (event.packageName == packageName) return
        DictationLog.add(this, "evento ${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                "app=${event.packageName}")
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source
                DictationLog.add(this, "foco/clic: editable=${node?.isEditable} " +
                        "contraseña=${node?.isPassword} pantalla=${node?.let { displayOf(it) }}")
                if (node != null && isDictationField(node)) {
                    mic.show(node, displayOf(node))
                } else if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                    mic.hide()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // The keyboard also opens a window; only react to the field that has the focus
                val focused = try {
                    rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                } catch (_: Exception) { null }
                if (focused != null && isDictationField(focused)) {
                    mic.show(focused, displayOf(focused))
                } else {
                    mic.hide()
                }
            }
        }
    }

    private fun isDictationField(node: AccessibilityNodeInfo): Boolean {
        return node.isEditable && !node.isPassword
    }

    /** Display (0 = main, 1 = cover) showing the window of [node], or -1 if unknown. */
    private fun displayOf(node: AccessibilityNodeInfo): Int {
        try {
            val all = windowsOnAllDisplays
            for (i in 0 until all.size()) {
                for (window in all.valueAt(i)) {
                    if (window.id == node.windowId) return all.keyAt(i)
                }
            }
        } catch (_: Exception) { }
        return -1
    }

    override fun onInterrupt() {
        if (null == getInstance()) observerInstance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        dictation?.destroy()
        dictation = null
        return super.onUnbind(intent)
    }
}
