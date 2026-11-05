package com.eightbit.samsprung.speech

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.DisplayMetrics
import android.util.TypedValue
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.eightbit.content.ScaledContext
import com.eightbit.samsprung.R
import kotlin.math.hypot

/**
 * Floating microphone shown next to the focused text field of any app. It is drawn by the
 * accessibility service (accessibility overlay window, no extra permission) and writes the
 * dictated text into the field with ACTION_SET_TEXT.
 *
 * All methods must be called from the main thread.
 */
class SystemDictation(private val service: AccessibilityService) : DictationHelper.Listener {

    private val dictation = AiDictation(service, this)

    /** Microphone with AI (lilac) or normal microphone (green). A swipe over the button changes it. */
    private var aiMode = AiSettings.aiMode(service)

    init {
        dictation.useAi = aiMode
    }

    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var micView: ImageView? = null
    private var micParams: WindowManager.LayoutParams? = null
    private var micDisplayId = -1
    private var viewContext: Context? = null
    private var statusView: TextView? = null
    private var statusAnimator: ObjectAnimator? = null

    private var target: AccessibilityNodeInfo? = null
    private var baseText: CharSequence = ""
    private val handler = Handler(Looper.getMainLooper())

    /** Text known to be really typed in the field (not an app placeholder) and when it was set. */
    private var verifiedText: String? = null
    private var verifiedAt = 0L
    /** Placeholder texts ("Mensaje"...) that apps report as if they were the content. */
    private val placeholders = HashSet<String>()
    private var probing = false

    /** Size of the buttons in pixels, fixed for the display (it never depends on app scaling). */
    private var buttonSize = 0

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
            val size = params.height
            val screen = wm.currentWindowMetrics.bounds
            params.x = (bounds.right - params.width - margin())
                .coerceIn(0, (screen.width() - params.width).coerceAtLeast(0))
            params.y = (bounds.top - size / 2).coerceIn(0, (screen.height() - size).coerceAtLeast(0))
            if (view.parent == null) wm.addView(view, params) else wm.updateViewLayout(view, params)
            DictationLog.add(service, "mic mostrado en pantalla $micDisplayId")
        } catch (ex: Exception) {
            // The window could not be added (display gone, service shutting down...)
            DictationLog.add(service, "ERROR al mostrar: ${ex.javaClass.simpleName}: ${ex.message}")
            micView = null
            rootView = null
        }
    }

    /** Removes the microphone and stops any dictation in progress. */
    fun hide() {
        if (dictation.isListening || dictation.isProcessing) dictation.destroy()
        target = null
        removeWindow()
    }

    private fun removeWindow() {
        stopRepeat()
        hideStatus()
        rootView?.let {
            try {
                if (it.parent != null) windowManager?.removeView(it)
            } catch (_: Exception) { }
        }
    }

    fun destroy() {
        hide()
        dictation.destroy()
        micView = null
        rootView = null
        windowManager = null
    }

    private fun margin(): Int = buttonSize / 10

    private fun pickDisplay(preferred: Int): Display {
        val dm = service.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        // The display that really holds the field, when the service could find it out
        if (preferred >= 0) dm.getDisplay(preferred)?.let { return it }
        val main = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val cover = dm.getDisplay(1)
        // Flip phones: when the main display is off the user is typing on the cover display
        return if (cover != null && main?.state != Display.STATE_ON) cover else main
    }

    private fun ensureView(preferred: Int): LinearLayout {
        val display = pickDisplay(preferred)
        rootView?.let { if (micDisplayId == display.displayId) return it }

        // Display changed (fold / unfold) or first use
        removeWindow()
        // The accessibility overlay window gets no token on the cover display, so the button is a
        // regular overlay window of this app, exactly like the cover navigation bar. It relies on
        // the "draw over other apps" permission the app already needs.
        if (!Settings.canDrawOverlays(service)) {
            throw IllegalStateException("falta el permiso de mostrar sobre otras apps")
        }
        val context = ScaledContext(service.applicationContext).cover()
        // The size comes from the real width of the display. The density of the contexts used by
        // the app is changed in other places, which made the buttons grow and shrink.
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val width = minOf(metrics.widthPixels, metrics.heightPixels)
        val size = (width * BUTTON_WIDTH_RATIO).toInt().coerceAtLeast(48)
        val pad = size * 15 / 66
        val gap = size * 12 / 66
        val elevationPx = size * 6f / 66
        buttonSize = size
        viewContext = context
        DictationLog.add(service, "mic: ancho pantalla=$width tamaño botones=$size")

        val mic = ImageView(context).apply {
            setImageResource(R.drawable.ic_mic_24dp)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(pad, pad, pad, pad)
            background = circle(idleColor())
            elevation = elevationPx
            contentDescription = micDescription()
            // A tap dictates, a swipe over the button changes between the two microphones
            var downX = 0f
            var downY = 0f
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        v.isPressed = true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        if (hypot(event.rawX - downX, event.rawY - downY) > size * 0.4f)
                            toggleMode()
                        else
                            onMicClicked()
                    }
                    MotionEvent.ACTION_CANCEL -> v.isPressed = false
                }
                true
            }
        }
        val erase = ImageView(context).apply {
            setImageResource(R.drawable.ic_backspace_24dp)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setPadding(pad, pad, pad, pad)
            background = circle(ERASE_COLOR)
            elevation = elevationPx
            contentDescription = service.getString(R.string.dictation_erase)
            // Tap deletes one character, holding the button keeps deleting
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        stopRepeat()
                        if (deleteLast()) handler.postDelayed(repeatDelete, 450)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        stopRepeat()
                    }
                }
                true
            }
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(erase, LinearLayout.LayoutParams(size, size).apply { marginEnd = gap })
            addView(mic, LinearLayout.LayoutParams(size, size))
        }
        micParams = WindowManager.LayoutParams(
            size * 2 + gap, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        micView = mic
        rootView = view
        micDisplayId = display.displayId
        return view
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun idleColor() = if (aiMode) AI_COLOR else NORMAL_COLOR

    private fun micDescription() = service.getString(
        if (aiMode) R.string.dictation_mic_ai else R.string.dictation_mic
    )

    /** Swipe over the microphone: changes between the normal dictation and the one with AI. */
    private fun toggleMode() {
        if (dictation.isListening || dictation.isProcessing) dictation.destroy()
        aiMode = !aiMode
        dictation.useAi = aiMode
        AiSettings.setAiMode(service, aiMode)
        DictationLog.add(service, "micrófono: ${if (aiMode) "IA" else "normal"}")
        micView?.background = circle(idleColor())
        micView?.contentDescription = micDescription()
    }

    private fun onMicClicked() {
        stopRepeat()
        probing = false
        // A second tap while the text is being prepared cancels it
        if (dictation.isProcessing) {
            dictation.destroy()
            return
        }
        val field = target ?: return
        if (dictation.isListening) {
            dictation.stop()
            return
        }
        field.refresh()
        // A field showing its hint reports the hint as text
        val current = field.text?.toString() ?: ""
        if (current.isNotEmpty() && current == verifiedText) {
            baseText = current
            dictation.start()
            return
        }
        if (current in placeholders) {
            baseText = ""
            dictation.start()
            return
        }
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
                placeholders.add(current)
            } else {
                baseText = current
                setFieldText(field, current)
                markVerified(current)
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
        markVerified(joined.toString())
    }

    private fun markVerified(text: String) {
        verifiedText = text
        verifiedAt = SystemClock.uptimeMillis()
    }

    private val repeatDelete = object : Runnable {
        override fun run() {
            // Stop as soon as there is nothing left to delete
            if (deleteLast()) handler.postDelayed(this, 120)
        }
    }

    private fun stopRepeat() {
        handler.removeCallbacks(repeatDelete)
    }

    /**
     * Deletes the last character of the field, ignoring app placeholders.
     * Returns false when there is nothing (more) to delete.
     */
    private fun deleteLast(): Boolean {
        val field = target ?: return false
        // Waiting for the check of a previous press: keep going, but do nothing yet
        if (probing) return true
        // A selected text goes away at once (and holding the button does not go on deleting)
        if (deleteSelection(field)) return false

        val recent = verifiedText != null && SystemClock.uptimeMillis() - verifiedAt < 1500
        val current: String
        if (recent) {
            // The field may not have shown our last change yet, so trust what we wrote
            current = verifiedText ?: return false
        } else {
            field.refresh()
            current = field.text?.toString() ?: ""
        }
        if (current.isEmpty() || field.isShowingHintText || current in placeholders) return false
        // A dictation in progress would write its text again right after the deletion
        if (dictation.isListening || dictation.isProcessing) dictation.destroy()
        if (recent || current == verifiedText) {
            eraseOne(field, current)
            return true
        }
        // Not sure whether this is real text or a placeholder: clear and look again
        probing = true
        setFieldText(field, "")
        handler.postDelayed({
            probing = false
            field.refresh()
            val after = field.text?.toString() ?: ""
            if (after.isNotEmpty() && after == current) {
                placeholders.add(current)
            } else {
                eraseOne(field, current)
            }
        }, 250)
        return true
    }

    /** Deletes the selected text in one go. Returns false when nothing is selected. */
    private fun deleteSelection(field: AccessibilityNodeInfo): Boolean {
        field.refresh()
        val text = field.text?.toString() ?: return false
        val start = minOf(field.textSelectionStart, field.textSelectionEnd)
        val end = maxOf(field.textSelectionStart, field.textSelectionEnd)
        if (start < 0 || end <= start || end > text.length) return false
        if (field.isShowingHintText || text in placeholders) return false
        if (dictation.isListening || dictation.isProcessing) dictation.destroy()
        val next = text.removeRange(start, end)
        setFieldText(field, next)
        // The cursor stays where the selection was
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, start)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        markVerified(next)
        DictationLog.add(service, "borrada la selección (${end - start} caracteres)")
        if (next.isEmpty()) {
            handler.postDelayed({
                field.refresh()
                field.text?.toString()?.takeIf { it.isNotEmpty() }?.let { placeholders.add(it) }
            }, 250)
        }
        return true
    }

    private fun eraseOne(field: AccessibilityNodeInfo, current: String) {
        val next = if (current.isEmpty()) "" else
            current.substring(0, current.offsetByCodePoints(current.length, -1))
        setFieldText(field, next)
        markVerified(next)
        if (next.isEmpty()) {
            // The app now shows its placeholder: learn it so it is never taken for real text
            handler.postDelayed({
                field.refresh()
                field.text?.toString()?.takeIf { it.isNotEmpty() }?.let { placeholders.add(it) }
            }, 250)
        }
    }

    override fun onListening(active: Boolean) {
        micView?.background = circle(if (active) ACTIVE_COLOR else idleColor())
    }

    override fun onProcessing(active: Boolean) {
        micView?.background = circle(if (active) PROCESSING_COLOR else idleColor())
        if (!active) hideStatus()
    }

    override fun onStatus(message: String?) {
        if (message == null) hideStatus() else showStatus(message)
    }

    /** Small blinking box next to the buttons saying what the AI dictation is doing. */
    private fun showStatus(text: String) {
        val wm = windowManager ?: return
        val context = viewContext ?: return
        val params = micParams ?: return
        try {
            val screen = wm.currentWindowMetrics.bounds
            val gap = margin()
            val label = statusView ?: TextView(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, buttonSize * 0.2f)
                setPadding(buttonSize / 5, buttonSize / 10, buttonSize / 5, buttonSize / 10)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E6263238"))
                    cornerRadius = buttonSize * 0.25f
                }
            }.also { statusView = it }
            label.maxWidth = screen.width() - 2 * gap
            label.text = text
            label.measure(
                View.MeasureSpec.makeMeasureSpec(screen.width() - 2 * gap, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.UNSPECIFIED
            )
            val width = label.measuredWidth
            val height = label.measuredHeight
            val layout = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            layout.x = (params.x + params.width - width).coerceIn(0, (screen.width() - width).coerceAtLeast(0))
            // Above the buttons, or below them when there is no room
            layout.y = if (params.y - height - gap >= 0) params.y - height - gap
                else params.y + params.height + gap
            if (label.parent == null) wm.addView(label, layout) else wm.updateViewLayout(label, layout)
            if (statusAnimator == null) {
                statusAnimator = ObjectAnimator.ofFloat(label, View.ALPHA, 1f, 0.3f).apply {
                    duration = 600
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
        } catch (ex: Exception) {
            DictationLog.add(service, "ERROR al mostrar estado: ${ex.javaClass.simpleName}: ${ex.message}")
        }
    }

    private fun hideStatus() {
        statusAnimator?.cancel()
        statusAnimator = null
        statusView?.let {
            try {
                if (it.parent != null) windowManager?.removeView(it)
            } catch (_: Exception) { }
        }
        statusView = null
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
        private val NORMAL_COLOR = Color.parseColor("#2E9E5B")
        private val AI_COLOR = Color.parseColor("#9575E8")
        private val ACTIVE_COLOR = Color.parseColor("#E53935")
        private val ERASE_COLOR = Color.parseColor("#546E7A")
        private val PROCESSING_COLOR = Color.parseColor("#F9A825")
        /** Button size relative to the width of the display (66 px on the 260 px cover display). */
        private const val BUTTON_WIDTH_RATIO = 66f / 260f
    }
}
