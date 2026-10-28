package com.lightskip.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import com.lightskip.app.data.ImportedRuleStore
import com.lightskip.app.data.PopupMode
import com.lightskip.app.data.Prefs
import com.lightskip.app.data.RuleLearningStore

class DebugRuleTestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var message = "debug rule test"

        intent.getStringExtra("clipboard_b64")?.let { encoded ->
            val text = String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("LightSkip test rules", text))
            message = "clipboard set: ${text.length}"
        }

        val target = intent.getStringExtra("package")
        val encodedRules = intent.getStringExtra("rules_b64")
        if (!target.isNullOrBlank() && !encodedRules.isNullOrBlank()) {
            val raw = String(Base64.decode(encodedRules, Base64.NO_WRAP), Charsets.UTF_8)
            if (intent.getBooleanExtra("clear_learned", false)) {
                RuleLearningStore(this).clear(target)
            }
            val count = ImportedRuleStore(this).importForPackage(target, raw)
            Prefs(this).apply {
                setTarget(target, true)
                popupMode = PopupMode.LEARNED
            }
            message = "imported $count for $target"
        }

        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
