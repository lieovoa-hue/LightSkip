package com.lightskip.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.lightskip.app.R
import com.lightskip.app.data.AppEntry
import com.lightskip.app.util.appIconLetter

class AppListAdapter(
    private val context: Context,
    private val isEnabled: (String) -> Boolean,
    private val onToggle: (AppEntry, Boolean) -> Unit
) : BaseAdapter() {

    private var items: List<AppEntry> = emptyList()

    fun submit(list: List<AppEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): AppEntry = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_app, parent, false)
        val holder = view.tag as? Holder ?: Holder(view).also { view.tag = it }
        val entry = items[position]
        val enabled = isEnabled(entry.packageName)

        holder.icon.text = appIconLetter(entry.label)
        holder.icon.backgroundTintList = ColorStateList.valueOf(entry.color)
        holder.name.text = entry.label
        holder.sub.setText(if (enabled) R.string.enabled_sub else R.string.excluded_sub)

        holder.toggle.onCheckedChange = null
        holder.toggle.setChecked(enabled, animate = false)
        holder.toggle.contentDescription = entry.label
        holder.toggle.onCheckedChange = { checked ->
            onToggle(entry, checked)
            notifyDataSetChanged()
        }

        view.setOnClickListener {
            onToggle(entry, !isEnabled(entry.packageName))
            notifyDataSetChanged()
        }
        return view
    }

    private class Holder(view: View) {
        val icon: TextView = view.findViewById(R.id.item_icon)
        val name: TextView = view.findViewById(R.id.item_name)
        val sub: TextView = view.findViewById(R.id.item_sub)
        val toggle: ToggleView = view.findViewById(R.id.item_toggle)
    }
}