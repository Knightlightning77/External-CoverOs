package com.eightbit.samsprung.drawer

import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.util.Xml
import androidx.core.content.res.ResourcesCompat
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/**
 * Support for the common Android icon packs (Arcticons, Lawnicons...). A pack declares itself
 * with one of the launcher theme actions and maps app components to its drawables in an
 * "appfilter" file, either an xml resource or an asset.
 */
object IconPacks {

    class Pack(val packageName: String, val label: String)

    private val ACTIONS = arrayOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.anddoes.launcher.THEME",
        "org.adw.launcher.icons.ACTION_PICK_ICON",
        "com.dlto.atom.launcher.THEME",
        "ch.deletescape.lawnchair.ICONPACK"
    )

    /** Icon packs installed on the phone, sorted by name. */
    fun installed(pm: PackageManager): List<Pack> {
        val found = LinkedHashMap<String, Pack>()
        for (action in ACTIONS) {
            val matches = try {
                pm.queryIntentActivities(Intent(action), PackageManager.GET_META_DATA)
            } catch (_: Exception) { emptyList() }
            for (match in matches) {
                val info = match.activityInfo ?: continue
                if (found.containsKey(info.packageName)) continue
                val label = try {
                    info.applicationInfo.loadLabel(pm).toString()
                } catch (_: Exception) { info.packageName }
                found[info.packageName] = Pack(info.packageName, label)
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /** Grey of the circle drawn behind the icons of a pack, close to the one of the home screen. */
    private val CIRCLE_COLOR = Color.parseColor("#293237")
    private const val OVERRIDES = "prefIconOverrides"

    private fun overrideKey(pack: String, info: ResolveInfo): String {
        val activity = info.activityInfo
        return "$pack|${activity.packageName}/${activity.name}"
    }

    private fun overrides(prefs: SharedPreferences): JSONObject = try {
        JSONObject(prefs.getString(OVERRIDES, "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    /** Name of the pack icon the user assigned to [info], or null. */
    fun assignedName(prefs: SharedPreferences, pack: String, info: ResolveInfo): String? {
        return overrides(prefs).optString(overrideKey(pack, info), "").takeIf { it.isNotEmpty() }
    }

    /** Assigns the pack icon [drawableName] to [info]; null removes the assignment. */
    fun assign(prefs: SharedPreferences, pack: String, info: ResolveInfo, drawableName: String?) {
        val all = overrides(prefs)
        if (drawableName == null) all.remove(overrideKey(pack, info))
        else all.put(overrideKey(pack, info), drawableName)
        prefs.edit().putString(OVERRIDES, all.toString()).apply()
    }

    /** Puts the icon over a round grey background, like the icons of the home screen. */
    fun circled(icon: Drawable): Drawable {
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(CIRCLE_COLOR)
        }
        return LayerDrawable(arrayOf(circle, InsetDrawable(icon, 0.18f)))
    }

    /** True when the pack itself (not a user assignment) has an icon for [info]. */
    @Synchronized
    fun hasPackIcon(pm: PackageManager, packPackage: String, info: ResolveInfo): Boolean {
        ensureLoaded(pm, packPackage)
        val activity = info.activityInfo ?: return false
        val name = mapping["${activity.packageName}/${activity.name}"] ?: return false
        return resources?.let { res ->
            res.getIdentifier(name, "drawable", packPackage) != 0 ||
                    res.getIdentifier(name, "mipmap", packPackage) != 0
        } ?: false
    }

    /** Every icon name of the pack, sorted. */
    @Synchronized
    fun drawableNames(pm: PackageManager, packPackage: String): List<String> {
        ensureLoaded(pm, packPackage)
        return mapping.values.toSet().sorted()
    }

    @Synchronized
    fun iconByName(pm: PackageManager, packPackage: String, name: String): Drawable? {
        ensureLoaded(pm, packPackage)
        val res = resources ?: return null
        var id = res.getIdentifier(name, "drawable", packPackage)
        if (id == 0) id = res.getIdentifier(name, "mipmap", packPackage)
        if (id == 0) return null
        return try {
            ResourcesCompat.getDrawable(res, id, null)
        } catch (_: Exception) { null }
    }

    /**
     * The icon to show in the drawer for [info]: the one the user assigned, or the one of the
     * pack, over a grey circle. Null when neither exists (use the normal icon of the app).
     */
    fun themedIcon(
        pm: PackageManager, prefs: SharedPreferences, packPackage: String, info: ResolveInfo
    ): Drawable? {
        val assigned = assignedName(prefs, packPackage, info)
        val icon = (if (assigned != null) iconByName(pm, packPackage, assigned) else null)
            ?: iconFor(pm, packPackage, info)
        return icon?.let { circled(it) }
    }

    private var loadedPackage: String? = null
    private var resources: Resources? = null
    private var mapping: Map<String, String> = emptyMap()

    /**
     * The icon of [info] in the pack [packPackage], or null if the pack has none for it (the
     * caller should then use the normal icon of the app). May take a while the first time.
     */
    @Synchronized
    fun iconFor(pm: PackageManager, packPackage: String, info: ResolveInfo): Drawable? {
        ensureLoaded(pm, packPackage)
        val res = resources ?: return null
        val activity = info.activityInfo ?: return null
        val name = mapping["${activity.packageName}/${activity.name}"] ?: return null
        var id = res.getIdentifier(name, "drawable", packPackage)
        if (id == 0) id = res.getIdentifier(name, "mipmap", packPackage)
        if (id == 0) return null
        return try {
            ResourcesCompat.getDrawable(res, id, null)
        } catch (_: Exception) { null }
    }

    private fun ensureLoaded(pm: PackageManager, packPackage: String) {
        if (loadedPackage == packPackage) return
        loadedPackage = packPackage
        resources = null
        mapping = emptyMap()
        try {
            val res = pm.getResourcesForApplication(packPackage)
            mapping = readAppFilter(res, packPackage)
            resources = res
        } catch (_: Exception) {
            // Pack removed or without a readable appfilter: keep the normal icons
        }
    }

    private fun readAppFilter(res: Resources, packPackage: String): Map<String, String> {
        val parser: XmlPullParser
        val xmlId = res.getIdentifier("appfilter", "xml", packPackage)
        if (xmlId != 0) {
            parser = res.getXml(xmlId)
        } else {
            parser = Xml.newPullParser()
            parser.setInput(res.assets.open("appfilter.xml"), null)
        }
        val map = HashMap<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "item") {
                val component = parser.getAttributeValue(null, "component")
                val drawable = parser.getAttributeValue(null, "drawable")
                if (component != null && drawable != null && component.contains("{")) {
                    val key = component.substringAfter("{").substringBefore("}")
                    val pkg = key.substringBefore("/")
                    var cls = key.substringAfter("/", "")
                    if (cls.startsWith(".")) cls = pkg + cls
                    if (pkg.isNotEmpty() && cls.isNotEmpty()) {
                        map.putIfAbsent("$pkg/$cls", drawable)
                    }
                }
            }
            event = parser.next()
        }
        return map
    }
}
