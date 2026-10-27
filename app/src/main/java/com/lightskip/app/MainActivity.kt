package com.lightskip.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.lightskip.app.data.AppEntry
import com.lightskip.app.data.AppRepository
import com.lightskip.app.data.LocalStats
import com.lightskip.app.data.Prefs
import com.lightskip.app.ui.AppListAdapter
import com.lightskip.app.ui.ToggleView
import com.lightskip.app.util.AccessibilityUtils
import com.lightskip.app.util.SystemBarUtils
import com.lightskip.app.util.appIconLetter
import com.lightskip.app.util.formatCount
import com.lightskip.app.util.formatSeconds
import com.lightskip.app.util.relativeTime
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private enum class Tab { HOME, APPS, SETTINGS }
    private enum class Filter { ALL, ENABLED, EXCLUDED }

    private lateinit var prefs: Prefs
    private lateinit var stats: LocalStats
    private lateinit var repo: AppRepository
    private var allApps: List<AppEntry> = emptyList()
    private var currentTab = Tab.HOME
    private var appsFilter = Filter.ALL
    private var searchQuery = ""
    private lateinit var appListAdapter: AppListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        stats = LocalStats(this, prefs)
        stats.prune()
        repo = AppRepository(this)

        if (!prefs.onboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        SystemBarUtils.apply(this)

        bindNavigation()
        bindHome()
        bindApps()
        bindSettings()
        refreshApps()
        selectTab(Tab.HOME)
    }

    override fun onResume() {
        super.onResume()
        refreshHome()
        refreshSettings()
        if (currentTab == Tab.APPS) refreshApps()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentTab != Tab.HOME) selectTab(Tab.HOME) else super.onBackPressed()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !isFinishing) {
            finishAndRemoveTask()
        }
    }

    private fun bindNavigation() {
        findViewById<View>(R.id.nav_home).setOnClickListener { selectTab(Tab.HOME) }
        findViewById<View>(R.id.nav_apps).setOnClickListener { selectTab(Tab.APPS) }
        findViewById<View>(R.id.nav_settings).setOnClickListener { selectTab(Tab.SETTINGS) }
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab
        findViewById<View>(R.id.home_view).visibility = if (tab == Tab.HOME) View.VISIBLE else View.GONE
        findViewById<View>(R.id.apps_view).visibility = if (tab == Tab.APPS) View.VISIBLE else View.GONE
        findViewById<View>(R.id.settings_view).visibility = if (tab == Tab.SETTINGS) View.VISIBLE else View.GONE
        updateNavigation()

        when (tab) {
            Tab.HOME -> refreshHome()
            Tab.APPS -> refreshApps()
            Tab.SETTINGS -> refreshSettings()
        }
    }

    private fun updateNavigation() {
        styleNav(R.id.nav_home_icon, R.id.nav_home_label, currentTab == Tab.HOME)
        styleNav(R.id.nav_apps_icon, R.id.nav_apps_label, currentTab == Tab.APPS)
        styleNav(R.id.nav_settings_icon, R.id.nav_settings_label, currentTab == Tab.SETTINGS)
    }

    private fun styleNav(iconId: Int, labelId: Int, active: Boolean) {
        val color = getColor(if (active) R.color.forest_text else R.color.nav_inactive)
        findViewById<ImageView>(iconId).setColorFilter(color)
        findViewById<TextView>(labelId).apply {
            setTextColor(color)
            typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun bindHome() {
        val master = findViewById<ToggleView>(R.id.master_toggle)
        master.onCheckedChange = { handleMasterToggle(it) }

        findViewById<View>(R.id.guard_card).setOnClickListener {
            if (!AccessibilityUtils.isServiceEnabled(this)) {
                AccessibilityUtils.openAccessibilitySettings(this)
            }
        }

        findViewById<View>(R.id.quick_scope_row).setOnClickListener { selectTab(Tab.APPS) }
        findViewById<View>(R.id.quick_window_row).setOnClickListener { showWindowDialog() }

        findViewById<ToggleView>(R.id.quick_vibrate).onCheckedChange = {
            prefs.vibrateEnabled = it
            refreshSettings()
        }
    }

    private fun handleMasterToggle(checked: Boolean) {
        if (checked && !AccessibilityUtils.isServiceEnabled(this)) {
            Toast.makeText(this, R.string.toast_service_needed, Toast.LENGTH_SHORT).show()
            findViewById<ToggleView>(R.id.master_toggle).setChecked(false, animate = true)
            findViewById<ToggleView>(R.id.settings_toggle).setChecked(false, animate = true)
            AccessibilityUtils.openAccessibilitySettings(this)
            return
        }
        prefs.masterEnabled = checked
        refreshHome()
        refreshSettings()
    }

    private fun refreshHome() {
        val serviceEnabled = AccessibilityUtils.isServiceEnabled(this)
        val masterEnabled = prefs.masterEnabled
        val active = serviceEnabled && masterEnabled

        findViewById<TextView>(R.id.guard_state).setText(
            when {
                !serviceEnabled -> R.string.needs_permission
                !masterEnabled -> R.string.paused
                else -> R.string.guarding
            }
        )
        findViewById<ToggleView>(R.id.master_toggle).setChecked(active, animate = false)

        val skips = stats.todaySkips()
        findViewById<TextView>(R.id.home_count).text = formatCount(skips)
        findViewById<TextView>(R.id.home_saved).text = getString(
            R.string.saved_format,
            (skips * 1.5f).roundToInt()
        )
        findViewById<TextView>(R.id.home_app_count).text = getString(
            R.string.apps_count_format,
            prefs.targetPackages.size
        )

        findViewById<TextView>(R.id.stat_response).text = formatSeconds(stats.averageResponseMs())
        findViewById<TextView>(R.id.stat_sessions).text = getString(R.string.number_format, stats.todaySessions())

        val recent = stats.lastSkip()
        val recentIcon = findViewById<TextView>(R.id.recent_icon)
        val recentName = findViewById<TextView>(R.id.recent_name)
        val recentTime = findViewById<TextView>(R.id.recent_time)
        val recentStatus = findViewById<TextView>(R.id.recent_status)

        if (recent == null) {
            recentIcon.text = "?"
            recentIcon.backgroundTintList = ColorStateList.valueOf(getColor(R.color.nav_inactive))
            recentName.setText(R.string.recent_empty_name)
            recentTime.setText(R.string.recent_empty_sub)
            recentStatus.visibility = View.GONE
        } else {
            val label = repo.labelFor(recent.packageName)
            recentIcon.text = appIconLetter(label)
            recentIcon.backgroundTintList = ColorStateList.valueOf(repo.colorFor(recent.packageName))
            recentName.text = label
            recentTime.text = getString(R.string.recent_time_format, relativeTime(recent.at))
            recentStatus.visibility = View.VISIBLE
        }

        findViewById<TextView>(R.id.quick_scope_sub).text = getString(
            R.string.quick_scope_sub,
            prefs.targetPackages.size
        )
        findViewById<TextView>(R.id.quick_window_sub).text = getString(
            R.string.quick_window_sub_format,
            (prefs.detectionWindowMs / 1000L).toInt(),
            prefs.maxAttempts
        )
        findViewById<ToggleView>(R.id.quick_vibrate).setChecked(prefs.vibrateEnabled, animate = false)
    }

    private fun bindApps() {
        appListAdapter = AppListAdapter(
            context = this,
            isEnabled = { prefs.isTarget(it) },
            onToggle = { entry, enabled ->
                prefs.setTarget(entry.packageName, enabled)
                updateSegments()
                updateAppsSummary()
                refreshHome()
            }
        )
        findViewById<ListView>(R.id.app_list).adapter = appListAdapter
        findViewById<View>(R.id.apps_select_all).setOnClickListener { toggleAllApps() }
        findViewById<View>(R.id.apps_search_button).setOnClickListener {
            val input = findViewById<EditText>(R.id.search_input)
            input.requestFocus()
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }

        findViewById<EditText>(R.id.search_input).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim().orEmpty()
                refreshAppList()
            }
        })

        findViewById<View>(R.id.seg_all).setOnClickListener { setFilter(Filter.ALL) }
        findViewById<View>(R.id.seg_enabled).setOnClickListener { setFilter(Filter.ENABLED) }
        findViewById<View>(R.id.seg_excluded).setOnClickListener { setFilter(Filter.EXCLUDED) }
    }

    private fun setFilter(filter: Filter) {
        appsFilter = filter
        updateSegments()
        refreshAppList()
    }

    private fun toggleAllApps() {
        val allEnabled = allApps.isNotEmpty() && allApps.all { prefs.isTarget(it.packageName) }
        val targets = prefs.targetPackages.toMutableSet()
        if (allEnabled) targets.clear() else targets.addAll(allApps.map { it.packageName })
        prefs.targetPackages = targets
        updateSegments()
        updateAppsSummary()
        refreshAppList()
        refreshHome()
    }

    private fun refreshApps() {
        allApps = repo.load()
        updateSegments()
        updateAppsSummary()
        refreshAppList()
    }

    private fun updateSegments() {
        val enabled = allApps.count { prefs.isTarget(it.packageName) }
        val excluded = allApps.size - enabled

        styleSegment(R.id.seg_all, appsFilter == Filter.ALL)
        styleSegment(R.id.seg_enabled, appsFilter == Filter.ENABLED)
        styleSegment(R.id.seg_excluded, appsFilter == Filter.EXCLUDED)

        findViewById<TextView>(R.id.seg_all).text =
            getString(R.string.filter_count_format, getString(R.string.filter_all), allApps.size)
        findViewById<TextView>(R.id.seg_enabled).text =
            getString(R.string.filter_count_format, getString(R.string.filter_enabled), enabled)
        findViewById<TextView>(R.id.seg_excluded).text =
            getString(R.string.filter_count_format, getString(R.string.filter_excluded), excluded)
        updateSelectAllLabel()
    }

    private fun updateSelectAllLabel() {
        val allEnabled = allApps.isNotEmpty() && allApps.all { prefs.isTarget(it.packageName) }
        findViewById<TextView>(R.id.apps_select_all).setText(
            if (allEnabled) R.string.clear_all else R.string.select_all
        )
    }

    private fun styleSegment(id: Int, active: Boolean) {
        findViewById<TextView>(id).apply {
            if (active) setBackgroundResource(R.drawable.bg_segment_active) else setBackgroundColor(Color.TRANSPARENT)
            setTextColor(getColor(if (active) R.color.forest_text else R.color.muted))
            typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun refreshAppList() {
        val query = searchQuery.lowercase()
        val filtered = allApps.filter { entry ->
            val matchesFilter = when (appsFilter) {
                Filter.ALL -> true
                Filter.ENABLED -> prefs.isTarget(entry.packageName)
                Filter.EXCLUDED -> !prefs.isTarget(entry.packageName)
            }
            val matchesQuery = query.isEmpty() ||
                entry.label.lowercase().contains(query) ||
                entry.packageName.lowercase().contains(query)
            matchesFilter && matchesQuery
        }

        val list = findViewById<ListView>(R.id.app_list)
        val empty = findViewById<TextView>(R.id.app_list_empty)
        if (filtered.isEmpty()) {
            list.visibility = View.GONE
            empty.visibility = View.VISIBLE
            appListAdapter.submit(emptyList())
        } else {
            list.visibility = View.VISIBLE
            empty.visibility = View.GONE
            appListAdapter.submit(filtered)
        }
    }

    private fun updateAppsSummary() {
        val enabled = allApps.count { prefs.isTarget(it.packageName) }
        val excluded = (allApps.size - enabled).coerceAtLeast(0)
        findViewById<TextView>(R.id.apps_summary).text =
            getString(R.string.apps_summary_format, enabled, excluded)
    }

    private fun bindSettings() {
        findViewById<View>(R.id.settings_status_card).setOnClickListener {
            AccessibilityUtils.openAccessibilitySettings(this)
        }

        findViewById<ToggleView>(R.id.settings_toggle).onCheckedChange = { handleMasterToggle(it) }
        findViewById<View>(R.id.row_window).setOnClickListener { showWindowDialog() }
        findViewById<View>(R.id.row_attempts).setOnClickListener { showAttemptsDialog() }

        findViewById<ToggleView>(R.id.toggle_close_ad).onCheckedChange = {
            prefs.closeAdEnabled = it
        }
        findViewById<ToggleView>(R.id.toggle_vibrate).onCheckedChange = {
            prefs.vibrateEnabled = it
            refreshHome()
        }
        findViewById<ToggleView>(R.id.toggle_stats).onCheckedChange = {
            prefs.statsEnabled = it
            refreshHome()
        }

        findViewById<View>(R.id.row_network).setOnClickListener {
            showInfoDialog(R.string.network_title, R.string.network_body)
        }
        findViewById<View>(R.id.row_background).setOnClickListener {
            showBackgroundDialog()
        }
        findViewById<View>(R.id.row_clear).setOnClickListener { confirmClearStats() }
        findViewById<View>(R.id.row_privacy).setOnClickListener {
            showInfoDialog(R.string.privacy_title, R.string.privacy_body)
        }
        findViewById<View>(R.id.row_demo).apply {
            visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
            setOnClickListener { openDemoSplash() }
        }
    }

    private fun refreshSettings() {
        val serviceEnabled = AccessibilityUtils.isServiceEnabled(this)
        val active = serviceEnabled && prefs.masterEnabled

        val title = findViewById<TextView>(R.id.settings_status_title)
        val sub = findViewById<TextView>(R.id.settings_status_sub)
        when {
            !serviceEnabled -> {
                title.setText(R.string.settings_status_off)
                sub.setText(R.string.settings_status_sub_off)
            }
            !prefs.masterEnabled -> {
                title.setText(R.string.settings_status_paused)
                sub.setText(R.string.settings_status_sub_paused)
            }
            else -> {
                title.setText(R.string.settings_status_ok)
                sub.setText(R.string.settings_status_sub_ok)
            }
        }
        findViewById<ToggleView>(R.id.settings_toggle).setChecked(active, animate = false)

        findViewById<TextView>(R.id.value_window).text =
            getString(R.string.seconds_format, (prefs.detectionWindowMs / 1000L).toInt())
        findViewById<TextView>(R.id.value_attempts).text =
            getString(R.string.attempts_format, prefs.maxAttempts)

        findViewById<ToggleView>(R.id.toggle_close_ad).setChecked(prefs.closeAdEnabled, animate = false)
        findViewById<ToggleView>(R.id.toggle_vibrate).setChecked(prefs.vibrateEnabled, animate = false)
        findViewById<ToggleView>(R.id.toggle_stats).setChecked(prefs.statsEnabled, animate = false)
    }

    private fun showWindowDialog() {
        val values = longArrayOf(4_000L, 6_000L, 8_000L, 10_000L)
        val labels = arrayOf("4 秒", "6 秒", "8 秒", "10 秒")
        val checked = values.indexOf(prefs.detectionWindowMs).let { if (it < 0) 1 else it }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_window_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.detectionWindowMs = values[which]
                dialog.dismiss()
                refreshHome()
                refreshSettings()
            }
            .show()
    }

    private fun showAttemptsDialog() {
        val values = intArrayOf(2, 3, 5)
        val labels = arrayOf("2 次", "3 次", "5 次")
        val checked = values.indexOf(prefs.maxAttempts).let { if (it < 0) 1 else it }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_attempts_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.maxAttempts = values[which]
                dialog.dismiss()
                refreshHome()
                refreshSettings()
            }
            .show()
    }

    private fun showBackgroundDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.background_dialog_title)
            .setMessage(R.string.background_dialog_body)
            .setNegativeButton(R.string.later, null)
            .setPositiveButton(R.string.background_open_settings) { _, _ ->
                AccessibilityUtils.openBatteryOptimizationSettings(this)
            }
            .show()
    }

    private fun confirmClearStats() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_confirm_title)
            .setMessage(R.string.clear_confirm_msg)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear) { _, _ ->
                stats.clear()
                refreshHome()
                Toast.makeText(this, R.string.toast_stats_cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showInfoDialog(titleRes: Int, messageRes: Int) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openDemoSplash() {
        if (!BuildConfig.DEBUG) return
        try {
            startActivity(
                Intent().setClassName(packageName, "com.lightskip.app.DemoSplashActivity")
            )
        } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_demo_missing, Toast.LENGTH_SHORT).show()
        }
    }

}