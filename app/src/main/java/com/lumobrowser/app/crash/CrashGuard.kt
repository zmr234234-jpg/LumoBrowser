package com.lumobrowser.app.crash

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import android.webkit.WebView
import com.lumobrowser.app.BuildConfig
import java.io.File
import java.util.Locale

/**
 * Makes a crash impossible to lose.
 *
 * The failure this exists for is the one this project actually hit: an APK that installs, flashes,
 * and returns to the launcher, on a phone whose owner cannot easily produce a `logcat`. Nothing in
 * the app can explain a crash the user cannot describe, so the app records its own death, states it
 * plainly on the next launch, and offers a way in that turns the ambient machinery off.
 *
 * Three deliberate choices:
 *
 * - **The previous handler is always called.** This installs itself in front of the platform's
 *   handler and delegates when it is done, so the process still dies the way Android expects. A
 *   crash handler that swallows the exception leaves a half-dead process and lies to the user.
 * - **`SharedPreferences` with `commit()`, not DataStore.** The write happens on a thread that is
 *   about to be killed. `commit()` is synchronous and returns when the bytes are on disk; DataStore
 *   is a coroutine API with a write queue, which is the wrong shape for a process that has no time
 *   left. This is also why [crashFile] is written with a plain `File.writeText`.
 * - **Everything is bounded and wrapped.** A crash handler that throws replaces the user's bug
 *   report with a worse one, so every platform call here goes through [guarded].
 */
object CrashGuard {

    private const val TAG = "LumoCrash"

    /** Directory under `filesDir` that holds the report. */
    const val DIR_NAME = "crash"

    /** The report itself; one file, newest crash wins. */
    const val FILE_NAME = "last-crash.txt"

    private const val PREFS_NAME = "lumo_crash_guard"
    private const val KEY_PENDING = "pending_crash"
    private const val KEY_SAFE_MODE = "safe_mode"
    private const val KEY_CRASHES = "crash_count"

    @Volatile
    private var handler: Thread.UncaughtExceptionHandler? = null

    /**
     * Installs the handler. Idempotent, and cheap enough to call from `Application.onCreate` before
     * anything else — which is exactly where it belongs, because the crashes worth catching are the
     * ones that happen while the app is still starting up.
     */
    @Synchronized
    fun install(context: Context) {
        val app = context.applicationContext
        val current = Thread.getDefaultUncaughtExceptionHandler()
        // Identity rather than a boolean flag: if anything else has taken the slot since the last
        // call, this chains onto that instead of refusing to install at all.
        if (current != null && current === handler) return
        val previous = current
        val chained = Thread.UncaughtExceptionHandler { thread, error ->
            // Recorded first, on this thread, while the process can still write: the report is the
            // whole point, and a failure to show the recovery screen must not cost us the report.
            runCatching { record(app, error, thread.name.orEmpty()) }
                .onFailure { Log.w(TAG, "could not write the crash report", it) }
            runCatching { startRecovery(app) }
            // Hand the process back to the platform. This is what makes the app terminate normally
            // instead of lingering in an undefined state.
            previous?.uncaughtException(thread, error)
        }
        handler = chained
        Thread.setDefaultUncaughtExceptionHandler(chained)
    }

    /** Writes [error] to disk and marks a crash as pending. Returns what was written. */
    fun record(context: Context, error: Throwable, threadName: String): CrashRecord {
        val app = context.applicationContext
        val record = CrashRecord(
            occurredAtMillis = System.currentTimeMillis(),
            exceptionClass = error.javaClass.name,
            message = error.message,
            threadName = threadName.ifBlank { "unknown" },
            deviceSummary = deviceSummary(app),
            stackTrace = CrashRecord.traceOf(error),
        )
        runCatching { Log.e(TAG, "${record.exceptionClass}: ${record.message}", error) }
        runCatching {
            val file = crashFile(app)
            file.parentFile?.mkdirs()
            file.writeText(record.toShareText())
        }
        runCatching {
            preferences(app).edit()
                .putBoolean(KEY_PENDING, true)
                .putInt(KEY_CRASHES, crashCount(app) + 1)
                .commit()
        }
        return record
    }

    /** The most recent report, or `null` when there is none or it cannot be read. */
    fun lastCrash(context: Context): CrashRecord? = runCatching {
        val file = crashFile(context.applicationContext)
        if (!file.isFile) return@runCatching null
        CrashRecord.parse(file.readText())
    }.getOrNull()

    /** Whether a crash has been recorded since the report was last acknowledged. */
    fun hasPendingCrash(context: Context): Boolean = runCatching {
        preferences(context.applicationContext).getBoolean(KEY_PENDING, false)
    }.getOrDefault(false)

    /** Marks the report as seen. Called when the user picks a way forward from the recovery screen. */
    fun clearPendingCrash(context: Context) {
        runCatching { preferences(context.applicationContext).edit().putBoolean(KEY_PENDING, false).commit() }
    }

    /**
     * Forgets the report **and the running count**.
     *
     * The count is not history for its own sake: the recovery screen prints "crashes recorded on
     * this install", so leaving a stale number behind after "delete report" would make the screen
     * state something that is no longer true.
     */
    fun clearCrash(context: Context) {
        val app = context.applicationContext
        runCatching { crashFile(app).delete() }
        runCatching {
            preferences(app).edit().putBoolean(KEY_PENDING, false).remove(KEY_CRASHES).commit()
        }
    }

    /** How many crashes this install has recorded, ever. Shown on the recovery screen. */
    fun crashCount(context: Context): Int = runCatching {
        preferences(context.applicationContext).getInt(KEY_CRASHES, 0)
    }.getOrDefault(0)

    /**
     * Reads the safe-mode request and clears it.
     *
     * Read **once per process** — [com.lumobrowser.app.LumoApplication] holds the answer — because a
     * configuration change or a language switch recreates the Activity, and safe mode has to survive
     * that or the effects it switched off would come back mid-session.
     */
    fun consumeSafeMode(context: Context): Boolean {
        val app = context.applicationContext
        val requested = runCatching { preferences(app).getBoolean(KEY_SAFE_MODE, false) }.getOrDefault(false)
        if (requested) runCatching { preferences(app).edit().putBoolean(KEY_SAFE_MODE, false).commit() }
        return requested
    }

    /** Queues (or cancels) a safe-mode launch. */
    fun setSafeMode(context: Context, enabled: Boolean) {
        runCatching { preferences(context.applicationContext).edit().putBoolean(KEY_SAFE_MODE, enabled).commit() }
    }

    /** The one place the recovery screen's intent is built. */
    fun recoveryIntent(context: Context): Intent = Intent(context, CrashRecoveryActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    /**
     * What this run is running on, as plain lines.
     *
     * This is the part of the report the app cannot know in advance and the user cannot type: the
     * exact build, the OS build, the ABI set, the **memory page size** (an Android 15 device can run
     * 16 KB pages, which a native library compiled for 4 KB will not survive) and the **WebView
     * provider** — a browser with no usable WebView has a very specific kind of bad day. Every line
     * is best-effort: `unavailable` is a valid answer, and one that is itself evidence.
     */
    fun deviceSummary(context: Context): String = buildString {
        appendLine("app: LumoBrowser ${versionName(context)} (versionCode ${versionCode(context)}) built ${guarded { BuildConfig.BUILD_TIME }}")
        appendLine("package: ${context.packageName}, debuggable ${guarded { isDebuggable(context).toString() }}")
        appendLine("android: ${guarded { Build.VERSION.RELEASE }} (API ${guarded { Build.VERSION.SDK_INT.toString() }}), build ${guarded { Build.VERSION.INCREMENTAL }}")
        appendLine("device: ${guarded { Build.MANUFACTURER }} ${guarded { Build.MODEL }} (${guarded { Build.DEVICE }})")
        appendLine("abis: ${guarded { Build.SUPPORTED_ABIS.joinToString("/") }}")
        appendLine("page size: ${pageSizeLabel()}")
        appendLine("webview: ${webkitLabel()}")
        appendLine("locale: ${guarded { Locale.getDefault().toString() }}")
        appendLine("safe mode: ${guarded { consumeSafeModeReadOnly(context).toString() }}")
    }.trimEnd()

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun crashFile(context: Context): File =
        File(File(context.filesDir, DIR_NAME), FILE_NAME)

    private fun startRecovery(app: Context) {
        // Best effort by design: if the platform refuses the start (a background-activity restriction,
        // most likely) the pending flag still makes the next launch explain itself.
        app.startActivity(recoveryIntent(app))
    }

    /**
     * Reads the page size the kernel actually uses.
     *
     * Reported as a number rather than a "yes/no" because the interesting case is a device that is
     * neither 4096 nor 16384, and because the number is checkable by hand.
     */
    private fun pageSizeLabel(): String = guarded {
        val bytes = Os.sysconf(OsConstants._SC_PAGESIZE)
        when {
            bytes >= 16_384 -> "$bytes bytes (16 KB pages)"
            bytes > 0 -> "$bytes bytes"
            else -> "unknown"
        }
    }

    private fun webkitLabel(): String = guarded {
        val info = WebView.getCurrentWebViewPackage()
        if (info == null) {
            "unavailable (no WebView provider is installed)"
        } else {
            "${info.packageName} ${info.versionName} (${info.longVersionLabel()})"
        }
    }

    private fun versionName(context: Context): String = guarded {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }

    private fun versionCode(context: Context): String = guarded {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionLabel()
    }

    /**
     * `PackageInfo.versionCode` is deprecated from API 28 on, and its replacement only exists there,
     * so both spellings are kept to keep the summary meaning the same thing on every supported
     * release.
     */
    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionLabel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode.toString()
        } else {
            versionCode.toString()
        }

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Safe-mode state for the report, without consuming it: [deviceSummary] runs inside [record], and
     * reporting must not change the next launch's behaviour.
     */
    private fun consumeSafeModeReadOnly(context: Context): Boolean =
        preferences(context.applicationContext).getBoolean(KEY_SAFE_MODE, false)

    private inline fun guarded(block: () -> String): String =
        runCatching(block).getOrDefault("unavailable")
}
