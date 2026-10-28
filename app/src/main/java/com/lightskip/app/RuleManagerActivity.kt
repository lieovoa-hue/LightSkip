package com.lightskip.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lightskip.app.data.AppRepository
import com.lightskip.app.data.ImportedRuleStore
import com.lightskip.app.data.PopupMode
import com.lightskip.app.data.Prefs
import com.lightskip.app.data.RuleLearningStore
import com.lightskip.app.service.DiagnosticsStore
import com.lightskip.app.service.RegionRecorderBridge
import com.lightskip.app.util.SystemBarUtils
import com.lightskip.app.util.appIconLetter
import com.lightskip.app.util.dp
import java.text.Collator
import java.util.Locale

class RuleManagerActivity : Activity() {

    private data class RuleApp(
        val packageName: String,
        val label: String,
        val color: Int,
        val ruleCount: Int
    )

    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepository
    private lateinit var store: ImportedRuleStore
    private lateinit var learningStore: RuleLearningStore
    private lateinit var adapter: RuleAppAdapter
    private var pendingRecordPackage: String? = null
    private var pendingRecordTrigger: String = ""
    private var pendingRecordIndex: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = AppRepository(this)
        store = ImportedRuleStore(this)
        learningStore = RuleLearningStore(this)
        setContentView(R.layout.activity_rule_manager)
        SystemBarUtils.apply(this)

        findViewById<View>(R.id.rule_back).setOnClickListener { finish() }
        findViewById<View>(R.id.rule_import_all).setOnClickListener { importAllRules() }
        findViewById<View>(R.id.rule_export_all).setOnClickListener { exportAllRules() }
        findViewById<View>(R.id.rule_clear_all).setOnClickListener { confirmClearAllRules() }
        findViewById<View>(R.id.rule_restore_builtin).setOnClickListener { restoreBuiltinRules() }

        adapter = RuleAppAdapter()
        findViewById<ListView>(R.id.rule_app_list).adapter = adapter
        refresh()
        handleRecordIntent(intent)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRecordIntent(intent)
    }

    private fun refresh() {
        val installed = repo.load().associateBy { it.packageName }
        val collator = Collator.getInstance(Locale.CHINA)
        val items = prefs.targetPackages.map { packageName ->
            val app = installed[packageName]
            RuleApp(
                packageName = packageName,
                label = app?.label ?: packageName.substringAfterLast('.').ifBlank { packageName },
                color = app?.color ?: AppRepository.colorForPackage(packageName),
                ruleCount = store.ruleCount(packageName) + learningStore.ruleCount(packageName)
            )
        }.sortedWith { a, b -> collator.compare(a.label, b.label) }
        adapter.submit(items)

        val list = findViewById<ListView>(R.id.rule_app_list)
        val empty = findViewById<TextView>(R.id.rule_app_empty)
        if (items.isEmpty()) {
            list.visibility = View.GONE
            empty.visibility = View.VISIBLE
        } else {
            list.visibility = View.VISIBLE
            empty.visibility = View.GONE
        }
    }

    private fun showRules(item: RuleApp) {
        val rules = store.getRules(item.packageName).toMutableList()
        val learned = learningStore.getRules(item.packageName).toMutableList()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        var dialog: AlertDialog? = null
        if (rules.isEmpty() && learned.isEmpty()) {
            content.addView(TextView(this).apply {
                setText(R.string.rule_empty)
                setTextColor(getColor(R.color.muted))
                textSize = 13f
                setPadding(0, dp(12), 0, dp(12))
            })
        } else {
            if (rules.isNotEmpty()) {
                content.addView(sectionView(R.string.rule_imported_section))
                rules.forEachIndexed { index, rule ->
                    val row = ruleRow(
                        summary = formatRule(rule),
                        onOpen = {
                            dialog?.dismiss()
                            showRuleEditor(item, index, rule)
                        },
                        onDelete = {
                            dialog?.dismiss()
                            store.setRules(
                                item.packageName,
                                rules.toMutableList().also { it.removeAt(index) }
                            )
                            prefs.bumpRulesRevision()
                            refresh()
                            showRules(item)
                        }
                    )
                    content.addView(row)
                    if (index < rules.lastIndex) content.addView(divider())
                }
            }
            if (learned.isNotEmpty()) {
                content.addView(sectionView(R.string.rule_learned_section))
                learned.forEachIndexed { index, rule ->
                    val row = ruleRow(
                        summary = formatLearnedRule(rule),
                        onOpen = null,
                        onDelete = {
                            dialog?.dismiss()
                            learningStore.removeRule(item.packageName, rule)
                            prefs.bumpRulesRevision()
                            refresh()
                            showRules(item)
                        }
                    )
                    content.addView(row)
                    if (index < learned.lastIndex) content.addView(divider())
                }
            }
            content.addView(TextView(this).apply {
                setText(R.string.rule_clear_app)
                setTextColor(getColor(R.color.amber_text))
                textSize = 12f
                setPadding(0, dp(14), 0, dp(4))
                isClickable = true
                setOnClickListener {
                    AlertDialog.Builder(this@RuleManagerActivity)
                        .setTitle(R.string.rule_clear_confirm_title)
                        .setMessage(R.string.rule_clear_confirm_msg)
                        .setPositiveButton(R.string.rule_clear_confirm) { _, _ ->
                            dialog?.dismiss()
                            store.setRules(item.packageName, emptyList())
                            learningStore.clear(item.packageName)
                            prefs.bumpRulesRevision()
                            refresh()
                            showRules(item)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            })
        }

        val scroll = ScrollView(this).apply { addView(content) }
        dialog = AlertDialog.Builder(this)
            .setTitle(item.label)
            .setView(scroll)
            .setPositiveButton(R.string.rule_add) { d, _ ->
                d.dismiss()
                showRuleEditor(item, null, null)
            }
            .setNeutralButton(R.string.rule_import_app) { d, _ ->
                d.dismiss()
                importForApp(item.packageName)
            }
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.show()
    }

    private fun startRecording(item: RuleApp, trigger: String, index: Int?) {
        pendingRecordPackage = item.packageName
        pendingRecordTrigger = trigger
        pendingRecordIndex = index
        if (!DiagnosticsStore.serviceConnected || RegionRecorderBridge.start == null) {
            Toast.makeText(this, R.string.rule_record_service, Toast.LENGTH_LONG).show()
            clearPendingRecord()
            return
        }
        if (RegionRecorderBridge.start?.invoke(item.packageName) != true) {
            Toast.makeText(this, R.string.rule_record_failed, Toast.LENGTH_SHORT).show()
            clearPendingRecord()
            return
        }
        val intent = packageManager.getLaunchIntentForPackage(item.packageName)
        if (intent == null) {
            RegionRecorderBridge.cancel?.invoke()
            Toast.makeText(this, R.string.rule_record_launch_failed, Toast.LENGTH_SHORT).show()
            clearPendingRecord()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(intent) }.onFailure {
            RegionRecorderBridge.cancel?.invoke()
            Toast.makeText(this, R.string.rule_record_launch_failed, Toast.LENGTH_SHORT).show()
            clearPendingRecord()
        }
    }

    private fun handleRecordIntent(intent: Intent?) {
        val packageName = intent?.getStringExtra(EXTRA_RECORD_PACKAGE) ?: return
        val action = intent.getStringExtra(EXTRA_RECORD_ACTION) ?: return
        intent.removeExtra(EXTRA_RECORD_PACKAGE)
        intent.removeExtra(EXTRA_RECORD_ACTION)
        val installed = repo.load().firstOrNull { it.packageName == packageName }
        val trigger = if (pendingRecordPackage == packageName) pendingRecordTrigger else ""
        val index = if (pendingRecordPackage == packageName) pendingRecordIndex else null
        val item = RuleApp(
            packageName = packageName,
            label = installed?.label
                ?: packageName.substringAfterLast('.').ifBlank { packageName },
            color = installed?.color ?: AppRepository.colorForPackage(packageName),
            ruleCount = store.ruleCount(packageName) + learningStore.ruleCount(packageName)
        )
        clearPendingRecord()
        refresh()
        showRuleEditor(item, index, null, action, trigger)
    }

    private fun clearPendingRecord() {
        pendingRecordPackage = null
        pendingRecordTrigger = ""
        pendingRecordIndex = null
    }

    private fun ruleRow(
        summary: String,
        onOpen: (() -> Unit)?,
        onDelete: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            val text = TextView(this@RuleManagerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = summary
                setTextColor(getColor(R.color.ink))
                textSize = 13f
            }
            val delete = TextView(this@RuleManagerActivity).apply {
                setText(R.string.rule_delete)
                setTextColor(getColor(R.color.amber_text))
                textSize = 12f
                setPadding(dp(12), dp(4), 0, dp(4))
                isClickable = true
                setOnClickListener { onDelete() }
            }
            addView(text)
            addView(delete)
            if (onOpen != null) {
                isClickable = true
                setOnClickListener { onOpen() }
            }
        }
    }

    private fun sectionView(textRes: Int): TextView {
        return TextView(this).apply {
            setText(textRes)
            setTextColor(getColor(R.color.forest_text))
            textSize = 11f
            setPadding(0, dp(10), 0, dp(2))
        }
    }

    private fun divider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            )
            setBackgroundColor(getColor(R.color.row_divider))
        }
    }

    private fun confirmClearAllRules() {
        if (store.importedAppCount() == 0 && learningStore.storedAppCount() == 0) {
            Toast.makeText(this, R.string.rule_clear_all_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rule_clear_all_confirm_title)
            .setMessage(R.string.rule_clear_all_confirm_msg)
            .setPositiveButton(R.string.rule_clear_confirm) { _, _ ->
                store.clearAllRules()
                learningStore.clearAll()
                prefs.bumpRulesRevision()
                refresh()
                Toast.makeText(this, R.string.rule_clear_all_done, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun restoreBuiltinRules() {
        val installed = repo.load().map { it.packageName }.toSet() + prefs.targetPackages
        val result = runCatching { store.importBuiltinRules(this, installed) }.getOrNull()
        if (result == null) {
            Toast.makeText(this, R.string.import_rules_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        if (result.ruleCount == 0) {
            Toast.makeText(this, R.string.builtin_rules_restored_none, Toast.LENGTH_SHORT).show()
            return
        }
        prefs.popupMode = PopupMode.LEARNED
        prefs.bumpRulesRevision()
        refresh()
        Toast.makeText(
            this,
            getString(R.string.builtin_rules_restored_format, result.ruleCount),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun formatLearnedRule(rule: RuleLearningStore.LearnedRule): String {
        val id = when {
            rule.viewId.isNotBlank() -> rule.viewId
            rule.contentDescription.isNotBlank() -> rule.contentDescription
            else -> getString(R.string.rule_learned_region)
        }
        return id.take(80)
    }

    private fun showRuleEditor(
        item: RuleApp,
        index: Int?,
        existing: ImportedRuleStore.PopupRule?,
        initialAction: String? = null,
        initialTrigger: String? = null
    ) {
        var dialog: AlertDialog? = null
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        content.addView(labelView(R.string.rule_trigger_label))
        val triggerInput = EditText(this).apply {
            hint = getString(R.string.rule_trigger_hint)
            setText(existing?.id ?: initialTrigger.orEmpty())
            setSingleLine(false)
        }
        content.addView(triggerInput)
        content.addView(labelView(R.string.rule_action_label))
        val actionInput = EditText(this).apply {
            hint = getString(R.string.rule_action_hint)
            setText(
                existing?.action?.takeUnless { it == BACK_ACTION }
                    ?: initialAction.orEmpty()
            )
            setSingleLine(false)
        }
        content.addView(actionInput)
        val back = CheckBox(this).apply {
            setText(R.string.rule_back_close)
            isChecked = existing?.action == BACK_ACTION
            setOnCheckedChangeListener { _, checked -> actionInput.isEnabled = !checked }
        }
        actionInput.isEnabled = existing?.action != BACK_ACTION
        content.addView(back)
        content.addView(TextView(this).apply {
            setText(R.string.rule_record_close)
            setTextColor(getColor(R.color.forest_text))
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
            isClickable = true
            setOnClickListener {
                val trigger = triggerInput.text?.toString()?.trim().orEmpty()
                dialog?.dismiss()
                startRecording(item, trigger, index)
            }
        })
        content.addView(TextView(this).apply {
            setText(R.string.rule_example)
            setTextColor(getColor(R.color.forest_text))
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
            isClickable = true
            setOnClickListener {
                triggerInput.setText("广告")
                actionInput.isEnabled = true
                actionInput.setText("iv_close")
                back.isChecked = false
                Toast.makeText(this@RuleManagerActivity, R.string.rule_example_filled, Toast.LENGTH_SHORT).show()
            }
        })

        val scroll = ScrollView(this).apply { addView(content) }
        dialog = AlertDialog.Builder(this)
            .setTitle(if (index == null) R.string.rule_add else R.string.rule_edit)
            .setView(scroll)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val activeDialog = dialog ?: return@setOnShowListener
            activeDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = triggerInput.text?.toString()?.trim().orEmpty()
                val action = if (back.isChecked) {
                    BACK_ACTION
                } else {
                    actionInput.text?.toString()?.trim().orEmpty()
                }
                if (id.isEmpty() || action.isEmpty()) {
                    Toast.makeText(this, R.string.rule_fields_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val rules = store.getRules(item.packageName).toMutableList()
                val updated = existing?.copy(id = id, action = action)
                    ?: ImportedRuleStore.PopupRule(id, action, 1, 0L)
                if (index == null) rules.add(updated) else rules[index] = updated
                store.setRules(item.packageName, rules)
                prefs.popupMode = PopupMode.LEARNED
                prefs.bumpRulesRevision()
                activeDialog.dismiss()
                refresh()
                showRules(item)
            }
        }
        dialog?.show()
    }

    private fun labelView(textRes: Int): TextView {
        return TextView(this).apply {
            setText(textRes)
            setTextColor(getColor(R.color.muted))
            textSize = 11f
            setPadding(0, dp(8), 0, 0)
        }
    }

    private fun importAllRules() {
        showImportDialog(
            title = getString(R.string.rule_import_all_dialog_title),
            initial = clipboardText()
        ) { raw ->
            val installed = repo.load().map { it.packageName }.toSet() + prefs.targetPackages
            val result = runCatching { store.importAllRules(raw, installed) }.getOrNull()
            if (result == null) {
                Toast.makeText(this, R.string.import_rules_invalid, Toast.LENGTH_SHORT).show()
                return@showImportDialog false
            }
            if (result.ruleCount == 0) {
                val message = if (result.matchedApps > 0) {
                    getString(R.string.import_rules_already_exists)
                } else {
                    getString(R.string.import_rules_none_format, result.unmatchedHashes)
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return@showImportDialog false
            }
            val targets = prefs.targetPackages.toMutableSet()
            targets.addAll(result.packages)
            prefs.targetPackages = targets
            prefs.popupMode = PopupMode.LEARNED
            prefs.bumpRulesRevision()
            refresh()
            Toast.makeText(
                this,
                getString(R.string.import_rules_success_format, result.matchedApps, result.ruleCount),
                Toast.LENGTH_LONG
            ).show()
            true
        }
    }

    private fun importForApp(packageName: String) {
        showImportDialog(
            title = getString(R.string.rule_import_app_dialog_title, labelFor(packageName)),
            initial = clipboardText()
        ) { raw ->
            val count = runCatching { store.importForPackage(packageName, raw) }.getOrNull()
            if (count == null) {
                Toast.makeText(this, R.string.import_rules_invalid, Toast.LENGTH_SHORT).show()
                return@showImportDialog false
            }
            prefs.setTarget(packageName, true)
            prefs.popupMode = PopupMode.LEARNED
            prefs.bumpRulesRevision()
            refresh()
            Toast.makeText(
                this,
                getString(R.string.import_rules_single_success_format, labelFor(packageName), count),
                Toast.LENGTH_LONG
            ).show()
            true
        }
    }

    private fun showImportDialog(
        title: String,
        initial: String,
        onImport: (String) -> Boolean
    ) {
        val input = EditText(this).apply {
            hint = getString(R.string.rule_import_dialog_hint)
            setText(initial)
            if (initial.isNotEmpty()) setSelection(initial.length)
            gravity = Gravity.TOP or Gravity.START
            minLines = 8
            maxLines = 12
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(6))
            addView(input)
            addView(TextView(this@RuleManagerActivity).apply {
                setText(R.string.rule_import_dialog_note)
                setTextColor(getColor(R.color.muted))
                textSize = 11f
                setPadding(0, dp(6), 0, 0)
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton(R.string.rule_import_dialog_confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = input.text?.toString()?.trim().orEmpty()
                if (raw.isEmpty()) {
                    Toast.makeText(this, R.string.import_rules_empty, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (onImport(raw)) dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun exportAllRules() {
        val json = store.exportAllRules()
        if (json == "[]") {
            Toast.makeText(this, R.string.rule_export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rule_export_title)
            .setMessage(R.string.rule_export_sub)
            .setPositiveButton(R.string.copy) { _, _ -> copyText(json) }
            .setNeutralButton(R.string.share) { _, _ -> shareText(json) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clipboardText(): String {
        val clipboard = getSystemService(ClipboardManager::class.java)
        return clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
            .orEmpty()
    }

    private fun copyText(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("LightSkip rules", text))
        Toast.makeText(this, R.string.rule_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.rule_export_title)))
    }

    private fun labelFor(packageName: String): String {
        return repo.load().firstOrNull { it.packageName == packageName }?.label
            ?: packageName.substringAfterLast('.').ifBlank { packageName }
    }

    private fun formatRule(rule: ImportedRuleStore.PopupRule): String {
        val action = if (rule.action == BACK_ACTION) getString(R.string.rule_back_short) else rule.action
        return "${rule.id.take(80)} → ${action.take(80)}"
    }

    private inner class RuleAppAdapter : BaseAdapter() {
        private var items: List<RuleApp> = emptyList()
        private val inflater = LayoutInflater.from(this@RuleManagerActivity)

        fun submit(list: List<RuleApp>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): RuleApp = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.item_rule_app, parent, false)
            val holder = view.tag as? Holder ?: Holder(view).also { view.tag = it }
            val item = items[position]
            holder.icon.text = appIconLetter(item.label)
            holder.icon.backgroundTintList = ColorStateList.valueOf(item.color)
            holder.name.text = item.label
            holder.sub.text = item.packageName
            holder.count.text = if (item.ruleCount > 0) {
                getString(R.string.rule_count_format, item.ruleCount)
            } else {
                getString(R.string.rule_none_short)
            }
            view.setOnClickListener { showRules(item) }
            return view
        }

        private inner class Holder(view: View) {
            val icon: TextView = view.findViewById(R.id.rule_app_icon)
            val name: TextView = view.findViewById(R.id.rule_app_name)
            val sub: TextView = view.findViewById(R.id.rule_app_sub)
            val count: TextView = view.findViewById(R.id.rule_app_count)
        }
    }

    companion object {
        const val BACK_ACTION = "GLOBAL_ACTION_BACK"
        const val EXTRA_RECORD_PACKAGE = "record_package"
        const val EXTRA_RECORD_ACTION = "record_action"
    }
}
