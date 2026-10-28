package com.lightskip.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.PowerManager
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
import com.lightskip.app.data.ImportedRuleStore
import com.lightskip.app.data.LocalStats
import com.lightskip.app.data.PopupMode
import com.lightskip.app.data.Prefs
import com.lightskip.app.service.DiagnosticsStore
import com.lightskip.app.ui.AppListAdapter
import com.lightskip.app.ui.ToggleView
import com.lightskip.app.util.AccessibilityUtils
import com.lightskip.app.util.SystemBarUtils
import com.lightskip.app.util.formatCount

class MainActivity : Activity() {

    private enum class Tab { HOME, APPS, SETTINGS }
    private enum class Filter { ALL, ENABLED, EXCLUDED }

    private lateinit var prefs: Prefs
    private lateinit var stats: LocalStats
    private lateinit var repo: AppRepository
    private lateinit var importedRules: ImportedRuleStore
    private lateinit var appListAdapter: AppListAdapter
    private var allApps: List<AppEntry> = emptyList()
    private var currentTab = Tab.HOME
    private var appsFilter = Filter.ALL
    private var searchQuery = ""
    private var keepTaskOnStop = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        stats = LocalStats(this, prefs)
        stats.prune()
        repo = AppRepository(this)
        importedRules = ImportedRuleStore(this)

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
        keepTaskOnStop = false
        finishLearningIfReturned()
        refreshHome()
        refreshSettings()
        if (currentTab == Tab.APPS) refreshApps()
    }

    override fun onStop() {
        super.onStop()
        if (!keepTaskOnStop && !isChangingConfigurations && !isFinishing) {
            finishAndRemoveTask()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentTab != Tab.HOME) selectTab(Tab.HOME) else super.onBackPressed()
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
        findViewById<ToggleView>(R.id.master_toggle).onCheckedChange = { handleMasterToggle(it) }

        findViewById<View>(R.id.guard_card).setOnClickListener {
            if (!AccessibilityUtils.isServiceEnabled(this)) {
                AccessibilityUtils.openAccessibilitySettings(this)
            }
        }
        findViewById<View>(R.id.check_accessibility).setOnClickListener {
            AccessibilityUtils.openAccessibilitySettings(this)
        }
        findViewById<View>(R.id.check_background).setOnClickListener {
            showBackgroundDialog()
        }
        findViewById<View>(R.id.check_apps).setOnClickListener { selectTab(Tab.APPS) }
        findViewById<View>(R.id.check_popup).setOnClickListener { showPopupModeDialog() }
        findViewById<View>(R.id.home_test_splash).setOnClickListener { openDemoSplash() }
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
        val connected = DiagnosticsStore.serviceConnected
        val masterEnabled = prefs.masterEnabled
        val active = serviceEnabled && masterEnabled

        findViewById<TextView>(R.id.guard_state).setText(
            when {
                !serviceEnabled -> R.string.needs_permission
                !masterEnabled -> R.string.paused
                !connected -> R.string.status_not_connected
                else -> R.string.guarding
            }
        )
        findViewById<ToggleView>(R.id.master_toggle).setChecked(active, animate = false)

        val skips = stats.todaySkips()
        findViewById<TextView>(R.id.home_count).text = formatCount(skips)
        findViewById<TextView>(R.id.home_app_count).text =
            getString(R.string.apps_count_format, prefs.targetPackages.size)

        val accessibilitySub = findViewById<TextView>(R.id.check_accessibility_sub)
        accessibilitySub.setText(
            if (serviceEnabled && !connected) R.string.check_accessibility_unconnected_sub
            else R.string.check_accessibility_sub
        )
        val accessibilityStatus = findViewById<TextView>(R.id.check_accessibility_status)
        when {
            !serviceEnabled -> setStatus(accessibilityStatus, R.string.status_off, R.color.amber_text)
            !connected -> setStatus(accessibilityStatus, R.string.status_not_connected, R.color.amber_text)
            else -> setStatus(accessibilityStatus, R.string.status_connected, R.color.forest_text)
        }

        val batteryStatus = findViewById<TextView>(R.id.check_background_status)
        val unrestricted = runCatching {
            val power = getSystemService(PowerManager::class.java)
            power.isIgnoringBatteryOptimizations(packageName)
        }.getOrDefault(false)
        if (unrestricted) {
            setStatus(batteryStatus, R.string.battery_unrestricted, R.color.forest_text)
        } else {
            setStatus(batteryStatus, R.string.battery_restricted, R.color.amber_text)
        }

        val appsStatus = findViewById<TextView>(R.id.check_apps_status)
        if (prefs.targetPackages.isEmpty()) {
            setStatus(appsStatus, R.string.apps_zero, R.color.amber_text)
        } else {
            setStatus(
                appsStatus,
                R.string.apps_count_format,
                R.color.forest_text,
                prefs.targetPackages.size
            )
        }

        val popupStatus = findViewById<TextView>(R.id.check_popup_status)
        setStatus(
            popupStatus,
            popupModeLabel(prefs.popupMode),
            if (prefs.popupMode == PopupMode.OFF) R.color.muted else R.color.forest_text
        )

        findViewById<View>(R.id.home_test_splash).visibility =
            if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
    }

    private fun setStatus(view: TextView, textRes: Int, colorRes: Int, vararg args: Any) {
        view.text = if (args.isEmpty()) getString(textRes) else getString(textRes, *args)
        view.setTextColor(getColor(colorRes))
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
        findViewById<View>(R.id.row_accessibility).setOnClickListener {
            AccessibilityUtils.openAccessibilitySettings(this)
        }
        findViewById<View>(R.id.row_background).setOnClickListener { showBackgroundDialog() }
        findViewById<ToggleView>(R.id.toggle_compat).onCheckedChange = {
            prefs.compatibilityMode = it
            refreshHome()
            refreshSettings()
        }
        findViewById<View>(R.id.row_popup).setOnClickListener { showPopupModeDialog() }
        findViewById<View>(R.id.row_import_rules).setOnClickListener { importRulesFromClipboard() }
        findViewById<View>(R.id.row_learning).setOnClickListener { onLearningClicked() }
        findViewById<View>(R.id.row_diagnostics).setOnClickListener { showDiagnosticsDialog() }
        findViewById<View>(R.id.row_privacy).setOnClickListener {
            showInfoDialog(R.string.privacy_title, R.string.privacy_body)
        }

        findViewById<View>(R.id.row_demo).apply {
            visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
            setOnClickListener { openDemoSplash() }
        }
        findViewById<View>(R.id.row_demo_popup).apply {
            visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
            setOnClickListener { openDemoPopup() }
        }
    }

    private fun refreshSettings() {
        val serviceEnabled = AccessibilityUtils.isServiceEnabled(this)
        val connected = DiagnosticsStore.serviceConnected
        val active = serviceEnabled && prefs.masterEnabled

        val title = findViewById<TextView>(R.id.settings_status_title)
        val sub = findViewById<TextView>(R.id.settings_status_sub)
        when {
            !serviceEnabled -> {
                title.setText(R.string.settings_status_off)
                sub.setText(R.string.settings_status_sub_off)
            }
            !connected -> {
                title.setText(R.string.settings_status_unconnected)
                sub.setText(R.string.settings_status_sub_unconnected)
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

        val accessibilityValue = findViewById<TextView>(R.id.value_accessibility)
        when {
            !serviceEnabled -> setStatus(accessibilityValue, R.string.status_off, R.color.amber_text)
            !connected -> setStatus(accessibilityValue, R.string.status_not_connected, R.color.amber_text)
            else -> setStatus(accessibilityValue, R.string.status_connected, R.color.forest_text)
        }

        findViewById<ToggleView>(R.id.toggle_compat).setChecked(prefs.compatibilityMode, animate = false)
        findViewById<TextView>(R.id.value_popup).setText(popupModeLabel(prefs.popupMode))
        val importedCount = importedRules.importedAppCount()
        findViewById<TextView>(R.id.value_import_rules).text =
            if (importedCount > 0) {
                getString(R.string.import_rules_count_format, importedCount)
            } else {
                getString(R.string.import_rules_value)
            }
        val learningValue = findViewById<TextView>(R.id.value_learning)
        when {
            !prefs.learningMode -> {
                learningValue.setText(R.string.learning_off)
                learningValue.setTextColor(getColor(R.color.muted))
            }
            !prefs.learningArmed -> {
                learningValue.setText(R.string.learning_waiting_splash)
                learningValue.setTextColor(getColor(R.color.amber_text))
            }
            else -> {
                learningValue.text = getString(R.string.learning_captured_format, prefs.learningSavedCount)
                learningValue.setTextColor(getColor(R.color.forest_text))
            }
        }
    }

    private fun popupModeLabel(mode: PopupMode): Int = when (mode) {
        PopupMode.OFF -> R.string.popup_off
        PopupMode.LEARNED -> R.string.popup_learned
    }

    private fun showPopupModeDialog() {
        val modes = arrayOf(PopupMode.OFF, PopupMode.LEARNED)
        val labels = arrayOf(
            getString(R.string.popup_off),
            getString(R.string.popup_learned)
        )
        val checked = modes.indexOf(prefs.popupMode).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.check_popup)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                prefs.popupMode = modes[which]
                if (modes[which] == PopupMode.LEARNED) {
                    Toast.makeText(this, R.string.popup_learned_warning, Toast.LENGTH_LONG).show()
                }
                dialog.dismiss()
                refreshHome()
                refreshSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onLearningClicked() {
        if (prefs.learningMode) {
            finishLearningIfReturned()
            refreshSettings()
            return
        }

        val entries = mutableListOf<Pair<String, String>>()
        if (BuildConfig.DEBUG) entries.add(packageName to getString(R.string.learning_test_app))
        entries.addAll(
            repo.load()
                .filter { prefs.isTarget(it.packageName) }
                .map { it.packageName to it.label }
        )
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.learning_no_apps, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = entries.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.learning_choose_app)
            .setItems(labels) { _, which ->
                val (targetPackage, label) = entries[which]
                prefs.startLearning(targetPackage)
                DiagnosticsStore.clearLearning()
                Toast.makeText(
                    this,
                    getString(R.string.learning_started_format, label),
                    Toast.LENGTH_LONG
                ).show()
                refreshSettings()
                if (!launchLearningTarget(targetPackage)) {
                    prefs.stopLearning()
                    Toast.makeText(this, R.string.learning_launch_failed, Toast.LENGTH_LONG).show()
                    refreshSettings()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun launchLearningTarget(targetPackage: String): Boolean {
        if (BuildConfig.DEBUG && targetPackage == packageName) {
            keepTaskOnStop = true
            return try {
                startActivity(
                    Intent().setClassName(packageName, "com.lightskip.app.DemoPopupActivity")
                )
                true
            } catch (_: Exception) {
                keepTaskOnStop = false
                false
            }
        }

        val intent = packageManager.getLaunchIntentForPackage(targetPackage) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun finishLearningIfReturned() {
        if (!prefs.learningMode) return
        val count = prefs.learningSavedCount
        prefs.stopLearning()
        DiagnosticsStore.clearLearning()
        val message = if (count > 0) {
            getString(R.string.learning_finished_format, count)
        } else {
            getString(R.string.learning_finished_none)
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun importRulesFromClipboard() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val raw = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (raw.isEmpty()) {
            Toast.makeText(this, R.string.import_rules_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val installedPackages = repo.load().map { it.packageName }.toSet()
        val result = runCatching {
            importedRules.importAllRules(raw, installedPackages)
        }.getOrNull()
        if (result != null) {
            if (result.ruleCount > 0) {
                val targets = prefs.targetPackages.toMutableSet()
                targets.addAll(result.packages)
                prefs.targetPackages = targets
                prefs.popupMode = PopupMode.LEARNED
                Toast.makeText(
                    this,
                    getString(R.string.import_rules_success_format, result.matchedApps, result.ruleCount),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.import_rules_none_format, result.unmatchedHashes),
                    Toast.LENGTH_LONG
                ).show()
            }
            refreshHome()
            refreshSettings()
            return
        }

        chooseImportTarget(raw)
    }

    private fun chooseImportTarget(raw: String) {
        val apps = repo.load()
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.import_rules_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = apps.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.import_rules_choose_app)
            .setItems(labels) { _, which ->
                val entry = apps[which]
                val count = runCatching {
                    importedRules.importForPackage(entry.packageName, raw)
                }.getOrNull() ?: run {
                    Toast.makeText(this, R.string.import_rules_invalid, Toast.LENGTH_LONG).show()
                    return@setItems
                }
                prefs.setTarget(entry.packageName, true)
                prefs.popupMode = PopupMode.LEARNED
                Toast.makeText(
                    this,
                    getString(R.string.import_rules_single_success_format, entry.label, count),
                    Toast.LENGTH_LONG
                ).show()
                refreshHome()
                refreshSettings()
            }
            .setNegativeButton(R.string.cancel, null)
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

    private fun showDiagnosticsDialog() {
        val text = DiagnosticsStore.format(this, prefs)
        AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics_title)
            .setMessage(text)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.share_diagnostics) { _, _ -> shareDiagnostics(text) }
            .setPositiveButton(R.string.copy) { _, _ -> copyDiagnostics(text) }
            .show()
    }

    private fun copyDiagnostics(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("LightSkip diagnostics", text))
        Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareDiagnostics(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_diagnostics)))
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
        keepTaskOnStop = true
        try {
            startActivity(Intent().setClassName(packageName, "com.lightskip.app.DemoSplashActivity"))
        } catch (_: Exception) {
            keepTaskOnStop = false
            Toast.makeText(this, R.string.toast_demo_missing, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDemoPopup() {
        if (!BuildConfig.DEBUG) return
        keepTaskOnStop = true
        try {
            startActivity(Intent().setClassName(packageName, "com.lightskip.app.DemoPopupActivity"))
        } catch (_: Exception) {
            keepTaskOnStop = false
            Toast.makeText(this, R.string.toast_demo_missing, Toast.LENGTH_SHORT).show()
        }
    }
}
