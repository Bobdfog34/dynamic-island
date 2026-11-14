package com.dynamicisland.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class AppListActivity : AppCompatActivity() {

    private data class AppEntry(
        val pkg: String,
        val label: String,
        val icon: Drawable,
        var protected: Boolean
    )

    private lateinit var prefs: Prefs
    private val entries = ArrayList<AppEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)
        prefs = Prefs(this)
        title = "Приложения"

        val list = findViewById<ListView>(R.id.listApps)
        val progress = findViewById<ProgressBar>(R.id.progress)

        Thread {
            val loaded = loadApps()
            runOnUiThread {
                entries.clear()
                entries.addAll(loaded)
                list.adapter = AppAdapter()
                progress.visibility = View.GONE
            }
        }.start()
    }

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val seen = HashSet<String>()
        val result = ArrayList<AppEntry>()
        for (info in resolved) {
            val pkg = info.activityInfo.packageName
            if (pkg == packageName) continue
            if (!seen.add(pkg)) continue
            val label = info.loadLabel(pm).toString()
            val icon = info.loadIcon(pm)
            result.add(AppEntry(pkg, label, icon, prefs.isProtected(pkg)))
        }
        result.sortBy { it.label.lowercase() }
        return result
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@AppListActivity)
                .inflate(R.layout.item_app, parent, false)
            val entry = entries[position]
            view.findViewById<ImageView>(R.id.ivIcon).setImageDrawable(entry.icon)
            view.findViewById<TextView>(R.id.tvName).text = entry.label
            view.findViewById<TextView>(R.id.tvPkg).text = entry.pkg
            val sw = view.findViewById<SwitchCompat>(R.id.swProtect)
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = entry.protected
            sw.setOnCheckedChangeListener { _, checked ->
                entry.protected = checked
                prefs.setProtected(entry.pkg, checked)
            }
            return view
        }
    }
}
