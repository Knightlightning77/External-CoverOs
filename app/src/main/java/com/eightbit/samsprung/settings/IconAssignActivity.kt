package com.eightbit.samsprung.settings

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.eightbit.samsprung.R
import com.eightbit.samsprung.drawer.IconPacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lets the user choose, for the apps the selected icon pack has no icon for, one of the icons of
 * the pack. The choice is used by the app drawer of the cover display.
 */
class IconAssignActivity : AppCompatActivity() {

    private class Entry(val info: ResolveInfo, val label: String, val hasPackIcon: Boolean)

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(Preferences.prefsValue, MODE_PRIVATE)
    }

    private var pack = ""
    private val all = ArrayList<Entry>()
    private val shown = ArrayList<Entry>()
    private var onlyMissing = true
    private lateinit var adapter: EntryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pack = prefs.getString(Preferences.prefIconPack, "") ?: ""
        if (pack.isEmpty()) {
            Toast.makeText(this, R.string.icon_pack_select_first, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(R.layout.activity_icon_assign)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = EntryAdapter()
        findViewById<RecyclerView>(R.id.icon_assign_list).apply {
            layoutManager = LinearLayoutManager(this@IconAssignActivity)
            adapter = this@IconAssignActivity.adapter
        }
        findViewById<CheckBox>(R.id.icon_assign_only_missing).apply {
            isChecked = onlyMissing
            setOnCheckedChangeListener { _, checked ->
                onlyMissing = checked
                applyFilter()
            }
        }
        loadApps()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadApps() {
        CoroutineScope(Dispatchers.IO).launch {
            val pm = packageManager
            val launchables = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            )
            val entries = launchables.map {
                Entry(it, it.loadLabel(pm).toString(), IconPacks.hasPackIcon(pm, pack, it))
            }.sortedBy { it.label.lowercase() }
            withContext(Dispatchers.Main) {
                all.clear()
                all.addAll(entries)
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        shown.clear()
        shown.addAll(if (onlyMissing) all.filter { !it.hasPackIcon } else all)
        adapter.notifyDataSetChanged()
    }

    private fun showPicker(entry: Entry) {
        val view = LayoutInflater.from(this).inflate(R.layout.icon_picker_dialog, null)
        val grid = view.findViewById<RecyclerView>(R.id.icon_grid)
        val search = view.findViewById<EditText>(R.id.icon_search)
        val picker = PickerAdapter()
        grid.layoutManager = GridLayoutManager(this, 5)
        grid.adapter = picker

        val dialog = AlertDialog.Builder(this)
            .setTitle(entry.label)
            .setView(view)
            .setNegativeButton(R.string.button_cancel) { d, _ -> d.dismiss() }
            .setNeutralButton(R.string.icon_assign_clear) { d, _ ->
                IconPacks.assign(prefs, pack, entry.info, null)
                adapter.notifyDataSetChanged()
                d.dismiss()
            }.create()
        picker.onPick = { name ->
            IconPacks.assign(prefs, pack, entry.info, name)
            adapter.notifyDataSetChanged()
            dialog.dismiss()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) { }
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                picker.filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) { }
        })
        dialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            val names = IconPacks.drawableNames(packageManager, pack)
            withContext(Dispatchers.Main) { picker.setNames(names) }
        }
    }

    private inner class EntryAdapter : RecyclerView.Adapter<EntryAdapter.Holder>() {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.assign_icon)
            val label: TextView = view.findViewById(R.id.assign_label)
            val state: TextView = view.findViewById(R.id.assign_state)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.icon_assign_item, parent, false)
        )

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = shown[position]
            val pm = packageManager
            holder.label.text = entry.label
            holder.icon.setImageDrawable(
                IconPacks.themedIcon(pm, prefs, pack, entry.info) ?: entry.info.loadIcon(pm)
            )
            val assigned = IconPacks.assignedName(prefs, pack, entry.info)
            holder.state.text = when {
                assigned != null -> getString(R.string.icon_assign_assigned, assigned)
                !entry.hasPackIcon -> getString(R.string.icon_assign_missing)
                else -> ""
            }
            holder.itemView.setOnClickListener { showPicker(entry) }
            holder.itemView.setOnLongClickListener {
                IconPacks.assign(prefs, pack, entry.info, null)
                notifyDataSetChanged()
                true
            }
        }
    }

    private inner class PickerAdapter : RecyclerView.Adapter<PickerAdapter.Holder>() {
        var onPick: (String) -> Unit = { }
        private var names: List<String> = emptyList()
        private var visible: List<String> = emptyList()
        private var query = ""

        inner class Holder(val image: ImageView) : RecyclerView.ViewHolder(image)

        fun setNames(list: List<String>) {
            names = list
            filter(query)
        }

        fun filter(text: String) {
            query = text.trim()
            val wanted = query.lowercase().replace(' ', '_')
            visible = if (wanted.isEmpty()) names else names.filter { it.lowercase().contains(wanted) }
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val size = (56 * parent.resources.displayMetrics.density).toInt()
            return Holder(ImageView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(size, size)
                val pad = (4 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            })
        }

        override fun getItemCount() = visible.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val name = visible[position]
            val icon = IconPacks.iconByName(packageManager, pack, name)
            holder.image.contentDescription = name
            holder.image.setImageDrawable(icon?.let { IconPacks.circled(it) })
            holder.image.setOnClickListener { onPick(name) }
        }
    }
}
