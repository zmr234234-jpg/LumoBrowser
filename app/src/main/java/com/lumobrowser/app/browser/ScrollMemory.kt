package com.lumobrowser.app.browser

/**
 * Remembers how far down the reader had scrolled on each address.
 *
 * Why this exists: a reload is not always the user's idea of "start over". AUTO mode escalates on a
 * page that drew nothing and re-requests it in the next mode, a mode or theme change re-applies the
 * plan and re-issues the address, and the toolbar's reload button is the same path again. Every one
 * of those tore the document down and rebuilt it at offset zero, so a reader halfway through a long
 * article lost their place to a reload they did not ask for. That is the "滚动位置保留" gap.
 *
 * Scope, stated honestly: the position is kept *per address* and only for as long as the screen that
 * owns this object lives. Following a link to an address that has no entry still starts at the top,
 * and closing the browser discards every entry — this is "a reload does not lose your place", not
 * "the app remembers where you were yesterday". Persisting it would mean writing a browsing-derived
 * value to disk, which the brief's "不默认上传收藏/浏览历史/设置" posture argues against keeping at
 * all, let alone persisting.
 *
 * Bounded: one browser session touches far more addresses than are worth holding, and the map lives
 * as long as the screen does, so it is capped and the least recently *used* entry is dropped first.
 * Reading counts as a use, which is what keeps a long article re-read after a detour while the
 * addresses merely passed through fall out.
 *
 * Pure Kotlin: no Android type appears here, so the eviction and "no entry is not zero" rules are
 * unit-testable without a device.
 */
class ScrollMemory(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    // accessOrder = true, so iteration order is least-recently-used first and eviction is by use
    // rather than by write. A plain HashMap would evict by insertion and could drop the article the
    // reader keeps coming back to while holding on to a page they bounced off in a second.
    private val offsets = LinkedHashMap<String, Int>(capacity, 0.75f, true)

    /**
     * Records where the reader is on [url].
     *
     * A blank address is ignored because `WebView.url` is null before the first commit and would
     * otherwise collect every pre-load scroll event under one empty key. A negative offset is
     * ignored because rubber-band overscroll reports one and it is not a position.
     */
    fun remember(url: String, offset: Int) {
        if (url.isBlank() || offset < 0) return
        offsets[url] = offset
        evictOverflow()
    }

    /**
     * The offset recorded for [url], or `null` when there is nothing to restore.
     *
     * `null` and `0` are deliberately different answers: "never seen this address" must not be
     * confused with "was already at the top", because only the first should skip a restore.
     */
    fun recall(url: String): Int? = if (url.isBlank()) null else offsets[url]

    /** Addresses currently held. Used by the tests to pin the bound. */
    val size: Int get() = offsets.size

    private fun evictOverflow() {
        var overflow = offsets.size - capacity
        if (overflow <= 0) return
        val oldest = offsets.keys.iterator()
        while (overflow > 0 && oldest.hasNext()) {
            oldest.next()
            oldest.remove()
            overflow--
        }
    }

    companion object {
        /**
         * Enough for a long reading session across several sites, small enough that the map is
         * never a measurable part of the browser screen's footprint.
         */
        const val DEFAULT_CAPACITY: Int = 24
    }
}
