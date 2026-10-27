package com.lightskip.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.lightskip.app.data.AppEntry
import com.lightskip.app.data.AppRepository
import com.lightskip.app.data.Prefs
import com.lightskip.app.ui.AppListAdapter
import com.lightskip.app.util.AccessibilityUtils
import com.lightskip.app.util.SystemBarUtils

class OnboardingActivity : Activity() {

    private enum class Page { WELCOME, PERMISSION, APPS }

    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepository
    private var page = Page.WELCOME

    private var allApps: List<AppEntry> = emptyList()
    private val selectedPackages = mutableSetOf<String>()
    private lateinit var appListAdapter: AppListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = AppRepository(this)

        if (prefs.onboardingDone) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        showWelcome()
    }

    override fun onResume() {
        super.onResume()
        if (page == Page.PERMISSION) updatePermissionUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (page) {
            Page.WELCOME -> super.onBackPressed()
            Page.PERMISSION -> showWelcome()
            Page.APPS -> showPermission()
        }
    }

    private fun showWelcome() {
        page = Page.WELCOME
        setPage(R.layout.activity_onboarding_welcome)
        findViewById<View>(R.id.welcome_start).setOnClickListener { showPermission() }
    }

    private fun showPermission() {
        page = Page.PERMISSION
        setPage(R.layout.activity_onboarding_permission)
        findViewById<View>(R.id.permission_back).setOnClickListener { showWelcome() }
        findViewById<View>(R.id.permission_later).setOnClickListener { showApps() }
        findViewById<View>(R.id.permission_action).setOnClickListener {
            if (AccessibilityUtils.isServiceEnabled(this)) {
                showApps()
            } else {
                AccessibilityUtils.openAccessibilitySettings(this)
                Toast.makeText(this, R.string.toast_service_needed, Toast.LENGTH_SHORT).show()
            }
        }
        updatePermissionUi()
    }

    private fun updatePermissionUi() {
        val enabled = AccessibilityUtils.isServiceEnabled(this)
        val status = findViewById<TextView>(R.id.permission_status)
        status.setText(if (enabled) R.string.status_on else R.string.status_off)
        status.setTextColor(getColor(if (enabled) R.color.forest_text else R.color.amber_text))
        status.setBackgroundResource(
            if (enabled) R.drawable.bg_success_pill else R.drawable.bg_status_pill_amber
        )

        val action = findViewById<TextView>(R.id.permission_action)
        action.setText(if (enabled) R.string.continue_label else R.string.go_settings)
    }

    private fun showApps() {
        page = Page.APPS
        allApps = repo.load()
        val known = if (prefs.hasChosenApps) prefs.targetPackages else repo.recommendedInstalled()
        val installed = allApps.mapTo(HashSet()) { it.packageName }
        selectedPackages.clear()
        selectedPackages.addAll(known.filter { it in installed })

        setPage(R.layout.activity_onboarding_apps)
        appListAdapter = AppListAdapter(
            context = this,
            isEnabled = { it in selectedPackages },
            onToggle = { entry, enabled ->
                if (enabled) selectedPackages.add(entry.packageName) else selectedPackages.remove(entry.packageName)
                updateSummary()
            }
        )
        findViewById<ListView>(R.id.onboarding_app_list).adapter = appListAdapter
        findViewById<View>(R.id.apps_back).setOnClickListener { showPermission() }
        findViewById<View>(R.id.onboarding_done).setOnClickListener { finishOnboarding() }
        findViewById<View>(R.id.onboarding_select_all).setOnClickListener {
            if (selectedPackages.size >= allApps.size) {
                selectedPackages.clear()
            } else {
                selectedPackages.addAll(allApps.map { it.packageName })
            }
            appListAdapter.notifyDataSetChanged()
            updateSummary()
        }

        rebuildAppList()
    }

    private fun rebuildAppList() {
        val list = findViewById<ListView>(R.id.onboarding_app_list)
        val empty = findViewById<TextView>(R.id.onboarding_app_empty)
        if (allApps.isEmpty()) {
            list.visibility = View.GONE
            empty.visibility = View.VISIBLE
            appListAdapter.submit(emptyList())
        } else {
            list.visibility = View.VISIBLE
            empty.visibility = View.GONE
            appListAdapter.submit(allApps)
        }
        updateSummary()
    }

    private fun updateSummary() {
        val summary = findViewById<TextView>(R.id.onboarding_summary)
        summary.text = getString(
            R.string.apps_summary_format,
            selectedPackages.size,
            (allApps.size - selectedPackages.size).coerceAtLeast(0)
        )

        val selectAll = findViewById<TextView>(R.id.onboarding_select_all)
        selectAll.setText(if (allApps.isNotEmpty() && selectedPackages.size >= allApps.size) R.string.clear_all else R.string.select_all)
    }

    private fun finishOnboarding() {
        prefs.targetPackages = selectedPackages
        prefs.hasChosenApps = true
        prefs.onboardingDone = true
        Toast.makeText(
            this,
            getString(R.string.toast_selected_apps_format, selectedPackages.size),
            Toast.LENGTH_SHORT
        ).show()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun setPage(layoutRes: Int) {
        setContentView(layoutRes)
        SystemBarUtils.apply(this)
    }

}