package com.eightbit.samsprung.drawer

import android.annotation.SuppressLint
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetManager.*
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.children
import androidx.viewpager2.widget.ViewPager2
import com.eightbit.content.ScaledContext
import com.eightbit.samsprung.R
import com.eightbit.samsprung.SamSprungOverlay
import com.eightbit.samsprung.drawer.panels.*
import com.eightbit.samsprung.settings.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

class PanelWidgetManager(
    private var overlay: SamSprungOverlay,
    private var mAppWidgetManager: AppWidgetManager,
    private var appWidgetHost: AppWidgetHost,
    private var pagerAdapter: CoverStateAdapter,
) {
    private var displayMetrics: IntArray = ScaledContext(overlay).getDisplayParams()

    /**
     * Density override chosen by the user in the widget manager screen.
     * [WidgetSizing.DENSITY_AUTO] keeps the original behavior.
     */
    private val widgetDensity: Int
        get() = overlay.getSharedPreferences(Preferences.prefsValue, Context.MODE_PRIVATE)
            .getInt(Preferences.prefZoom, WidgetSizing.DENSITY_AUTO)

    /**
     * Creates the host view for a widget, ready to be shown on the cover display.
     */
    private fun createHostView(
        appWidgetId: Int, appWidgetInfo: AppWidgetProviderInfo, launcherInfo: PanelWidgetInfo
    ): AppWidgetHostView {
        return appWidgetHost.createView(
            WidgetSizing.widgetContext(overlay.applicationContext, widgetDensity, true),
            appWidgetId, appWidgetInfo
        ).apply { tag = launcherInfo }
    }

    /**
     * Puts the host view in a pager page so that it fills the whole cover display and tells
     * the widget how much room it really has.
     */
    private fun attachToPage(fragment: PanelViewFragment, hostView: AppWidgetHostView) {
        fragment.setListener(object: PanelViewFragment.ViewCreatedListener {
            override fun onViewCreated(view: View) {
                val layout = view as LinearLayout
                layout.removeAllViews()
                // The fragment view may have been recreated while the old one still holds us
                (hostView.parent as? ViewGroup)?.removeView(hostView)
                layout.addView(hostView, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
                WidgetSizing.fit(hostView, displayMetrics[0], displayMetrics[1])
            }
        })
    }

    /**
     * Removes every widget page. Used before rebinding the widgets after the list was changed
     * from somewhere else (the widget manager screen of the main app).
     */
    fun resetPages() {
        pagerAdapter.clearFragments()
    }

    @SuppressLint("InflateParams")
    fun completeAddAppWidget(appWidgetId: Int, viewPager: ViewPager2) {
        val appWidgetInfo = mAppWidgetManager.getAppWidgetInfo(appWidgetId)
        if (null == appWidgetInfo) {
            appWidgetHost.deleteAppWidgetId(appWidgetId)
            Toast.makeText(overlay, R.string.widget_error, Toast.LENGTH_LONG).show()
            return
        }

        // Build Launcher-specific widget info and save to database
        val launcherInfo = PanelWidgetInfo(appWidgetId)

        // Every widget occupies the entire cover display
        val spanX = displayMetrics[0]
        val spanY = displayMetrics[1]
        launcherInfo.spanX = spanX
        launcherInfo.spanY = spanY

        CoroutineScope(Dispatchers.IO).launch(Dispatchers.IO) {
            WidgetModel.addItemToDatabase(
                overlay.applicationContext, launcherInfo,
                WidgetSettings.Favorites.CONTAINER_DESKTOP,
                spanX, spanY, false
            )
        }
        overlay.model.addDesktopAppWidget(launcherInfo)

        val hostView = createHostView(appWidgetId, appWidgetInfo, launcherInfo)
        launcherInfo.hostView = hostView

        val id = pagerAdapter.addFragment()
        attachToPage(pagerAdapter.getFragment(id), hostView)
        viewPager.setCurrentItem(id, true)
    }

    private fun addAppWidget(appWidgetId: Int, viewPager: ViewPager2) {
        val appWidget = mAppWidgetManager.getAppWidgetInfo(appWidgetId) ?: return
        if (null != appWidget.configure) {
            overlay.setKeyguardListener(object: SamSprungOverlay.KeyguardListener {
                override fun onKeyguardCheck(unlocked: Boolean) {
                    if (!unlocked) return
                    try {
                        overlay.requestCreateAppWidget.launch(
                            Intent(ACTION_APPWIDGET_CONFIGURE).apply {
                                component = appWidget.configure
                                putExtra(EXTRA_APPWIDGET_ID, appWidgetId)
                            }
                        )
                    } catch (ex: Exception) {
                        Toast.makeText(overlay, R.string.widget_error, Toast.LENGTH_LONG).show()
                        ex.printStackTrace()
                    }
                }
            })
        } else {
            completeAddAppWidget(appWidgetId, viewPager)
        }
    }

    @SuppressLint("InflateParams")
    fun showAddDialog(viewPager: ViewPager2) {
        val appWidgetId = appWidgetHost.allocateAppWidgetId()
        val mWidgetPreviewLoader = WidgetPreviews(overlay)

        val view: View = overlay.layoutInflater.inflate(R.layout.panel_picker_view, null)
        val previews = view.findViewById<LinearLayout>(R.id.previews_layout)
        val widgetDialog = AlertDialog.Builder(
            ContextThemeWrapper(overlay, R.style.Theme_Overlay_NoActionBar)
        ).apply {
            setOnCancelListener {
                previews.children.forEach {
                    val imageView = it as AppCompatImageView
                    mWidgetPreviewLoader.recycleBitmap(it.tag, imageView.drawable.toBitmap())
                }
                previews.removeAllViewsInLayout()
            }
            setOnDismissListener {
                previews.children.forEach {
                    val imageView = it as AppCompatImageView
                    mWidgetPreviewLoader.recycleBitmap(it.tag, imageView.drawable.toBitmap())
                }
                previews.removeAllViewsInLayout()
            }
        }.setView(view).create()
        view.findViewById<LinearLayout>(R.id.widget_cancel).setOnClickListener {
            widgetDialog.dismiss()
        }
        mAppWidgetManager.installedProviders.forEach { info ->
            val previewSizeBeforeScale = IntArray(1)
            val preview = mWidgetPreviewLoader.generateWidgetPreview(
                info, overlay.window.decorView.width, overlay.window.decorView.height,
                null, previewSizeBeforeScale
            )
            val imageView = overlay.layoutInflater.inflate(R.layout.widget_preview, null) as AppCompatImageView
            previews.addView(imageView.apply {
                adjustViewBounds = true
                setImageBitmap(preview)
                setOnClickListener {
                    if (mAppWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, info.provider)) {
                        addAppWidget(appWidgetId, viewPager)
                    } else {
                        this@PanelWidgetManager.overlay.requestCreateAppWidget.launch(
                            Intent(ACTION_APPWIDGET_BIND).apply {
                                putExtra(EXTRA_APPWIDGET_ID, appWidgetId)
                                putExtra(EXTRA_APPWIDGET_PROVIDER, info.provider)
                                putExtra(EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
                            }
                        )
                    }
                    widgetDialog.dismiss()
                }
                tag = info
            })
        }
        widgetDialog.show()
        widgetDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    @SuppressLint("InflateParams")
    fun bindAppWidgets(
        binder: DesktopBinder,
        appWidgets: LinkedList<PanelWidgetInfo>
    ) {
        if (appWidgets.isNotEmpty()) {
            val item = appWidgets.removeFirst()
            val appWidgetId = item.appWidgetId
            val appWidgetInfo = mAppWidgetManager.getAppWidgetInfo(appWidgetId)
            // A widget whose provider is not installed anymore has nothing to show. It is kept in
            // the database so it comes back if the app is reinstalled, and can be removed from
            // the widget manager screen of the main app.
            if (null != appWidgetInfo) {
                val hostView = createHostView(appWidgetId, appWidgetInfo, item)
                item.hostView = hostView

                val id = pagerAdapter.addFragment()
                attachToPage(pagerAdapter.getFragment(id), hostView)
            }
        }
        if (appWidgets.isNotEmpty())
            binder.obtainMessage(DesktopBinder.MESSAGE_BIND_APPWIDGETS).sendToTarget()
    }
}
