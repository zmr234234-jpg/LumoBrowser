package com.lumobrowser.app.crash

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One captured crash, in the shape both the recovery screen and the share sheet need.
 *
 * The record is *stored as the exact text* [toShareText] produces and read back by [parse], so the
 * file on disk, the text on screen and the text the user pastes into a message are one artefact
 * rather than three that can disagree. Nothing here is clever: a crash report that needs decoding is
 * a crash report nobody reads.
 *
 * Both ends are bounded. A wedged or recursive failure can produce a stack trace with thousands of
 * frames, and this text has to be written from a process that is already dying — where an allocation
 * failure is the one failure that cannot be reported. [traceOf] therefore caps frames per cause and
 * causes per record, and [parse] never throws on malformed input.
 */
data class CrashRecord(
    val occurredAtMillis: Long,
    val exceptionClass: String,
    val message: String?,
    val threadName: String,
    val deviceSummary: String,
    val stackTrace: String,
) {

    /** Wall-clock time in the device's own zone — the reader is the person holding the phone. */
    val occurredAtLabel: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(occurredAtMillis))

    /** The full report: what the recovery screen shows and what the share sheet sends. */
    fun toShareText(): String = buildString {
        appendLine(HEADER)
        appendLine("$KEY_TIME: $occurredAtMillis ($occurredAtLabel)")
        appendLine("$KEY_EXCEPTION: $exceptionClass")
        appendLine("$KEY_MESSAGE: ${message?.replace('\n', ' ') ?: NONE}")
        appendLine("$KEY_THREAD: $threadName")
        appendLine(SECTION_DEVICE)
        appendLine(deviceSummary.trimEnd())
        appendLine(SECTION_STACK)
        append(stackTrace.trimEnd())
        appendLine()
    }

    companion object {

        const val HEADER = "LumoBrowser crash report"
        const val SECTION_DEVICE = "---- device ----"
        const val SECTION_STACK = "---- stack trace ----"
        const val NONE = "(none)"

        const val KEY_TIME = "occurredAt"
        const val KEY_EXCEPTION = "exception"
        const val KEY_MESSAGE = "message"
        const val KEY_THREAD = "thread"

        /** Frames kept per throwable. Deep enough for a real cause, short enough to always write. */
        const val MAX_FRAMES_PER_CAUSE = 48

        /** Causes walked before the chain is declared uninteresting (a cycle or a pathological chain). */
        const val MAX_CAUSES = 6

        /** Hard cap on the stored trace, so a runaway trace cannot fill the app's data directory. */
        const val MAX_TRACE_CHARS = 12_000

        /**
         * Renders a throwable chain as plain lines.
         *
         * `Throwable.printStackTrace` writes to a stream, `Log.getStackTraceString` drags in
         * `android.util.Log` and a 4 KB truncation, and neither lets the caller bound the frame
         * count — so the chain is walked here instead. Every step is defensive: a cause that points
         * at itself would loop forever, which is exactly the kind of failure that must not turn a
         * crash report into a hang.
         */
        fun traceOf(error: Throwable): String {
            val text = StringBuilder()
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < MAX_CAUSES) {
                if (depth > 0) appendLine(text, "Caused by: ${current.javaClass.name}: ${current.message ?: NONE}")
                val frames = current.stackTrace
                if (frames.isEmpty()) {
                    appendLine(text, "\t(no frames: ${current.javaClass.name})")
                } else {
                    frames.take(MAX_FRAMES_PER_CAUSE).forEach { appendLine(text, "\tat $it") }
                    if (frames.size > MAX_FRAMES_PER_CAUSE) {
                        appendLine(text, "\t... ${frames.size - MAX_FRAMES_PER_CAUSE} more frames")
                    }
                }
                val cause = current.cause
                current = if (cause === current) null else cause
                depth++
            }
            if (current != null) appendLine(text, "... cause chain truncated at $MAX_CAUSES levels")
            return text.toString().trimEnd().let { bounded(it) }
        }

        /**
         * Reads back a report written by [toShareText].
         *
         * Returns `null` rather than throwing for anything it does not recognise: the callers are a
         * recovery screen that must render *something* and a test suite, and neither is helped by an
         * exception raised while explaining an exception.
         */
        fun parse(text: String): CrashRecord? {
            val lines = text.lines()
            if (lines.firstOrNull()?.trim() != HEADER) return null
            val deviceStart = lines.indexOf(SECTION_DEVICE)
            val stackStart = lines.indexOf(SECTION_STACK)
            if (deviceStart <= 0 || stackStart <= deviceStart) return null
            val header = lines.subList(1, deviceStart)
            fun value(key: String): String? = header
                .firstOrNull { it.startsWith("$key: ") }
                ?.substring(key.length + 2)
                ?.trim()
            val time = value(KEY_TIME)?.substringBefore(' ')?.toLongOrNull() ?: return null
            val exception = value(KEY_EXCEPTION)?.takeIf { it.isNotEmpty() } ?: return null
            val message = value(KEY_MESSAGE)?.takeIf { it != NONE && it.isNotEmpty() }
            return CrashRecord(
                occurredAtMillis = time,
                exceptionClass = exception,
                message = message,
                threadName = value(KEY_THREAD) ?: "unknown",
                // `trimEnd`, never `trim`: every frame line starts with a tab, and losing the first
                // one would make the text on screen differ from the text that was stored.
                deviceSummary = lines.subList(deviceStart + 1, stackStart).joinToString("\n").trimEnd(),
                stackTrace = lines.subList(stackStart + 1, lines.size).joinToString("\n").trimEnd(),
            )
        }

        private fun appendLine(text: StringBuilder, line: String) {
            text.append(line).append('\n')
        }

        private fun bounded(trace: String): String =
            if (trace.length <= MAX_TRACE_CHARS) {
                trace
            } else {
                trace.take(MAX_TRACE_CHARS) + "\n... truncated at $MAX_TRACE_CHARS characters"
            }
    }
}
