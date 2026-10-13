package com.lumobrowser.app.util

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * Small, deterministic date/colour helpers used across screens.
 * Kept pure so they are directly unit-testable on the JVM.
 */
object LumoFormat {

    /** "3 minutes ago" style relative label, localised through the provided pattern. */
    fun relativeTime(
        timestamp: Long,
        now: Long = System.currentTimeMillis(),
        justNow: String = "just now",
        minutesAgo: (Long) -> String = { "$it min ago" },
        hoursAgo: (Long) -> String = { "$it h ago" },
        daysAgo: (Long) -> String = { "$it d ago" },
    ): String {
        val delta = (now - timestamp).coerceAtLeast(0L)
        val minutes = delta / 60_000L
        val hours = delta / 3_600_000L
        val days = delta / 86_400_000L
        return when {
            minutes < 1L -> justNow
            minutes < 60L -> minutesAgo(minutes)
            hours < 24L -> hoursAgo(hours)
            else -> daysAgo(days)
        }
    }

    /** Truncates a URL for display without breaking the host. */
    fun shortenUrl(url: String, max: Int = 48): String {
        // Judge first, then cut. Stripping the scheme up front would make a 60-char URL look short
        // and return it untouched — longer than the caller asked for.
        if (max <= 1) return "…"
        return if (url.length <= max) url else url.take(max - 1) + "…"
    }

    /** Blends two colors in linear-ish sRGB space; used for gradient placeholders. */
    fun blend(from: Color, to: Color, fraction: Float): Color {
        val f = fraction.coerceIn(0f, 1f)
        return Color(
            red = from.red + (to.red - from.red) * f,
            green = from.green + (to.green - from.green) * f,
            blue = from.blue + (to.blue - from.blue) * f,
            alpha = from.alpha + (to.alpha - from.alpha) * f,
        )
    }

    /**
     * Deterministic 32-bit hash (FNV-1a) used to pick a stable placeholder tint per domain,
     * so a card's fallback avatar never changes between launches.
     */
    fun stableHash(value: String): Int {
        var hash = 0x811C9DC5.toInt()
        for (char in value) {
            hash = hash xor char.code
            hash *= 0x01000193
        }
        return hash
    }

    /** Two uppercase initials for the favicon placeholder. */
    fun initials(title: String, domain: String): String {
        // Word-based, not letter-based: "Google Books" -> GB, but a single word yields one letter
        // ("News" -> N) so a one-word title never reads as a two-letter acronym.
        val words = title.trim().split(' ', '.', '-', '_', '/', ',').filter { it.isNotBlank() }
        // A domain fallback is dot-separated, and the registrable part is the leading one.
        val parts = words.ifEmpty { domain.trim().split('.').filter { it.isNotBlank() } }
        return when {
            parts.isEmpty() -> "?"
            parts.size == 1 -> parts[0].take(1).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }

    /** Clamps and quantises a float slider value to whole percent, for stable persistence. */
    fun percent(value: Float): Int = (value.coerceIn(0f, 1f) * 100f).roundToInt()

    fun denormalizePercent(value: Int): Float = value.coerceIn(0, 100) / 100f
}
