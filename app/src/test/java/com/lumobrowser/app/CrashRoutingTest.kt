package com.lumobrowser.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lumobrowser.app.crash.CrashGuard
import com.lumobrowser.app.crash.CrashRecoveryActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The one path through [MainActivity] that must never build the Compose tree: a crash is waiting to
 * be explained, so the app hands over to the recovery screen and stops.
 *
 * Only the pending case is tested here, and that is deliberate. The other case composes the whole app
 * — including the drifting backdrop, one of the two effects this routing exists to keep away from a
 * crashing process — and a bare `buildActivity` around it would sit in the same unbounded looper
 * `LanguageSwitchTest` had to be rescued from. That path is already covered by
 * [com.lumobrowser.app.ui.components.StartupSmokeTest], which drives Compose through its own frame
 * clock.
 */
@RunWith(RobolectricTestRunner::class)
class CrashRoutingTest {

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
    fun `a pending crash opens the recovery screen instead of the app`() {
        CrashGuard.record(context, IllegalStateException("killed the last run"), "main")

        Robolectric.buildActivity(MainActivity::class.java).create().use { controller ->
            val activity = controller.get()
            val started = shadowOf(activity).nextStartedActivity

            assertEquals(CrashRecoveryActivity::class.java.name, started?.component?.className)
            assertTrue(activity.isFinishing)
            // The routing only forwards: the crash stays pending until the user picks a way forward,
            // so a launch that dies again before they choose still explains itself next time.
            assertTrue(CrashGuard.hasPendingCrash(context))
        }
    }
}
