package com.lumobrowser.app.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A report written by a dying process, rendered by a screen that runs no Compose, and pasted by hand
 * into a message. These tests pin both ends of that trip: the text that reaches the disk, and the
 * record that comes back from it.
 */
class CrashRecordTest {

    private companion object {
        const val TIME = 1_735_689_600_000L
        const val DEVICE =
            "app: LumoBrowser 1.07 (versionCode 9) built 2026-01-01 00:00\n" +
                "page size: 4096 bytes\n" +
                "webview: com.google.android.webview 120.0 (120)"
        const val TRACE = "\tat com.lumobrowser.app.MainActivity.onCreate(MainActivity.kt:105)"
    }

    private fun record(
        message: String? = "the device said no",
        deviceSummary: String = DEVICE,
        stackTrace: String = TRACE,
    ) = CrashRecord(
        occurredAtMillis = TIME,
        exceptionClass = "java.lang.IllegalStateException",
        message = message,
        threadName = "main",
        deviceSummary = deviceSummary,
        stackTrace = stackTrace,
    )

    @Test
    fun `a report reads as plain lines a person can act on`() {
        val text = record().toShareText()
        assertTrue(text.startsWith(CrashRecord.HEADER))
        assertTrue(text.contains("${CrashRecord.KEY_EXCEPTION}: java.lang.IllegalStateException"))
        assertTrue(text.contains("${CrashRecord.KEY_MESSAGE}: the device said no"))
        assertTrue(text.contains("${CrashRecord.KEY_THREAD}: main"))
        assertTrue(text.contains(CrashRecord.SECTION_DEVICE))
        assertTrue(text.contains("page size: 4096 bytes"))
        assertTrue(text.contains(CrashRecord.SECTION_STACK))
        assertTrue(text.contains("MainActivity.onCreate"))
    }

    @Test
    fun `every field survives being written and read back`() {
        val parsed = CrashRecord.parse(record().toShareText())
        assertNotNull(parsed)
        assertEquals(TIME, parsed!!.occurredAtMillis)
        assertEquals("java.lang.IllegalStateException", parsed.exceptionClass)
        assertEquals("the device said no", parsed.message)
        assertEquals("main", parsed.threadName)
        assertEquals(DEVICE, parsed.deviceSummary)
        assertEquals(TRACE, parsed.stackTrace)
    }

    @Test
    fun `text that was never a report is refused rather than guessed at`() {
        assertNull(CrashRecord.parse(""))
        assertNull(CrashRecord.parse("java.lang.RuntimeException: boom\n\tat foo(Bar.kt:1)"))
        assertNull(CrashRecord.parse("${CrashRecord.HEADER}\nnothing else here\n"))
        assertNull(CrashRecord.parse(record().toShareText().replace("exception: ", "xception: ")))
        assertNull(
            CrashRecord.parse(record().toShareText().replaceFirst("occurredAt: $TIME", "occurredAt: soon")),
        )
    }

    @Test
    fun `a missing message is stored as a placeholder and comes back as absent`() {
        val text = record(message = null).toShareText()
        assertTrue(text.contains("${CrashRecord.KEY_MESSAGE}: ${CrashRecord.NONE}"))
        assertNull(CrashRecord.parse(text)!!.message)
    }

    @Test
    fun `a message that spans lines cannot break the header`() {
        val parsed = CrashRecord.parse(record(message = "line one\nline two").toShareText())
        assertEquals("line one line two", parsed!!.message)
    }

    @Test
    fun `an exception with no frames still says which type it was`() {
        val error = IllegalStateException("no frames").apply { stackTrace = emptyArray() }
        assertTrue(CrashRecord.traceOf(error).contains("(no frames: java.lang.IllegalStateException)"))
    }

    @Test
    fun `a deep stack is capped and says how many frames were dropped`() {
        val frames = 200
        val error = IllegalStateException("deep").apply {
            stackTrace = Array(frames) { StackTraceElement("com.example.C$it", "m", "F.kt", it) }
        }
        val trace = CrashRecord.traceOf(error)
        assertTrue(trace.contains("\tat com.example.C0.m(F.kt:0)"))
        assertTrue(trace.contains("... ${frames - CrashRecord.MAX_FRAMES_PER_CAUSE} more frames"))
        assertEquals(CrashRecord.MAX_FRAMES_PER_CAUSE + 1, trace.lines().size)
    }

    @Test
    fun `a cause chain is kept so the real reason is not lost`() {
        val root = IllegalArgumentException("root cause").apply { stackTrace = emptyArray() }
        val wrapper = IllegalStateException("wrapper", root).apply { stackTrace = emptyArray() }
        val trace = CrashRecord.traceOf(wrapper)
        assertTrue(trace.contains("(no frames: java.lang.IllegalStateException)"))
        assertTrue(trace.contains("Caused by: java.lang.IllegalArgumentException: root cause"))
    }

    @Test
    fun `a bottomless cause chain stops at the cause cap`() {
        // The frames are stripped here on purpose: with real ones the 12_000-character cap can bite
        // before the cause cap does, which would make this test measure the wrong bound.
        var error: Throwable = IllegalStateException("level 0").apply { stackTrace = emptyArray() }
        repeat(CrashRecord.MAX_CAUSES * 2) { level ->
            error = IllegalStateException("level ${level + 1}", error).apply { stackTrace = emptyArray() }
        }
        val trace = CrashRecord.traceOf(error)
        assertTrue(trace.contains("... cause chain truncated at ${CrashRecord.MAX_CAUSES} levels"))
        // The top of the chain is not repeated in the trace: `toShareText` prints its class and its
        // message in the header, so the walk starts at level 12 with the frames and the deepest cause
        // it reports is the sixth counting from the top, i.e. level 7.
        assertFalse("the head belongs to the record header, not to the trace: $trace", trace.contains("level 12"))
        assertTrue(trace.contains("level 11"))
        assertTrue(trace.contains("level 7"))
        assertFalse("a cause past the cap must not be reported: $trace", trace.contains("level 6"))
    }

    @Test
    fun `an enormous trace is cut to the stored limit`() {
        val error = IllegalStateException("long").apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "com.example." + "X".repeat(CrashRecord.MAX_TRACE_CHARS + 1_000),
                    "m",
                    "F.kt",
                    1,
                ),
            )
        }
        val trace = CrashRecord.traceOf(error)
        assertTrue(trace.length <= CrashRecord.MAX_TRACE_CHARS + 64)
        assertTrue(trace.endsWith("... truncated at ${CrashRecord.MAX_TRACE_CHARS} characters"))
    }
}
