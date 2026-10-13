package com.lumobrowser.app.crash

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.lumobrowser.app.MainActivity
import com.lumobrowser.app.R

/**
 * What the user sees after the app has died: the report, and a way back in.
 *
 * Built from platform views rather than Compose, and it touches neither WebView nor the app's theme
 * system. That is the entire design: everything this screen needs is already proven to work — the
 * Activity, the resource table and the crash guard wrote this report from the same process — while
 * whatever killed the app is by definition *not* proven, and the two most complex things in this
 * codebase are the Compose tree and the WebView. A recovery screen that can itself fail to start is
 * worth nothing.
 *
 * The report is shown as the exact text that gets copied or shared, so there is no chance of the user
 * sending something different from what they saw.
 */
class CrashRecoveryActivity : Activity() {

    private lateinit var container: LinearLayout
    private var record: CrashRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        record = CrashGuard.lastCrash(this)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@CrashRecoveryActivity, R.color.lumo_backdrop))
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        scroll.addView(
            container,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        setContentView(scroll)
        render()
    }

    /** Rebuilds the screen; called again after the report is deleted so the screen cannot lie. */
    private fun render() {
        container.removeAllViews()
        val current = record
        container.addView(headline(getString(R.string.crash_recovery_title)))
        container.addView(body(getString(R.string.crash_recovery_subtitle)))
        if (current == null) {
            container.addView(body(getString(R.string.crash_recovery_empty)))
        } else {
            container.addView(
                body(
                    getString(
                        R.string.crash_recovery_when,
                        current.occurredAtLabel,
                        current.exceptionClass,
                        CrashGuard.crashCount(this),
                    ),
                ),
            )
            container.addView(code(current.toShareText()))
        }
        container.addView(body(getString(R.string.crash_recovery_safe_note)))
        container.addView(
            button(getString(R.string.crash_recovery_start_safe), primary = true) {
                CrashGuard.setSafeMode(this, true)
                CrashGuard.clearPendingCrash(this)
                launchApp()
            },
        )
        container.addView(
            button(getString(R.string.crash_recovery_start_normal), primary = false) {
                CrashGuard.setSafeMode(this, false)
                CrashGuard.clearPendingCrash(this)
                launchApp()
            },
        )
        if (current != null) {
            container.addView(
                button(getString(R.string.crash_recovery_copy), primary = false) {
                    copyReport(current.toShareText())
                },
            )
            container.addView(
                button(getString(R.string.crash_recovery_share), primary = false) {
                    shareReport(current.toShareText())
                },
            )
            container.addView(
                button(getString(R.string.crash_recovery_clear), primary = false) {
                    CrashGuard.clearCrash(this)
                    record = null
                    render()
                    toast(getString(R.string.crash_recovery_cleared))
                },
            )
        }
    }

    /**
     * Leaves for the app proper.
     *
     * `finish()` matters as much as the start: this Activity was launched into its own task
     * ([CrashGuard.recoveryIntent] clears the task), so leaving it alive would put the recovery
     * screen behind the app instead of replacing it.
     */
    private fun launchApp() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun copyReport(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            toast(getString(R.string.crash_recovery_copy_failed))
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.crash_recovery_share_subject), text))
        toast(getString(R.string.crash_recovery_copied))
    }

    private fun shareReport(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.crash_recovery_share_subject))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, getString(R.string.crash_recovery_share))
        // A device with nothing to share to is not a reason to crash the crash screen.
        runCatching { startActivity(chooser) }
            .onFailure { toast(getString(R.string.crash_recovery_share_failed)) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun headline(text: String): TextView = TextView(this).apply {
        setText(text)
        setTextColor(ContextCompat.getColor(this@CrashRecoveryActivity, R.color.lumo_recovery_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(8))
    }

    private fun body(text: String): TextView = TextView(this).apply {
        setText(text)
        setTextColor(ContextCompat.getColor(this@CrashRecoveryActivity, R.color.lumo_recovery_text_muted))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, 0, 0, dp(12))
    }

    private fun code(text: String): TextView = TextView(this).apply {
        setText(text)
        setTextColor(ContextCompat.getColor(this@CrashRecoveryActivity, R.color.lumo_recovery_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setBackgroundColor(ContextCompat.getColor(this@CrashRecoveryActivity, R.color.lumo_surface))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(16) }
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        setText(label)
        isAllCaps = false
        setTextColor(
            if (primary) {
                ContextCompat.getColor(this@CrashRecoveryActivity, R.color.lumo_backdrop)
            } else {
                ContextCompat.getColor(this@CrashRecoveryActivity, R.color.lumo_recovery_text)
            },
        )
        backgroundTintList = ColorStateList.valueOf(
            if (primary) {
                ContextCompat.getColor(this@CrashRecoveryActivity, R.color.lumo_accent)
            } else {
                ContextCompat.getColor(this@CrashRecoveryActivity, R.color.lumo_surface)
            },
        )
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()
}
