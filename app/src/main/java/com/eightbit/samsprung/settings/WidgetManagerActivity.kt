package com.eightbit.samsprung.settings

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import com.eightbit.content.ScaledContext
import com.eightbit.samsprung.R
import com.eightbit.samsprung.SamSprungOverlay
import com.eightbit.samsprung.drawer.panels.PanelWidgetInfo
import com.eightbit.samsprung.drawer.panels.WidgetModel
import com.eightbit.samsprung.drawer.panels.WidgetSettings
import com.eightbit.samsprung.drawer.panels.WidgetSizing
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lets the user choose, order, configure and remove the widgets that are shown on the cover
 * display, from the main display of the unfolded phone.
 *
 * The widget list lives in the same database the cover overlay uses, so changes made here show up
 * the next time the cover screen is opened (or immediately when it is already running).
 */
class WidgetManagerActivity : AppCompatActivity() {

    private val CharSequence.toPref get() = this.toString().lowercase().replace(" ", "_")

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(Preferences.prefsValue, MODE_PRIVATE)
    }

    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost
    private lateinit var widgetList: LinearLayout
    private lateinit var emptyView: View
    private lateinit var zoomValue: TextView

    private val widgets = ArrayList<PanelWidgetInfo>()
    private var pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var busy = false

    private val bindLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val appWidgetId = pendingWidgetId
        if (result.resultCode == RESULT_OK && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            onWidgetBound(appWidgetId)
        } else {
            cancelPendingWidget()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_manager)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        pendingWidgetId = savedInstanceState?.getInt(
            STATE_PENDING_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        appWidgetManager = AppWidgetManager.getInstance(applicationContext)
        // Same host id as the cover overlay, so both see the same set of widgets
        appWidgetHost = AppWidgetHost(applicationContext, SamSprungOverlay.APPWIDGET_HOST_ID)

        widgetList = findViewById(R.id.widget_list)
        emptyView = findViewById(R.id.widgets_empty)
        zoomValue = findViewById(R.id.zoom_value)

        val enabledKey = getString(R.string.toggle_widgets).toPref
        findViewById<SwitchCompat>(R.id.widgets_enabled).apply {
            isChecked = prefs.getBoolean(enabledKey, true)
            setOnCheckedChangeListener { _, isChecked ->
                with(prefs.edit()) {
                    putBoolean(enabledKey, isChecked)
                    apply()
                }
            }
        }

        findViewById<ExtendedFloatingActionButton>(R.id.add_widget).setOnClickListener {
            showWidgetPicker()
        }

        val steps = WidgetSizing.DENSITY_STEPS
        val zoomSeek = findViewById<SeekBar>(R.id.zoom_seek)
        zoomSeek.max = steps.size - 1
        zoomSeek.progress = steps.indexOf(
            prefs.getInt(Preferences.prefZoom, WidgetSizing.DENSITY_AUTO)
        ).coerceAtLeast(0)
        updateZoomLabel(zoomSeek.progress)
        zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                updateZoomLabel(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) { }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                with(prefs.edit()) {
                    putInt(Preferences.prefZoom, steps[seekBar.progress])
                    apply()
                }
                renderWidgets()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        loadWidgets()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PENDING_ID, pendingWidgetId)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun updateZoomLabel(index: Int) {
        val dpi = WidgetSizing.DENSITY_STEPS[index]
        zoomValue.text = if (dpi <= WidgetSizing.DENSITY_AUTO) {
            getString(R.string.widget_zoom_auto)
        } else {
            val coverWidth = ScaledContext(this).getCoverParams()[0]
            getString(R.string.widget_zoom_value, dpi, coverWidth * 160 / dpi)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Saved widgets
    // ---------------------------------------------------------------------------------------

    private fun loadWidgets() {
        CoroutineScope(Dispatchers.IO).launch {
            val loaded = try {
                WidgetModel.queryDesktopWidgets(applicationContext)
            } catch (_: Exception) {
                arrayListOf<PanelWidgetInfo>()
            }
            withContext(Dispatchers.Main) {
                busy = false
                if (isFinishing || isDestroyed) return@withContext
                widgets.clear()
                widgets.addAll(loaded)
                renderWidgets()
            }
        }
    }

    private fun renderWidgets() {
        widgetList.removeAllViews()
        emptyView.isVisible = widgets.isEmpty()
        widgets.forEachIndexed { index, item ->
            widgetList.addView(createItemView(index, item))
        }
    }

    private fun createItemView(index: Int, item: PanelWidgetInfo): View {
        val view = layoutInflater.inflate(R.layout.widget_manager_item, widgetList, false)
        val info = appWidgetManager.getAppWidgetInfo(item.appWidgetId)
        val title = view.findViewById<TextView>(R.id.widget_title)
        val subtitle = view.findViewById<TextView>(R.id.widget_subtitle)
        val configure = view.findViewById<AppCompatImageButton>(R.id.btn_configure)

        if (null != info) {
            title.text = info.loadLabel(packageManager)
            subtitle.text = getString(R.string.widget_position, index + 1, widgets.size)
            showPreview(view.findViewById(R.id.widget_preview_frame), item, info)
            configure.isVisible = null != info.configure &&
                    (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE) != 0
        } else {
            title.text = getString(R.string.gadget_error_text)
            subtitle.text = getString(R.string.widget_unavailable)
            configure.isVisible = false
        }

        view.findViewById<AppCompatImageButton>(R.id.btn_up).apply {
            isEnabled = index > 0
            alpha = if (isEnabled) 1f else 0.3f
            setOnClickListener { moveWidget(index, -1) }
        }
        view.findViewById<AppCompatImageButton>(R.id.btn_down).apply {
            isEnabled = index < widgets.size - 1
            alpha = if (isEnabled) 1f else 0.3f
            setOnClickListener { moveWidget(index, 1) }
        }
        configure.setOnClickListener {
            try {
                appWidgetHost.startAppWidgetConfigureActivityForResult(
                    this, item.appWidgetId, 0, REQUEST_RECONFIGURE, null
                )
            } catch (_: Exception) {
                Toast.makeText(this, R.string.widget_error, Toast.LENGTH_LONG).show()
            }
        }
        view.findViewById<AppCompatImageButton>(R.id.btn_remove).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.widget_remove)
                .setMessage(title.text)
                .setPositiveButton(android.R.string.ok) { _, _ -> removeWidget(item) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        return view
    }

    /**
     * Shows the widget the way it is laid out on the cover display, scaled down to fit the card.
     * The widget is created at the real size of the cover screen so it is the same one the cover
     * shows, with the same size reported to the provider.
     */
    private fun showPreview(frame: FrameLayout, item: PanelWidgetInfo, info: AppWidgetProviderInfo) {
        val cover = ScaledContext(this).getCoverParams()
        val frameWidth = (PREVIEW_WIDTH_DP * resources.displayMetrics.density).toInt()
        val scale = frameWidth.toFloat() / cover[0]
        frame.layoutParams = frame.layoutParams.also {
            it.width = frameWidth
            it.height = (cover[1] * scale).toInt()
        }
        try {
            val hostView = appWidgetHost.createView(
                WidgetSizing.widgetContext(
                    applicationContext,
                    prefs.getInt(Preferences.prefZoom, WidgetSizing.DENSITY_AUTO)
                ), item.appWidgetId, info
            )
            hostView.layoutParams = FrameLayout.LayoutParams(cover[0], cover[1])
            hostView.pivotX = 0f
            hostView.pivotY = 0f
            hostView.scaleX = scale
            hostView.scaleY = scale
            frame.addView(hostView)
            WidgetSizing.fit(hostView, cover[0], cover[1])
            // The preview is only a preview: do not let touches reach the widget
            frame.addView(View(this).apply { isClickable = true })
        } catch (_: Exception) { }
    }

    private fun moveWidget(index: Int, offset: Int) {
        val target = index + offset
        if (busy || index !in widgets.indices || target !in widgets.indices) return
        busy = true
        val first = widgets[index]
        val second = widgets[target]
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WidgetModel.swapItems(applicationContext, first, second)
            } catch (_: Exception) { }
            withContext(Dispatchers.Main) { loadWidgets() }
        }
    }

    private fun removeWidget(item: PanelWidgetInfo) {
        if (busy) return
        busy = true
        try {
            appWidgetHost.deleteAppWidgetId(item.appWidgetId)
        } catch (_: Exception) { }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WidgetModel.deleteItemFromDatabase(applicationContext, item, true)
            } catch (_: Exception) { }
            withContext(Dispatchers.Main) { loadWidgets() }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Adding a widget
    // ---------------------------------------------------------------------------------------

    private class ProviderEntry(
        val info: AppWidgetProviderInfo, val label: String, val app: String
    )

    private inner class ProviderAdapter(
        private val entries: List<ProviderEntry>
    ) : BaseAdapter() {
        override fun getCount(): Int = entries.size

        override fun getItem(position: Int): Any = entries[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView
                ?: layoutInflater.inflate(R.layout.widget_picker_item, parent, false)
            val entry = entries[position]
            row.findViewById<TextView>(R.id.picker_label).text = entry.label
            row.findViewById<TextView>(R.id.picker_app).text = entry.app
            row.findViewById<ImageView>(R.id.picker_icon).setImageDrawable(
                try {
                    entry.info.loadIcon(
                        this@WidgetManagerActivity, resources.displayMetrics.densityDpi
                    )
                } catch (_: Exception) {
                    null
                }
            )
            row.findViewById<ImageView>(R.id.picker_preview).setImageDrawable(
                try {
                    entry.info.loadPreviewImage(this@WidgetManagerActivity, 0)
                } catch (_: Exception) {
                    null
                }
            )
            return row
        }
    }

    private fun showWidgetPicker() {
        val entries = appWidgetManager.installedProviders.map { info ->
            val app = try {
                packageManager.getApplicationLabel(info.providerInfo.applicationInfo).toString()
            } catch (_: Exception) {
                info.provider.packageName
            }
            ProviderEntry(info, info.loadLabel(packageManager), app)
        }.sortedWith(compareBy<ProviderEntry>({ it.app.lowercase() }, { it.label.lowercase() }))

        AlertDialog.Builder(this)
            .setTitle(R.string.widget_add)
            .setAdapter(ProviderAdapter(entries)) { dialog, which ->
                dialog.dismiss()
                startAddingWidget(entries[which].info)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startAddingWidget(info: AppWidgetProviderInfo) {
        val appWidgetId = appWidgetHost.allocateAppWidgetId()
        pendingWidgetId = appWidgetId
        if (appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, info.provider)) {
            onWidgetBound(appWidgetId)
        } else {
            try {
                // Android asks the user to allow this app to bind the widget
                bindLauncher.launch(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
                    }
                )
            } catch (_: Exception) {
                cancelPendingWidget()
                Toast.makeText(this, R.string.widget_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onWidgetBound(appWidgetId: Int) {
        val info = appWidgetManager.getAppWidgetInfo(appWidgetId)
        if (null == info) {
            cancelPendingWidget()
            Toast.makeText(this, R.string.widget_error, Toast.LENGTH_LONG).show()
            return
        }
        if (null != info.configure) {
            try {
                appWidgetHost.startAppWidgetConfigureActivityForResult(
                    this, appWidgetId, 0, REQUEST_CONFIGURE, null
                )
            } catch (_: Exception) {
                cancelPendingWidget()
                Toast.makeText(this, R.string.widget_error, Toast.LENGTH_LONG).show()
            }
        } else {
            commitWidget(appWidgetId)
        }
    }

    private fun cancelPendingWidget() {
        if (pendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            try {
                appWidgetHost.deleteAppWidgetId(pendingWidgetId)
            } catch (_: Exception) { }
            pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        }
    }

    /**
     * Saves the new widget, which then becomes the last page of the cover display.
     */
    private fun commitWidget(appWidgetId: Int) {
        pendingWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
        val cover = ScaledContext(this).getCoverParams()
        val item = PanelWidgetInfo(appWidgetId)
        busy = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WidgetModel.addItemToDatabase(
                    applicationContext, item, WidgetSettings.Favorites.CONTAINER_DESKTOP,
                    cover[0], cover[1], true
                )
            } catch (_: Exception) { }
            withContext(Dispatchers.Main) { loadWidgets() }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CONFIGURE -> {
                val appWidgetId = data?.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId
                ) ?: pendingWidgetId
                if (resultCode == RESULT_OK && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    commitWidget(appWidgetId)
                } else {
                    cancelPendingWidget()
                    Toast.makeText(this, R.string.out_of_space, Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_RECONFIGURE -> loadWidgets()
        }
    }

    companion object {
        private const val STATE_PENDING_ID = "pendingWidgetId"
        private const val REQUEST_CONFIGURE = 0x5701
        private const val REQUEST_RECONFIGURE = 0x5702
        private const val PREVIEW_WIDTH_DP = 120
    }
}
