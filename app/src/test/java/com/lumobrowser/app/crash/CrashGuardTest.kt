package com.lumobrowser.app.crash

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lumobrowser.app.BuildConfig
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The guard runs on a thread that is about to be killed, so it has to be boring: write, flag, hand
 * over. These tests cover the file, the flags and — most importantly — the chain, because a handler
 * that swallows the exception would leave a half-dead process and hide the very bug it recorded.
 *
 * A plain [Application] is used on purpose: this suite must not drag in the real one's DataStore,
 * collection scope and ad blocker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CrashGuardTest {

    private lateinit var context: Context
    private var savedHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * The default handler is global state and every test class in this JVM shares it, so it is saved
     * and restored rather than merely replaced — otherwise a handler installed here would outlive the
     * suite and swallow another class's failures.
     */
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        savedHandler = Thread.getDefaultUncaughtExceptionHandler()
        CrashGuard.clearCrash(context)
        CrashGuard.setSafeMode(context, false)
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(savedHandler)
        CrashGuard.clearCrash(context)
        CrashGuard.setSafeMode(context, false)
    }

    @Test
    fun `a recorded crash is on disk and marked pending`() {
        CrashGuard.record(context, IllegalStateException("the screen would not build"), "main")

        assertTrue(CrashGuard.hasPendingCrash(context))
        assertEquals(1, CrashGuard.crashCount(context))
        val read = CrashGuard.lastCrash(context)
        assertNotNull(read)
        assertEquals("java.lang.IllegalStateException", read!!.exceptionClass)
        assertEquals("the screen would not build", read.message)
        assertEquals("main", read.threadName)
        assertTrue(read.deviceSummary.contains("page size: "))
    }

    @Test
    fun `the newest crash is the one that is kept and the count keeps rising`() {
        CrashGuard.record(context, IllegalStateException("first"), "main")
        CrashGuard.record(context, NullPointerException("second"), "worker")

        assertEquals(2, CrashGuard.crashCount(context))
        assertEquals("java.lang.NullPointerException", CrashGuard.lastCrash(context)!!.exceptionClass)
        assertEquals("worker", CrashGuard.lastCrash(context)!!.threadName)
    }

    @Test
    fun `acknowledging a crash keeps the report for the next reader`() {
        CrashGuard.record(context, IllegalStateException("boom"), "main")

        CrashGuard.clearPendingCrash(context)

        assertFalse(CrashGuard.hasPendingCrash(context))
        assertNotNull(CrashGuard.lastCrash(context))
    }

    @Test
    fun `deleting the report forgets the file, the flag and the count`() {
        CrashGuard.record(context, IllegalStateException("boom"), "main")

        CrashGuard.clearCrash(context)

        assertFalse(CrashGuard.hasPendingCrash(context))
        assertNull(CrashGuard.lastCrash(context))
        assertEquals(0, CrashGuard.crashCount(context))
    }

    @Test
    fun `a crash with nothing to say is still recorded`() {
        val recorded = CrashGuard.record(context, RuntimeException(), threadName = "   ")

        assertNull(recorded.message)
        assertEquals("unknown", recorded.threadName)
        assertNotNull(CrashGuard.lastCrash(context))
    }

    @Test
    fun `a file that is not a report reads as absent instead of throwing`() {
        val file = File(File(context.filesDir, CrashGuard.DIR_NAME), CrashGuard.FILE_NAME)
        file.parentFile?.mkdirs()
        file.writeText("this is not a crash report")

        assertNull(CrashGuard.lastCrash(context))
    }

    @Test
    fun `the handler records the crash and then hands the process back to the platform`() {
        val seen = ArrayList<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> seen.add(error) }
        CrashGuard.install(context)

        val error = IllegalStateException("killed the app")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread("test-main"), error)

        // The previous handler still runs: this is what keeps the process dying the way Android
        // expects instead of lingering half alive.
        assertEquals(1, seen.size)
        assertSame(error, seen.first())
        assertEquals("java.lang.IllegalStateException", CrashGuard.lastCrash(context)!!.exceptionClass)
        assertEquals("killed the app", CrashGuard.lastCrash(context)!!.message)
        assertEquals("test-main", CrashGuard.lastCrash(context)!!.threadName)
        assertTrue(CrashGuard.hasPendingCrash(context))
    }

    @Test
    fun `installing twice leaves exactly one handler in the chain`() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        CrashGuard.install(context)
        val installed = Thread.getDefaultUncaughtExceptionHandler()

        CrashGuard.install(context)

        assertSame(installed, Thread.getDefaultUncaughtExceptionHandler())
    }

    @Test
    fun `a second install does not chain the first one onto itself`() {
        val seen = ArrayList<Throwable>()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> seen.add(error) }
        CrashGuard.install(context)
        CrashGuard.install(context)

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread("main"), IllegalStateException("once"))

        // Twice through the chain would mean the report is written twice and the platform handler is
        // called twice for one crash.
        assertEquals(1, seen.size)
    }

    @Test
    fun `a safe-mode request is answered once and only once`() {
        CrashGuard.setSafeMode(context, true)

        assertTrue(CrashGuard.consumeSafeMode(context))
        assertFalse(CrashGuard.consumeSafeMode(context))
    }

    @Test
    fun `cancelling safe mode means the next launch is ordinary`() {
        CrashGuard.setSafeMode(context, true)

        CrashGuard.setSafeMode(context, false)

        assertFalse(CrashGuard.consumeSafeMode(context))
    }

    @Test
    fun `reporting a crash does not consume the pending safe-mode request`() {
        CrashGuard.setSafeMode(context, true)

        CrashGuard.record(context, IllegalStateException("boom"), "main")

        // The report describes the session; it must not change what the next one does.
        assertTrue(CrashGuard.consumeSafeMode(context))
    }

    @Test
    fun `the device summary names the build, the page size and the WebView provider`() {
        val summary = CrashGuard.deviceSummary(context)

        assertTrue(summary.contains("app: LumoBrowser"))
        assertTrue(summary.contains(BuildConfig.BUILD_TIME))
        assertTrue(summary.contains("package: ${context.packageName}"))
        assertTrue(summary.contains("android: "))
        assertTrue(summary.contains("page size: "))
        assertTrue(summary.contains("webview: "))
        assertTrue(summary.contains("safe mode: "))
    }

    @Test
    fun `the recovery intent replaces the task instead of stacking on it`() {
        val intent = CrashGuard.recoveryIntent(context)

        assertEquals(CrashRecoveryActivity::class.java.name, intent.component?.className)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
    }
}
