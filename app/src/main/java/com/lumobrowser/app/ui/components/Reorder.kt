package com.lumobrowser.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * Drag-to-reorder support for the bookmark grid.
 *
 * Deliberately no third-party reorderable-list dependency: the reorder decision is a nearest-centre
 * test against [LazyGridState.layoutInfo], so nothing extra is allocated per item and the grid can
 * stay a plain [androidx.compose.foundation.lazy.grid.LazyVerticalGrid].
 *
 * The order the user sees is never mutated mid-gesture. The dragged card is translated by
 * [dragOffset] and the slots it passes over are recomputed from the original list plus
 * [previewIndex]; the real order is emitted once, on release. That keeps the render list stable and
 * makes the "which slot am I over" arithmetic exact.
 *
 * Gesture contract:
 *  - long press picks the card up (a plain tap still opens the bookmark, a plain drag still
 *    scrolls the list);
 *  - dragging near the top or bottom edge auto-scrolls, so long collections stay reachable;
 *  - release calls [onMove] with the new key order, which the caller persists.
 */
class ReorderState internal constructor(
    private val listState: LazyGridState,
    private val scope: CoroutineScope,
) {
    /** Key of the card currently held, or `null` when idle. */
    var draggingKey by mutableStateOf<Any?>(null)
        internal set

    /** Finger movement since the drag began, in pixels. */
    var dragOffset by mutableStateOf(Offset.Zero)
        internal set

    /** Measured size of the held card. */
    var draggedSize by mutableStateOf(IntSize.Zero)
        internal set

    /** Slot the held card would occupy if released now; `null` when idle. */
    var previewIndex by mutableStateOf<Int?>(null)
        internal set

    /** Keys captured at drag start; the gesture never reads the live list again. */
    private var keys: List<Any> = emptyList()

    private var scrollJob: kotlinx.coroutines.Job? = null

    internal val isDragging: Boolean get() = draggingKey != null

    internal fun start(key: Any, index: Int, size: IntSize, snapshot: List<Any>) {
        keys = snapshot
        draggingKey = key
        previewIndex = index
        dragOffset = Offset.Zero
        draggedSize = size
    }

    /** Applies finger movement and recomputes the preview slot. */
    internal fun moveBy(delta: Offset, columns: Int) {
        dragOffset += delta
        previewIndex = targetIndex(columns)
    }

    /**
     * Slot the held card currently overlaps.
     *
     * Uses the *rendered* offset of the held card (from the lazy layout) plus the finger delta, so
     * it stays correct while the list itself scrolls underneath the gesture.
     */
    private fun targetIndex(columns: Int): Int? {
        val key = draggingKey ?: return null
        val from = keys.indexOf(key)
        if (from < 0) return null

        val layout = listState.layoutInfo
        val items = layout.visibleItemsInfo
        if (items.isEmpty() || columns <= 0) return null

        val heldInfo = items.firstOrNull { it.key == key }
        val cellWidth = heldInfo?.size?.width ?: draggedSize.width
        val cellHeight = heldInfo?.size?.height ?: draggedSize.height
        if (cellWidth == 0 || cellHeight == 0) return null

        // Cell distance implied by the finger movement, measured against the actual cell pitch so
        // a partially dragged card still snaps to the right slot.
        val columnDelta = if (cellWidth > 0) Math.round(dragOffset.x / cellWidth) else 0
        val rowDelta = if (cellHeight > 0) Math.round(dragOffset.y / cellHeight) else 0

        val target = from + rowDelta * columns + columnDelta
        return target.coerceIn(0, max(0, keys.size - 1))
    }

    /** Feeds an auto-scroll step to the scroll loop. */
    internal fun requestScroll(deltaPx: Float) {
        if (deltaPx == 0f) {
            scrollJob?.cancel()
            scrollJob = null
            return
        }
        if (scrollJob?.isActive == true) return
        scrollJob = scope.launch {
            // A short burst rather than an unbounded loop: it re-arms on the next pointer event,
            // which naturally stops the scroll when the finger stops moving or lifts.
            repeat(6) {
                if (!isDragging) return@launch
                listState.scrollBy(deltaPx)
                kotlinx.coroutines.delay(16)
            }
        }
    }

    /** Ends the gesture and returns the resulting key order, or `null` when nothing was dragged. */
    internal fun finish(): List<Any>? {
        val key = draggingKey
        val from = if (key == null) -1 else keys.indexOf(key)
        val to = previewIndex

        draggingKey = null
        dragOffset = Offset.Zero
        draggedSize = IntSize.Zero
        previewIndex = null
        scrollJob?.cancel()
        scrollJob = null

        if (key == null || from < 0 || to == null || to == from || keys.isEmpty()) return null

        val next = keys.toMutableList()
        next.removeAt(from)
        next.add(to.coerceIn(0, next.size), key)
        keys = emptyList()
        return next
    }

    internal fun cancel() {
        draggingKey = null
        dragOffset = Offset.Zero
        draggedSize = IntSize.Zero
        previewIndex = null
        keys = emptyList()
        scrollJob?.cancel()
        scrollJob = null
    }

    /** Auto-scroll speed for a finger position inside the viewport. */
    internal fun autoScrollDelta(pointerY: Float, viewportHeight: Int, edge: Float = 110f): Float {
        if (viewportHeight <= 0) return 0f
        val band = edge.coerceAtMost(viewportHeight / 3f)
        val raw = when {
            pointerY < band -> -((band - pointerY) / band) * MAX_STEP
            pointerY > viewportHeight - band -> ((pointerY - (viewportHeight - band)) / band) * MAX_STEP
            else -> 0f
        }
        // Clamped: once the finger leaves the viewport entirely the ramp above keeps growing, and
        // the grid would run away from the thumb.
        return raw.coerceIn(-MAX_STEP, MAX_STEP)
    }

    /**
     * Auto-scroll from a pointer position that is local to the dragged item.
     *
     * The gesture modifier lives on a grid item, so `change.position.y` is item-local and the item
     * is one card tall — not a viewport. Handing those straight to [autoScrollDelta] made a band of
     * 110px inside a ~330px card, i.e. a card's own top third counted as "the finger reached the
     * screen edge" and the grid scrolled away while the finger was still mid-screen. Translating
     * through the item's viewport offset first is what puts the edge bands at the real edges.
     */
    internal fun requestAutoScroll(key: Any, itemLocalY: Float) {
        val info = listState.layoutInfo
        val itemTop = info.visibleItemsInfo.firstOrNull { it.key == key }?.offset?.y ?: 0
        requestScroll(autoScrollDelta(itemTop + itemLocalY, info.viewportSize.height))
    }

    companion object {
        /** Pixels requested per auto-scroll burst; ~10px per frame is brisk but controllable. */
        const val MAX_STEP = 26f
    }
}

@Composable
fun rememberReorderState(listState: LazyGridState): ReorderState {
    val scope = rememberCoroutineScope()
    return remember(listState, scope) { ReorderState(listState, scope) }
}


/**
 * Attaches long-press drag handling to one grid item.
 *
 * @param key stable identity (the bookmark id).
 * @param index this item's index in the list being rendered.
 * @param columns number of columns in the grid, needed to translate finger rows into slot shifts.
 * @param keys snapshot provider; read exactly once per gesture so a database emission arriving
 *        mid-drag cannot corrupt the in-flight reorder.
 * @param onMove called on release with the resulting key order.
 */
fun Modifier.reorderable(
    state: ReorderState,
    key: Any,
    index: Int,
    columns: Int,
    keys: () -> List<Any>,
    onMove: (List<Any>) -> Unit,
): Modifier = pointerInput(key, index, columns) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        val snapshot = keys()
        if (snapshot.indexOf(key) < 0) return@awaitEachGesture

        state.start(key, index, IntSize(size.width, size.height), snapshot)
        longPress.consume()
        try {
            drag(longPress.id) { change ->
                state.moveBy(change.positionChange(), columns)
                state.requestAutoScroll(key, change.position.y)
                change.consume()
            }
        } finally {
            val order = state.finish()
            if (order != null) onMove(order)
        }
    }
}

/** Whether [a] and [b] are within [tolerance] pixels — used by the drag preview to pick a slot. */
internal fun near(a: Float, b: Float, tolerance: Float): Boolean = abs(a - b) <= tolerance
