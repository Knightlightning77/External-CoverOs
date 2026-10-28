package com.eightbit.samsprung.speech

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.Toast
import com.eightbit.content.ScaledContext
import com.eightbit.samsprung.R

/**
 * Floating microphone shown next to the focused text field of any app. It is drawn by the
 * accessibility service (accessibility overlay window, no extra permission) and writes the
 * dictated text into the field with ACTION_SET_TEXT.
 *
 * All methods must be called from the main thread.
 */
class SystemDictation(private val service: AccessibilityService) : DictationHelper.Listener {

    private val dictation = DictationHelper(service, this)

    private var windowManager: WindowManager? = null
    private var micView: ImageView? = null
    private var micParams: WindowManager.LayoutParams? = null
    private var micDisplayId = -1

    private var target: AccessibilityNodeInfo? = null
    private var baseText: CharSequence = ""
    private val handler = Handler(Looper.getMainLooper())

    /** Shows (or moves) the microphone next to [field]. */
    fun show(field: AccessibilityNodeInfo, displayId: Int = -1) {
        if (dictation.isListening && target?.let { it == field } == true) return
        // Only for the cover display: the main display has its own keyboard and dictation
        if (pickDisplay(displayId).displayId == Display.DEFAULT_DISPLAY) {
            DictationLog.add(service, "campo en pantalla principal (pantalla=$displayId): no se muestra")
            hide()
            return
        }
        target = field
        val bounds = Rect()
        field.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            DictationLog.add(service, "campo sin tamaño, no se muestra")
            hide()
            return
        }
        try {
            DictationLog.add(service, "mostrar mic: pantalla=$displayId campo=${bounds.flattenToString()}")
            val view = ensureView(displayId)
            val wm = windowManager ?: return
            val params = micParams ?: return
            val size = params.width
            val screen = wm.currentWindowMetrics.bounds
            params.x = (bounds.right - size - margin()).coerceIn(0, (screen.width() - size).coerceAtLeast(0))
            params.y = (bounds.top - size / 2).coerceIn(0, (screen.height() - size).coerceAtLeast(0))
            if (view.parent == null) wm.addView(view, params) else wm.updateViewLayout(view, params)
            DictationLog.add(service, "mic mostrado en pantalla $micDisplayId")
        } catch (ex: Exception) {
            // The window could not be added (display gone, service shutting down...)
            DictationLog.add(service, "ERROR al mostrar: ${ex.javaClass.simpleName}: ${ex.message}")
            micView = null
        }
    }

    /** Removes the microphone and stops any dictation in progress. */
    fun hide() {
        if (dictation.isListening) dictation.stop()
        target = null
        micView?.let {
            try {
                if (it.parent != null) windowManager?.removeView(it)
            } catch (_: Exception) { }
        }
    }

    fun destroy() {
        hide()
        dictation.destroy()
        micView = null
        windowManager = null
    }

    private fun margin(): Int = (6 * service.resources.displayMetrics.density).toInt()

    private fun pickDisplay(preferred: Int): Display {
        val dm = service.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        // The display that really holds the field, when the service could find it out
        if (preferred >= 0) dm.getDisplay(preferred)?.let { return it }
        val main = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val cover = dm.getDisplay(1)
        // Flip phones: when the main display is off the user is typing on the cover display
        return if (cover != null && main?.state != Display.STATE_ON) cover else main
    }

    private fun ensureView(preferred: Int): ImageView {
        val display = pickDisplay(preferred)
        if (micView != null && micDisplayId == display.displayId) return micView!!

        // Display changed (fold / unfold) or first use
        micView?.let {
            try {
                if (it.parent != null) windowManager?.removeView(it)
            } catch (_: Exception) { }
        }
        // The accessibility overlay window gets no token on the cover display, so the button is a
        // regular overlay window of this app, exactly like the cover navigation bar. It relies on
        // the "draw over other apps" permission the app already needs.
        if (!Settings.canDrawOverlays(service)) {
            throw IllegalStateException("falta el permiso de mostrar sobre otras apps")
        }
        val context = ScaledContext(service.applicationContext).cover()
        val density = context.resources.displayMetrics.density
        val size = (44 * density).toInt()

        val view = ImageView(context).apply {
            setImageResource(R.drawable.ic_mic_24dp)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            val pad = (10 * density).toInt()
            setPadding(pad, pad, pad, pad)
            background = circle(IDLE_COLOR)
            elevation = 6 * density
            contentDescription = service.getString(R.string.dictation_mic)
            setOnClickListener { onMicClicked() }
        }
        micParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        micView = view
        micDisplayId = display.displayId
        return view
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun onMicClicked() {
        val field = target ?: return
        if (dictation.isListening) {
            dictation.stop()
            return
        }
        field.refresh()
        // A field showing its hint reports the hint as text
        val current = field.text?.toString() ?: ""
        val hint = field.hintText?.toString()
        val description = field.contentDescription?.toString()
        // Many apps (WhatsApp, Telegram...) report the placeholder ("Mensaje") as the text
        val isPlaceholder = field.isShowingHintText ||
                (current.isNotEmpty() && (current == hint || current == description))
        if (isPlaceholder || current.isEmpty()) {
            baseText = ""
            DictationLog.add(service, "texto previo='$current' marcador=true")
            dictation.start()
            return
        }
        // Some apps (WhatsApp, Telegram) report their placeholder ("Mensaje") as the text without
        // marking it. Clear the field for a moment: if it still reports the same text it was only
        // a placeholder, otherwise it was real text and it is put back.
        setFieldText(field, "")
        handler.postDelayed({
            field.refresh()
            val after = field.text?.toString() ?: ""
            if (after.isNotEmpty() && after == current) {
                baseText = ""
            } else {
                baseText = current
                setFieldText(field, current)
            }
            DictationLog.add(service, "texto previo='$current' tras vaciar='$after' " +
                    "marcador=${baseText.isEmpty()}")
            dictation.start()
        }, 250)
    }

    private fun setFieldText(field: AccessibilityNodeInfo, text: CharSequence) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun writeText(text: String) {
        val field = target ?: return
        field.refresh()
        val joined = StringBuilder(baseText)
        if (joined.isNotEmpty() && !joined.last().isWhitespace()) joined.append(' ')
        joined.append(text)
        setFieldText(field, joined.toString())
    }

    override fun onListening(active: Boolean) {
        micView?.background = circle(if (active) ACTIVE_COLOR else IDLE_COLOR)
    }

    override fun onText(text: String, isFinal: Boolean) {
        writeText(text)
    }

    override fun onError(code: Int) {
        DictationHelper.messageFor(code)?.let {
            Toast.makeText(service, it, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private val IDLE_COLOR = Color.parseColor("#6A3DF0")
        private val ACTIVE_COLOR = Color.parseColor("#E53935")
    }
}
