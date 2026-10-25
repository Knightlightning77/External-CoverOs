package com.eightbit.samsprung.drawer.panels

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.SizeF
import android.view.ContextThemeWrapper
import android.view.View
import com.eightbit.content.ScaledContext
import com.eightbit.os.Version
import com.eightbit.samsprung.R

/**
 * Helpers that make an [AppWidgetHostView] use the whole cover display.
 *
 * The widget provider only knows how big its host is if the host tells it. Before this helper
 * nothing reported the real size of the cover screen, so providers fell back to their default
 * size and responsive / resizable widgets were laid out for a much larger (or smaller) area
 * than the one they were really drawn in.
 */
object WidgetSizing {

    /** Density override value meaning "keep the density the cover display already has". */
    const val DENSITY_AUTO = 0

    /**
     * Selectable density overrides (dpi). The lower the value, the more dp fit on the cover
     * screen, which helps with widgets that insist on a large minimum size.
     */
    @JvmField
    val DENSITY_STEPS = intArrayOf(DENSITY_AUTO, 200, 220, 240, 260, 280, 300, 320, 360, 400, 440)

    /**
     * Context used to inflate widgets that will be shown on the cover display.
     *
     * @param densityDpi [DENSITY_AUTO] for the original behavior or a dpi value to force.
     * @param legacyScaling keep the original (global) 1.5 scaling of the cover overlay. Only the
     * overlay needs it; other screens must not alter the shared application resources.
     */
    fun widgetContext(base: Context, densityDpi: Int, legacyScaling: Boolean = false): Context {
        val cover = if (legacyScaling && densityDpi <= DENSITY_AUTO)
            ScaledContext(ScaledContext(base).internal(1.5f)).cover()
        else
            ScaledContext(base).cover()
        if (densityDpi <= DENSITY_AUTO) return cover
        val config = Configuration(cover.resources.configuration)
        config.densityDpi = densityDpi
        return ContextThemeWrapper(
            cover.createConfigurationContext(config), R.style.Theme_SecondScreen
        )
    }

    /**
     * Make [hostView] report the given size (in pixels) to its provider and keep reporting it
     * whenever the view is laid out with a different size (rotation, window changes...).
     */
    fun fit(hostView: AppWidgetHostView, widthPx: Int, heightPx: Int) {
        hostView.setPadding(0, 0, 0, 0)
        (hostView.getTag(R.id.widget_size_reporter) as? SizeReporter)?.let {
            hostView.removeOnLayoutChangeListener(it)
        }
        val reporter = SizeReporter(hostView)
        hostView.setTag(R.id.widget_size_reporter, reporter)
        hostView.addOnLayoutChangeListener(reporter)
        reporter.report(widthPx, heightPx)
    }

    private class SizeReporter(private val hostView: AppWidgetHostView) :
        View.OnLayoutChangeListener {

        private var lastWidth = -1
        private var lastHeight = -1

        fun report(widthPx: Int, heightPx: Int) {
            if (widthPx <= 0 || heightPx <= 0) return
            if (widthPx == lastWidth && heightPx == lastHeight) return
            lastWidth = widthPx
            lastHeight = heightPx

            val density = hostView.resources.displayMetrics.density
            if (density <= 0f) return
            val width = (widthPx / density).toInt()
            val height = (heightPx / density).toInt()

            val options = Bundle().apply {
                // The widget fills the screen, so minimum and maximum are the same.
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
                putInt(
                    AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                    AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
                )
                if (Version.isSnowCone) {
                    // Responsive (Android 12+) widgets choose their layout from this list.
                    putParcelableArrayList(
                        AppWidgetManager.OPTION_APPWIDGET_SIZES,
                        arrayListOf(SizeF(width.toFloat(), height.toFloat()))
                    )
                }
            }
            try {
                hostView.updateAppWidgetOptions(options)
            } catch (_: Exception) {
                // The provider may be gone or the service temporarily unavailable.
            }
        }

        override fun onLayoutChange(
            v: View?, left: Int, top: Int, right: Int, bottom: Int,
            oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
        ) {
            report(right - left, bottom - top)
        }
    }
}
