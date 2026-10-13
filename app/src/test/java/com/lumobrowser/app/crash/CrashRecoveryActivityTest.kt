package com.lumobrowser.app.crash

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.lumobrowser.app.MainActivity
import com.lumobrowser.app.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The screen that has to work when nothing else does: no Compose, no WebView, no theme resolution,
 * just platform views over a text file. It is also the only place a user can choose a way forward, so
 * every button is exercised here rather than assumed.
 *
 * Unlike [com.lumobrowser.app.LanguageSwitchTest], `ActivityScenario.launch` is safe here: nothing on
 * this screen animates, so the looper drains and the launch returns.
 */
@RunWith(RobolectricTestRunner::class)
class CrashRecoveryActivityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        CrashGuard.clearCrash(context)
        CrashGuard.setSafeMode(context, false)
    }

    @After
    fun tearDown() {
        CrashGuard.clearCrash(context)
        CrashGuard.setSafeMode(context, false)
    }

    @Test
    fun `a stored crash is shown in full, with both ways back into the app`() {
        CrashGuard.record(context, IllegalStateException("the device said no"), "main")

        ActivityScenario.launch(CrashRecoveryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val shown = textOf(activity)
                assertTrue(shown.contains(activity.getString(R.string.crash_recovery_title)))
                assertTrue(shown.contains(CrashRecord.HEADER))
                assertTrue(shown.contains("java.lang.IllegalStateException"))
                assertTrue(shown.contains("the device said no"))
                assertTrue(shown.contains("page size: "))
                assertTrue(shown.contains("webview: "))
                assertNotNull(button(activity, activity.getString(R.string.crash_recovery_start_safe)))
                assertNotNull(button(activity, activity.getString(R.string.crash_recovery_start_normal)))
                assertNotNull(button(activity, activity.getString(R.string.crash_recovery_copy)))
                assertNotNull(button(activity, activity.getString(R.string.crash_recovery_share)))
                assertNotNull(button(activity, activity.getString(R.string.crash_recovery_clear)))
            }
        }
    }

    @Test
    fun `the screen states how many crashes this install has recorded`() {
        CrashGuard.record(context, IllegalStateException("first"), "main")
        CrashGuard.record(context, IllegalStateException("second"), "main")
        val report = CrashGuard.lastCrash(context)!!

        ActivityScenario.launch(CrashRecoveryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val expected = activity.getString(
                    R.string.crash_recovery_when,
                    report.occurredAtLabel,
                    report.exceptionClass,
                    CrashGuard.crashCount(context),
                )
                assertTrue(expected.contains("2"))
                assertTrue(textOf(activity).contains(expected))
            }
        }
    }

    @Test
    fun `with no report the screen says so and still offers a way in`() {
        ActivityScenario.launch(CrashRecoveryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val shown = textOf(activity)
                assertTrue(shown.contains(activity.getString(R.string.crash_recovery_empty)))
                assertNull(button(activity, activity.getString(R.string.crash_recovery_copy)))
                assertNull(button(activity, activity.getString(R.string.crash_recovery_clear)))
                assertNotNull(button(activity, activity.getString(R.string.crash_recovery_start_safe)))
            }
        }
    }

    @Test
    fun `starting in safe mode queues one safe launch and acknowledges the crash`() {
        CrashGuard.record(context, IllegalStateException("boom"), "main")
        var started: Intent? = null

        ActivityScenario.launch(CrashRecoveryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                button(activity, activity.getString(R.string.crash_recovery_start_safe))!!.performClick()
                started = shadowOf(activity).nextStartedActivity
            }
        }

        assertEquals(MainActivity::class.java.name, started?.component?.className)
        assertFalse(CrashGuard.hasPendingCrash(context))
        assertTrue(CrashGuard.consumeSafeMode(context))
    }

    @Test
    fun `starting normally acknowledges the crash and queues no safe mode`() {
        CrashGuard.record(context, IllegalStateException("boom"), "main")

        ActivityScenario.launch(CrashRecoveryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                button(activity, activity.getString(R.string.crash_recovery_start_normal))!!.performClick()
            }
        }

        assertFalse(CrashGuard.hasPendingCrash(context))
        assertFalse(CrashGuard.consumeSafeMode(context))
    }

    @Test
    fun `copying puts exactly the shown report on the clipboard`() {
        CrashGuard.record(context, IllegalStateException("clipboard me"), "main")

        ActivityScenario.launch(CrashRecoveryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                button(activity, activity.getString(R.string.crash_recovery_copy))!!.performClick()
            }
        }

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        assertNotNull(copied)
        assertTrue(copied!!.startsWith(CrashRecord.HEADER))
        assertTrue(copied.contains("clipboard me"))
    }

    @Test
    fun `sharing hands the report to the system chooser instead of sending it anywhere`() {
        CrashGuard.record(context, IllegalStateException("share me"), "main")

        ActivityScenario.launch(CrashRecoveryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                button(activity, activity.getString(R.string.crash_recovery_share))!!.performClick()
                assertEquals(Intent.ACTION_CHOOSER, shadowOf(activity).nextStartedActivity?.action)
            }
        }
    }

    @Test
    fun `deleting the report clears it and the screen stops claiming there was one`() {
        CrashGuard.record(context, IllegalStateException("gone"), "main")

        ActivityScenario.launch(CrashRecoveryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                button(activity, activity.getString(R.string.crash_recovery_clear))!!.performClick()

                assertNull(CrashGuard.lastCrash(context))
                assertEquals(0, CrashGuard.crashCount(context))
                val shown = textOf(activity)
                assertTrue(shown.contains(activity.getString(R.string.crash_recovery_empty)))
                assertNull(button(activity, activity.getString(R.string.crash_recovery_clear)))
            }
        }
    }

    /** Every word on the screen, in view order. */
    private fun textOf(activity: Activity): String = buildString {
        collect(activity.findViewById(android.R.id.content), this)
    }

    private fun collect(view: View?, into: StringBuilder) {
        if (view == null) return
        if (view is TextView) into.append(view.text).append('\n')
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collect(view.getChildAt(index), into)
        }
    }

    private fun button(activity: Activity, label: String): Button? =
        find(activity.findViewById(android.R.id.content), label)

    private fun find(view: View?, label: String): Button? {
        if (view == null) return null
        if (view is Button && view.text?.toString() == label) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) find(view.getChildAt(index), label)?.let { return it }
        }
        return null
    }
}
