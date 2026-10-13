package com.lumobrowser.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumobrowser.app.data.Bookmark
import com.lumobrowser.app.data.CategoryRailPlacement
import com.lumobrowser.app.data.LayoutMode
import com.lumobrowser.app.data.UNCATEGORISED
import com.lumobrowser.app.data.UserPreferences
import com.lumobrowser.app.feedback.LocalLumoFeedback
import com.lumobrowser.app.ui.components.LumoBackground
import com.lumobrowser.app.ui.components.LumoChip
import com.lumobrowser.app.ui.components.LumoIconButton
import com.lumobrowser.app.ui.components.LumoMenuItem
import com.lumobrowser.app.ui.components.LumoMinTouchTarget
import com.lumobrowser.app.ui.components.LumoSectionHeader
import com.lumobrowser.app.ui.components.LocalLumoOnElevatedSurface
import com.lumobrowser.app.ui.components.WeightedSpacer
import com.lumobrowser.app.ui.components.lumoPress
import com.lumobrowser.app.ui.components.lumoRipple
import com.lumobrowser.app.ui.components.lumoScreenInsets
import com.lumobrowser.app.ui.components.lumoSurface
import com.lumobrowser.app.ui.components.ReorderState
import com.lumobrowser.app.ui.components.rememberReorderState
import com.lumobrowser.app.ui.components.reorderable
import com.lumobrowser.app.ui.components.surfaceTreatment
import com.lumobrowser.app.ui.theme.LumoSpacing
import com.lumobrowser.app.ui.theme.LumoTheme
import com.lumobrowser.app.util.LumoFormat
import com.lumobrowser.app.util.ShareUtil
import kotlin.math.absoluteValue

/**
 * The bookmark library: a full-width header, then a content area split into a category strip and a
 * card list.
 *
 * The split is the whole point of the shape below, and it is the fix for the reported defect: the
 * header (title, count, overflow, add) and the search field are laid out once, full width, above
 * everything else, and only the area *below* them is divided into a strip and a grid. In 1.09's
 * first attempt the entire page was one horizontal pair, so choosing the left strip pushed the
 * title, the count and the search field into the right-hand column as well: the title wrapped onto
 * two lines and the cards lost a quarter of their width. Here that cannot happen, because the strip
 * is created inside a branch that the header sits outside of.
 *
 * `gridState` and `reorderState` are created above the branch and therefore survive a switch: the
 * list keeps its scroll position, and a flip never scrolls the library back to the top.
 */
@Composable
fun HomeScreen(
    preferences: UserPreferences,
    bookmarks: List<Bookmark>,
    categories: List<String>,
    totalCount: Int,
    query: String,
    selectedCategory: String?,
    onQueryChange: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onOpen: (Bookmark) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Bookmark) -> Unit,
    onTogglePin: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onReorder: (List<Bookmark>) -> Unit,
    onOpenSettings: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onToggleCrossSiteExternal: () -> Unit,
    onDismissDragHint: () -> Unit,
) {
    val s = LocalLumoStrings.current
    val theme = LumoTheme.state
    val context = LocalContext.current
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderState(gridState)

    val isList = preferences.layoutMode == LayoutMode.List
    val columns = if (isList) 1 else 2
    val keys = remember(bookmarks) { bookmarks.map { it.id } }

    // Cards play their entry animation once per appearance of a card set, NOT once per composition.
    // A LazyVerticalGrid disposes the items that scroll out of view, so keying the animation on
    // `remember(bookmark.id)` alone made every card replay its fade-and-lift whenever it scrolled
    // back into view: constant motion on a long list, and taps landing on a card that was still
    // invisible. Re-arming on the category filter keeps the transition when the user switches sets.
    val entryTracker = remember(selectedCategory) { EntryAnimationTracker() }

    // Hoisted out of the item lambda: recomputing these for every card turned a drag into O(n) work
    // on every frame of the gesture.
    val draggingKey: Long? = reorderState.draggingKey as Long?
    val previewIndex = reorderState.previewIndex
    val draggingFrom = if (previewIndex == null || draggingKey == null) {
        -1
    } else {
        bookmarks.indexOfFirst { it.id == draggingKey }
    }

    LumoBackground {
        // The backdrop is deliberately full-bleed (it is decoration and should reach the top edge),
        // while the content is inset once, here. Before 1.02 this Box had no inset at all, so the
        // title and the add/settings buttons were painted underneath the status bar: the clock and
        // battery icons sat on top of them and the header looked broken.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag(TestTags.HOME_ROOT)
                .lumoScreenInsets()
                .padding(horizontal = LumoSpacing.screenHorizontal),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Full width, drawn ONCE, outside the placement branch below. The strip cannot
                // reach these, which is the difference between this structure and the first
                // attempt's.
                HomeContentHeader(
                    s = s,
                    totalCount = totalCount,
                    query = query,
                    onQueryChange = onQueryChange,
                    onAdd = onAdd,
                    onOpenSettings = onOpenSettings,
                    onExport = onExport,
                    onImport = onImport,
                    crossSiteExternal = preferences.browserCrossSiteExternal,
                    onToggleCrossSiteExternal = { onToggleCrossSiteExternal() },
                )

                // Only the area below the header and the search field is divided. The two placements
                // are the same content with the strip in a different place, so the shape of the swap
                // is one decision, spelled once, here.
                AnimatedContent(
                    targetState = preferences.categoryRailPlacement == CategoryRailPlacement.Left,
                    transitionSpec = {
                        // Horizontal fade-through: the strip travels left-to-right and the grid
                        // crossfades underneath it. Deliberately not a size or offset animation --
                        // animating a LazyVerticalGrid's size re-measures every visible card on every
                        // frame, which is the shape of "the list stutters while I flip the switch".
                        //
                        // `navDurationMs` is the same field the shared `transitionSpec` uses, so the
                        // placement switch and the rest of the UI share one budget (120-300 ms per
                        // preset; locked by
                        // MotionAndThemeTest.uiTransitionBudgetStaysInTheFixedBand).
                        val duration = theme.motion.duration(theme.motion.navDurationMs)
                        // Two specs because the two halves animate different things: a fade is
                        // `FiniteAnimationSpec<Float>` and a horizontal expand/shrink is
                        // `FiniteAnimationSpec<IntSize>`, so one shared spec cannot typecheck for
                        // both.
                        val fade = tween<Float>(durationMillis = duration)
                        val slide = tween<IntSize>(durationMillis = duration)
                        (fadeIn(fade) + expandHorizontally(slide, clip = false))
                            .togetherWith(fadeOut(fade) + shrinkHorizontally(slide, clip = false))
                    },
                    label = "category-rail-placement",
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) { railOnTheLeft ->
                    if (railOnTheLeft) {
                        // The strip is a sibling of the list, not an overlay: two independent scroll
                        // containers side by side, so a drag on one can never reach the other and
                        // the two scroll positions are independent by construction.
                        //
                        // The width is a fixed, narrow band rather than a fraction of the window.
                        // The first attempt used 26% of the available width, which on a narrow
                        // phone is wide enough to read as a second content column and to crowd the
                        // cards.
                        Row(modifier = Modifier.fillMaxSize()) {
                            CategoryRail(
                                categories = categories,
                                selected = selectedCategory,
                                allLabel = s.categoryAll,
                                uncategorisedLabel = s.categoryUncategorised,
                                onSelect = onCategorySelected,
                                // Without an explicit width a `LazyColumn` in a `Row` takes every
                                // pixel it is offered, the grid is left with none, and the bookmark
                                // page renders as a black screen with one full-width category entry.
                                width = LumoSpacing.categoryRailWidth,
                            )
                            Spacer(Modifier.width(LumoSpacing.cardGap))
                            BookmarkGrid(
                                columns = columns,
                                state = gridState,
                                contentPadding = PaddingValues(top = 6.dp, bottom = 120.dp),
                                modifier = Modifier.fillMaxHeight().weight(1f),
                                content = {
                                    // No header and no category row here: in this placement the
                                    // strip is the rail beside the grid and the header is the
                                    // full-width bar above the whole branch. Registering either one
                                    // here would draw a second copy of a control that is already on
                                    // screen.
                                    bookContentItems(
                                        s = s,
                                        context = context,
                                        preferences = preferences,
                                        bookmarks = bookmarks,
                                        selectedCategory = selectedCategory,
                                        query = query,
                                        isList = isList,
                                        draggingKey = draggingKey,
                                        previewIndex = previewIndex,
                                        draggingFrom = draggingFrom,
                                        entryTracker = entryTracker,
                                        keys = keys,
                                        reorderState = reorderState,
                                        columns = columns,
                                        onAdd = onAdd,
                                        onOpen = onOpen,
                                        onEdit = onEdit,
                                        onTogglePin = onTogglePin,
                                        onDelete = onDelete,
                                        onReorder = onReorder,
                                        onDismissDragHint = onDismissDragHint,
                                    )
                                },
                            )
                        }
                    } else {
                        // The top placement keeps the strip as the first row of the content area,
                        // immediately under the search field: full width, horizontal, scrollable
                        // sideways when there are more categories than fit. This is the 1.08
                        // arrangement, and it is what the switch returns to.
                        Column(modifier = Modifier.fillMaxSize()) {
                            CategoryRow(
                                categories = categories,
                                selected = selectedCategory,
                                allLabel = s.categoryAll,
                                uncategorisedLabel = s.categoryUncategorised,
                                onSelect = onCategorySelected,
                            )
                            BookmarkGrid(
                                columns = columns,
                                state = gridState,
                                contentPadding = PaddingValues(top = 6.dp, bottom = 120.dp),
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                content = {
                                    bookContentItems(
                                        s = s,
                                        context = context,
                                        preferences = preferences,
                                        bookmarks = bookmarks,
                                        selectedCategory = selectedCategory,
                                        query = query,
                                        isList = isList,
                                        draggingKey = draggingKey,
                                        previewIndex = previewIndex,
                                        draggingFrom = draggingFrom,
                                        entryTracker = entryTracker,
                                        keys = keys,
                                        reorderState = reorderState,
                                        columns = columns,
                                        onAdd = onAdd,
                                        onOpen = onOpen,
                                        onEdit = onEdit,
                                        onTogglePin = onTogglePin,
                                        onDelete = onDelete,
                                        onReorder = onReorder,
                                        onDismissDragHint = onDismissDragHint,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeContentHeader(
    s: LumoStringsSnapshot,
    totalCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    crossSiteExternal: Boolean,
    onToggleCrossSiteExternal: () -> Unit,
) {
    HomeHeader(
        title = s.homeTitle,
        countLabel = s.bookmarkCount(totalCount),
        query = query,
        onQueryChange = onQueryChange,
        onAdd = onAdd,
        onOpenSettings = onOpenSettings,
        clearSearchLabel = s.clearSearch,
        searchHint = s.searchHint,
        addLabel = s.addBookmark,
        settingsLabel = s.settings,
        onExport = onExport,
        onImport = onImport,
        exportLabel = s.exportBackup,
        importLabel = s.importBackup,
        crossSiteLabel = s.crossSiteExternal,
        crossSiteHint = s.crossSiteExternalHint,
        crossSiteExternal = crossSiteExternal,
        onToggleCrossSiteExternal = { onToggleCrossSiteExternal() },
    )
}

/**
 * The grid itself, in whichever column count the current mode asks for.
 *
 * Extracted in 1.09 so the two placements can share one grid definition: the left-rail layout and
 * the top-row layout differ only in where the strip lives, and duplicating the grid for them would
 * mean two places to keep in step. `state` is passed in rather than created here for the same
 * reason 闂?the caller owns it so it survives a placement switch.
 */
@Composable
private fun BookmarkGrid(
    columns: Int,
    state: LazyGridState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns = if (columns == 1) GridCells.Fixed(1) else GridCells.Fixed(2),
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(LumoSpacing.cardGap),
        verticalArrangement = Arrangement.spacedBy(LumoSpacing.cardGap),
        modifier = modifier,
        content = content,
    )
}

/** The header, as an item of the grid that follows it. */

private fun LazyGridScope.headerItem(
    s: LumoStringsSnapshot,
    totalCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    crossSiteExternal: Boolean,
    onToggleCrossSiteExternal: () -> Unit,
) {
    item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
        HomeHeader(
            title = s.homeTitle,
            countLabel = s.bookmarkCount(totalCount),
            query = query,
            onQueryChange = onQueryChange,
            onAdd = onAdd,
            onOpenSettings = onOpenSettings,
            clearSearchLabel = s.clearSearch,
            searchHint = s.searchHint,
            addLabel = s.addBookmark,
            settingsLabel = s.settings,
            onExport = onExport,
            onImport = onImport,
            exportLabel = s.exportBackup,
            importLabel = s.importBackup,
            crossSiteLabel = s.crossSiteExternal,
            crossSiteHint = s.crossSiteExternalHint,
            crossSiteExternal = crossSiteExternal,
            onToggleCrossSiteExternal = { onToggleCrossSiteExternal() },
        )
    }
}

/**
 * Everything under the header: the hint, the empty state, and the cards.
 *
 * A `LazyGridScope` extension rather than a composable so the two placements can register the same
 * items on their own grid: the category strip is the only piece that moves, and it is registered by
 * the caller (top row) or drawn beside the grid (left rail) 闂?never both, so a category control
 * can never be duplicated on screen.
 */
private fun LazyGridScope.bookContentItems(
    s: LumoStringsSnapshot,
    context: android.content.Context,
    preferences: UserPreferences,
    bookmarks: List<Bookmark>,
    selectedCategory: String?,
    query: String,
    isList: Boolean,
    draggingKey: Long?,
    previewIndex: Int?,
    draggingFrom: Int,
    entryTracker: EntryAnimationTracker,
    keys: List<Long>,
    reorderState: ReorderState,
    columns: Int,
    onAdd: () -> Unit,
    onOpen: (Bookmark) -> Unit,
    onEdit: (Bookmark) -> Unit,
    onTogglePin: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onReorder: (List<Bookmark>) -> Unit,
    onDismissDragHint: () -> Unit,
) {
    if (bookmarks.isEmpty()) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
            EmptyState(
                title = if (query.isBlank() && selectedCategory == null) {
                    s.emptyTitle
                } else {
                    s.noResultsTitle
                },
                body = if (query.isBlank() && selectedCategory == null) {
                    s.emptyBody
                } else {
                    s.noResultsBody
                },
                onAdd = onAdd,
                addLabel = s.addBookmark,
                showAdd = query.isBlank() && selectedCategory == null,
            )
        }
        return
    }

    if (!preferences.dragHintShown && !isList) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "drag-hint") {
            DragHint(text = s.dragHint, onDismiss = onDismissDragHint)
        }
    }

    itemsIndexed(
        items = bookmarks,
        key = { _, bookmark -> bookmark.id },
        span = { _, _ -> GridItemSpan(1) },
    ) { index, bookmark ->
        // Read inside the item lambda: `bookContentItems` is a plain function, so it has no theme of
        // its own to capture from the composable that called it.
        val theme = LumoTheme.state
        val isDragged = draggingKey == bookmark.id
        val previewTarget = previewIndex

        // Live preview: the dragged card follows the finger, and cards between its
        // origin and the preview slot shift by one position. The rendered list itself
        // is never mutated mid-gesture, so nothing jumps.
        val shift = if (!isDragged && previewTarget != null) {
            when {
                draggingFrom < 0 -> 0
                draggingFrom < previewTarget && index in (draggingFrom + 1)..previewTarget -> -1
                draggingFrom > previewTarget && index in previewTarget..<draggingFrom -> 1
                else -> 0
            }
        } else {
            0
        }

        val animatedShift by animateFloatAsState(
            targetValue = shift.toFloat(),
            animationSpec = theme.motion.springMedium(),
            label = "reorder-shift",
        )

        BookmarkCard(
            bookmark = bookmark,
            isList = isList,
            pinnedBadge = s.pinnedBadge,
            openLabel = s.open,
            editLabel = s.edit,
            pinLabel = if (bookmark.isPinned) s.unpin else s.pin,
            deleteLabel = s.delete,
            shareLabel = s.share,
            copyLabel = s.copyLink,
            duplicateLabel = s.duplicate,
            onClick = { onOpen(bookmark) },
            onEdit = { onEdit(bookmark) },
            onTogglePin = { onTogglePin(bookmark) },
            onDelete = { onDelete(bookmark) },
            onShare = {
                ShareUtil.shareUrl(context, bookmark.url, bookmark.title)
            },
            onCopy = {
                ShareUtil.copyUrl(context, bookmark.url)
            },
            onDuplicate = {
                onEdit(bookmark)
            },
            entryDelayIndex = index,
            animateEntry = remember(bookmark.id, entryTracker) {
                entryTracker.isFirstAppearance(bookmark.id)
            },
            modifier = Modifier
                .graphicsLayer {
                    if (isDragged) {
                        translationY = reorderState.dragOffset.y
                        translationX = reorderState.dragOffset.x
                        scaleX = 1.03f
                        scaleY = 1.03f
                    } else {
                        // One row of travel (card height plus the grid gap), which is
                        // what a swap to the neighbouring slot actually moves.
                        translationY = animatedShift * (size.height + 36f)
                    }
                    shadowElevation = if (isDragged) 22f else 0f
                    alpha = if (isDragged) 0.96f else 1f
                }
                .reorderable(
                    state = reorderState,
                    key = bookmark.id,
                    index = index,
                    columns = columns,
                    keys = { keys },
                    onMove = { newOrder ->
                        val byId = bookmarks.associateBy { it.id }
                        val reordered = newOrder.mapNotNull { byId[it] }
                        if (reordered.size == bookmarks.size) onReorder(reordered)
                    },
                ),
        )
    }
}

/** Header: title row with the two top-right entries, then the search field. */
@Composable
private fun HomeHeader(
    title: String,
    countLabel: String,
    query: String,
    onQueryChange: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    clearSearchLabel: String,
    searchHint: String,
    addLabel: String,
    settingsLabel: String,
    onExport: () -> Unit,
    onImport: () -> Unit,
    exportLabel: String,
    importLabel: String,
    crossSiteLabel: String,
    crossSiteHint: String,
    crossSiteExternal: Boolean,
    onToggleCrossSiteExternal: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val feedback = LocalLumoFeedback.current

    Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag(TestTags.HOME_TITLE),
                )
                Text(
                    text = countLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TestTags.HOME_COUNT),
                )
            }

            // Top-right entries, exactly as specified: add + settings, nothing in a bottom bar.
            Box {
                LumoIconButton(
                    icon = LumoIcons.More,
                    contentDescription = settingsLabel,
                    onClick = { menuOpen = true },
                    modifier = Modifier.testTag(TestTags.HOME_OVERFLOW),
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    // Material 3 1.2.1 (Compose BOM 2024.05.00) has no public containerColor on
                    // DropdownMenu, so the menu keeps the themed default surface 闂?the same tonal
                    // family the rest of the app's elevated surfaces use.
                ) {
                    LumoMenuItem(
                        text = { Text(settingsLabel) },
                        leadingIcon = { Icon(LumoIcons.Settings, null) },
                        onClick = { menuOpen = false; onOpenSettings() },
                        modifier = Modifier.testTag(TestTags.MENU_SETTINGS),
                    )
                    LumoMenuItem(
                        text = { Text(exportLabel) },
                        leadingIcon = { Icon(LumoIcons.Upload, null) },
                        onClick = { menuOpen = false; onExport() },
                        modifier = Modifier.testTag(TestTags.MENU_EXPORT),
                    )
                    LumoMenuItem(
                        text = { Text(importLabel) },
                        leadingIcon = { Icon(LumoIcons.Download, null) },
                        onClick = { menuOpen = false; onImport() },
                        modifier = Modifier.testTag(TestTags.MENU_IMPORT),
                    )

                    // A real switch, and deliberately two separate tap targets: the label half
                    // of the row toggles, the switch toggles, and neither one contains the other.
                    // Binding the whole row *and* the switch to the same preference is how one tap
                    // flips it twice and lands back where it started -- which reads exactly like a
                    // menu entry that does nothing.
                    //
                    // The menu deliberately stays open. A switch is only trustworthy if you watch
                    // it move and stay moved, and the value it shows arrives from DataStore rather
                    // than from local state, so what is on screen here is the stored answer.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp)
                            .testTag(TestTags.MENU_CROSS_SITE),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = LumoIcons.OpenExternal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    feedback.tap()
                                    onToggleCrossSiteExternal()
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(
                                text = crossSiteLabel,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = crossSiteHint,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = crossSiteExternal,
                            onCheckedChange = {
                                feedback.tap()
                                onToggleCrossSiteExternal()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            LumoIconButton(
                icon = LumoIcons.Add,
                contentDescription = addLabel,
                onClick = onAdd,
                // NOT `onPrimary`. No button texture draws a solid accent container: Solid is
                // `surfaceContainerHighest`, Soft is accent at 12-14% alpha over the page, Glass is
                // `surfaceContainer` at 45%. `onPrimary` is pure white, so in light mode the app's
                // primary action was white-on-near-white at roughly 1.1:1 闂?invisible. The accent
                // itself reads against all three containers in both themes.
                tint = LumoTheme.state.accent,
                modifier = Modifier.testTag(TestTags.HOME_ADD),
            )
        }

        Spacer(Modifier.height(14.dp))
        SearchField(
            query = query,
            onQueryChange = onQueryChange,
            hint = searchHint,
            clearLabel = clearSearchLabel,
        )
    }
}

/** Minimal text field: one capsule, no label, so it costs almost no vertical space. */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String,
    clearLabel: String,
) {
    val theme = LumoTheme.state
    val treatment = lumoSurface()
    val shape = RoundedCornerShape(theme.radius.pill)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .surfaceTreatment(treatment, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = LumoIcons.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(MaterialTheme.typography.bodyMedium)
                    .copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(theme.accent),
                modifier = Modifier.fillMaxWidth().testTag(TestTags.HOME_SEARCH),
            )
        }
        if (query.isNotEmpty()) {
            LumoIconButton(
                icon = LumoIcons.Clear,
                contentDescription = clearLabel,
                onClick = { onQueryChange("") },
                containerSize = 30.dp,
                iconSize = 16.dp,
                modifier = Modifier.testTag(TestTags.HOME_SEARCH_CLEAR),
            )
        }
    }
}

/** Category filter row; hidden entirely when no bookmark has a category. */
@Composable
private fun CategoryRow(
    categories: List<String>,
    selected: String?,
    allLabel: String,
    uncategorisedLabel: String,
    onSelect: (String?) -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(TestTags.CATEGORY_ROW)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "all") {
                LumoChip(
                    label = allLabel,
                    selected = selected == null,
                    onClick = { onSelect(null) },
                    modifier = Modifier.testTag(TestTags.CATEGORY_ALL),
                )
            }
            item(key = "none") {
                LumoChip(
                    label = uncategorisedLabel,
                    selected = selected == UNCATEGORISED,
                    onClick = { onSelect(UNCATEGORISED) },
                    modifier = Modifier.testTag(TestTags.CATEGORY_NONE),
                )
            }
            items(categories.size, key = { categories[it] }) { index ->
                val category = categories[index]
                LumoChip(
                    label = category,
                    selected = selected == category,
                    onClick = { onSelect(category) },
                    modifier = Modifier.testTag(TestTags.categoryChip(category)),
                )
            }
        }
    }
}

/**
 * The left-hand category strip (1.09, brief 闁圭厧鍊圭粋?2).
 *
 * Two decisions live here, and they are deliberately separate:
 *
 * * the **stacking direction** is vertical for every name 闂?闂佺绻堥崝鎴﹀磿?/ 闂佸搫鐗滄禍婊堝垂鎼达絿灏?/ 闁诲孩鍐荤紓姘卞姬?/ esim闂? * * the **writing direction** is per name: a name made only of CJK characters is stacked one
 *   character per line and reads top-to-bottom, while a name containing Latin letters, digits or
 *   other non-CJK characters is kept on one line and allowed to wrap naturally.
 *
 * That split is the answer to the brief's warning that horizontal-to-vertical does not have to mean
 * every name is set vertically: `esim` is readable, `e`/`s`/`i`/`m` down the screen is not. The same
 * rule handles mixed names such as 闂佺绻愰幊搴ㄥ焵?because a single Han character per line is still readable
 * when a digit or a Latin word sits beside it.
 */
@Composable
private fun CategoryRail(
    categories: List<String>,
    selected: String?,
    allLabel: String,
    uncategorisedLabel: String,
    onSelect: (String?) -> Unit,
    width: Dp,
) {
    val feedback = LocalLumoFeedback.current
    // The strip never shows more than one selected entry, so haptics are driven from a snapshot of
    // "what was selected" rather than from the click handler: the handler cannot know whether the
    // tap actually changed the selection (tapping 闁诲孩鍐荤紓姘卞姬?twice must not buzz twice), and the brief
    // asks for feedback on selection, not on touching.
    var previousSelection by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (previousSelection != selected) {
            previousSelection = selected
            feedback.confirm()
        }
    }

    val configuration = LocalConfiguration.current
    val fontScale = configuration.fontScale
    // The list is a sibling of the bookmark grid, so this is an ordinary scroll container: a drag
    // that starts here is consumed here and cannot reach the grid, and vice versa. Nothing nests,
    // so there is no nested-scroll arbitration to get wrong (brief 闁圭厧鍊垮畷?1).
    val railState = rememberLazyListState()
    val compact = fontScale > 1.3f

    LazyColumn(
        state = railState,
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .testTag(TestTags.CATEGORY_RAIL),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        // Room for the system navigation bar: without this the last category can end up under the
        // gesture bar on a phone with many categories (brief 闁圭厧鍊垮畷?3).
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item(key = "all") {
            CategoryRailItem(
                label = allLabel,
                selected = selected == null,
                onClick = { onSelect(null) },
                compact = compact,
                modifier = Modifier.testTag(TestTags.CATEGORY_ALL),
            )
        }
        item(key = "none") {
            CategoryRailItem(
                label = uncategorisedLabel,
                selected = selected == UNCATEGORISED,
                onClick = { onSelect(UNCATEGORISED) },
                compact = compact,
                modifier = Modifier.testTag(TestTags.CATEGORY_NONE),
            )
        }
        items(categories.size, key = { categories[it] }) { index ->
            val category = categories[index]
            CategoryRailItem(
                label = category,
                selected = selected == category,
                onClick = { onSelect(category) },
                compact = compact,
                modifier = Modifier.testTag(TestTags.categoryRailItem(category)),
            )
        }
    }
}

/**
 * One category in the left strip.
 *
 * The selected treatment is a tinted, rounded background plus an indicator bar that fades in, and
 * both run on the shared UI-transition budget (`navDurationMs`) — the same one the placement switch
 * uses, inside the brief's 120–300 ms band for every preset.
 */
@Composable
private fun CategoryRailItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val theme = LumoTheme.state
    val accent = theme.accent
    // `navDurationMs`: the same UI-transition budget the placement switch uses, so a category tap and
    // a layout flip do not run at two different speeds. In the 120-300 ms band for every preset.
    val duration = theme.motion.duration(theme.motion.navDurationMs)
    val spec = tween<Color>(durationMillis = duration)

    val background by animateColorAsState(
        targetValue = if (selected) accent.copy(alpha = if (theme.isDark) 0.26f else 0.16f) else Color.Transparent,
        animationSpec = spec,
        label = "rail-item-background",
    )
    val indicator by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = duration),
        label = "rail-item-indicator",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = spec,
        label = "rail-item-text",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 36.dp else LumoMinTouchTarget)
            .clip(RoundedCornerShape(theme.buttonRadius.md))
            .background(background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = lumoRipple(accent.copy(alpha = 0.18f)),
                onClick = onClick,
            )
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(18.dp)
                .graphicsLayer { alpha = indicator }
                .background(accent, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(
                text = railLabel(label),
                color = contentColor,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Lays a category name out the way its script wants to be read.
 *
 * A name made only of CJK characters is set one character per line, so it reads top-to-bottom. Any
 * name carrying Latin letters or digits is left as a single string and wraps at word boundaries,
 * because breaking `esim` into `e`/`s`/`i`/`m` would be a legibility regression, not a feature.
 *
 * The stacked form drops whitespace between the characters it keeps: a line break already separates
 * them, so keeping a space as well would leave a visible gap in the middle of a two-character label
 * (and would double the separators, since the join inserts one at every position).
 */
internal fun railLabel(label: String): String = if (isCjkOnly(label)) {
    label.filterNot { it.isWhitespace() }.map(Char::toString).joinToString("\u200B")
} else {
    label
}

/** True when every non-space character of [text] is a Han character. */
internal fun isCjkOnly(text: String): Boolean {
    val meaningful = text.filterNot { it.isWhitespace() }
    return meaningful.isNotEmpty() && meaningful.all { it.code in 0x4E00..0x9FFF }
}

/** One-time hint that long-press starts a reorder; dismissible and persisted. */
@Composable
private fun DragHint(text: String, onDismiss: () -> Unit) {
    val theme = LumoTheme.state
    val treatment = lumoSurface()
    val shape = RoundedCornerShape(theme.radius.md)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .surfaceTreatment(treatment, shape)
            // The one clickable in the tree that used to fall through to the platform's default
            // indication. It was already bounded 闂?`surfaceTreatment` clips before this point 闂?but
            // it rippled at full content colour while every neighbouring control ripples at a tuned
            // alpha, so the quietest row on the screen had the loudest press.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = lumoRipple(theme.accent.copy(alpha = 0.20f)),
                onClick = onDismiss,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(TestTags.DRAG_HINT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = LumoIcons.Sort,
            contentDescription = null,
            tint = theme.accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = LumoIcons.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * Remembers which cards have already played their entry animation.
 *
 * A `LazyVerticalGrid` only composes the items near the viewport and disposes the rest, so an
 * animation keyed purely on `remember(bookmark.id)` restarts every time a card scrolls back into
 * view. On a long list that means the grid is permanently animating while the user scrolls, and a
 * card sitting at `alpha = 0` still accepts taps 闂?the touch lands on something invisible.
 *
 * Instead the animation is granted once per appearance of a card set. The tracker is read from
 * inside `remember`, so a card's answer is computed exactly once and never changes while it stays
 * composed.
 */
private class EntryAnimationTracker {
    private val seen = HashSet<Long>()

    /** Returns true the first time [id] is asked about, false on every later call. */
    fun isFirstAppearance(id: Long): Boolean = seen.add(id)
}

/**
 * A single bookmark card.
 *
 * Shows favicon (with a deterministic initials placeholder behind it), title, domain and optional
 * description. Everything the card can do is reachable from its overflow menu, and the whole card
 * opens the in-app browser.
 */
@Composable
private fun BookmarkCard(
    bookmark: Bookmark,
    isList: Boolean,
    pinnedBadge: String,
    openLabel: String,
    editLabel: String,
    pinLabel: String,
    deleteLabel: String,
    shareLabel: String,
    copyLabel: String,
    duplicateLabel: String,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onDuplicate: () -> Unit,
    entryDelayIndex: Int,
    animateEntry: Boolean,
    modifier: Modifier = Modifier,
) {
    val theme = LumoTheme.state
    val motion = theme.motion
    val treatment = lumoSurface()
    val shape = RoundedCornerShape(theme.radius.lg)
    val interaction = remember { MutableInteractionSource() }
    var menuOpen by remember { mutableStateOf(false) }
    // Opening a bookmark is a pure navigation action with no data side effect, so nothing else in
    // the pipeline would cue it.
    val feedback = LocalLumoFeedback.current

    // Card entry animation: fade in + slight upward shift. It plays once per card set 闂?see
    // [EntryAnimationTracker] 闂?instead of once per composition, so scrolling a long list back and
    // forth is completely static. The stagger delay is capped so a 200-item list does not take ten
    // seconds to finish appearing.
    var appeared by remember(bookmark.id) {
        mutableStateOf(!motion.animationsEnabled || !animateEntry)
    }
    LaunchedEffect(bookmark.id) {
        if (!appeared) appeared = true
    }
    val entryProgress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = motion.duration(motion.cardEntryDurationMs),
            delayMillis = motion.entryDelay(entryDelayIndex),
        ),
        label = "card-entry",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = entryProgress
                translationY = (1f - entryProgress) * motion.offset(motion.cardEntryOffsetPx)
                val scale = 1f - (1f - entryProgress) * (1f - motion.cardEntryScaleFrom)
                scaleX = scale
                scaleY = scale
            }
            .lumoPress(interaction)
            .surfaceTreatment(treatment, shape)
            .clickable(
                interactionSource = interaction,
                // A card that gives no visual acknowledgement of the touch reads as a dead card
                // even when the tap lands 闂?the ripple is what makes the target feel alive. The
                // preceding surfaceTreatment already clipped this node to [shape], so the ripple
                // is bounded by the rounded corners.
                indication = lumoRipple(theme.accent.copy(alpha = 0.18f)),
                onClick = {
                    feedback.tap()
                    onClick()
                },
            )
            // Vertical padding is trimmed so that the 48dp touch target of the overflow button
            // does not make every card taller than it was designed to be.
            .padding(
                start = if (isList) 12.dp else 14.dp,
                end = if (isList) 12.dp else 14.dp,
                top = if (isList) 7.dp else 9.dp,
                bottom = if (isList) 7.dp else 9.dp,
            )
            .testTag(TestTags.bookmarkCard(bookmark.id)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Favicon(
                url = bookmark.url,
                title = bookmark.title,
                domain = bookmark.domain,
                size = if (isList) 30.dp else 38.dp,
            )
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bookmark.title.ifBlank { bookmark.domain },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = bookmark.domain,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (bookmark.isPinned) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(theme.accent.copy(alpha = 0.18f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = pinnedBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (theme.isDark) theme.accent else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.width(6.dp))
            }

            Box {
                // The card already casts [treatment]'s shadow; a second one under this 32dp button
                // would land on the card's own fill and read as a smudge rather than as depth.
                CompositionLocalProvider(
                    LocalLumoOnElevatedSurface provides (treatment.shadowElevation > 0.dp),
                ) {
                    LumoIconButton(
                        icon = LumoIcons.More,
                        contentDescription = null,
                        onClick = { menuOpen = true },
                        containerSize = 32.dp,
                        iconSize = 17.dp,
                        modifier = Modifier.testTag(TestTags.bookmarkMenu(bookmark.id)),
                    )
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    LumoMenuItem(
                        text = { Text(openLabel) },
                        leadingIcon = { Icon(LumoIcons.OpenExternal, null) },
                        onClick = { menuOpen = false; onClick() },
                        modifier = Modifier.testTag(TestTags.bookmarkOpen(bookmark.id)),
                    )
                    LumoMenuItem(
                        text = { Text(editLabel) },
                        leadingIcon = { Icon(LumoIcons.Edit, null) },
                        onClick = { menuOpen = false; onEdit() },
                        modifier = Modifier.testTag(TestTags.bookmarkEdit(bookmark.id)),
                    )
                    LumoMenuItem(
                        text = { Text(pinLabel) },
                        leadingIcon = {
                            Icon(if (bookmark.isPinned) LumoIcons.PinOff else LumoIcons.Pin, null)
                        },
                        onClick = { menuOpen = false; onTogglePin() },
                        modifier = Modifier.testTag(TestTags.bookmarkPin(bookmark.id)),
                    )
                    LumoMenuItem(
                        text = { Text(duplicateLabel) },
                        leadingIcon = { Icon(LumoIcons.Copy, null) },
                        onClick = { menuOpen = false; onDuplicate() },
                    )
                    LumoMenuItem(
                        text = { Text(shareLabel) },
                        leadingIcon = { Icon(LumoIcons.Share, null) },
                        onClick = { menuOpen = false; onShare() },
                    )
                    LumoMenuItem(
                        text = { Text(copyLabel) },
                        leadingIcon = { Icon(LumoIcons.Copy, null) },
                        onClick = { menuOpen = false; onCopy() },
                    )
                    LumoMenuItem(
                        text = { Text(deleteLabel, color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(LumoIcons.Delete, null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menuOpen = false; onDelete() },
                        modifier = Modifier.testTag(TestTags.bookmarkDelete(bookmark.id)),
                    )
                }
            }
        }

        if (bookmark.description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = bookmark.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (isList) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (bookmark.category.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "#" + bookmark.category,
                style = MaterialTheme.typography.labelSmall,
                color = theme.accent.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Favicon with a stable initials placeholder underneath.
 *
 * The placeholder is derived from the domain hash, so the same site always gets the same tint and
 * there is never a blank box while the network image loads (or if it fails 闂?LumoBrowser must work
 * offline, and bookmarks are perfectly usable without their icons).
 */
@Composable
private fun Favicon(
    url: String,
    title: String,
    domain: String,
    size: androidx.compose.ui.unit.Dp,
) {
    val theme = LumoTheme.state
    val context = LocalContext.current
    val shape = RoundedCornerShape(size / 3)
    val seed = remember(domain) { LumoFormat.stableHash(domain) }
    val hue = (seed % 360 + 360) % 360
    val placeholder = remember(seed, theme.isDark) {
        val base = com.lumobrowser.app.ui.theme.AccentPalettes.custom(
            hue = hue.toFloat(),
            saturation = 0.55f,
            lightness = if (theme.isDark) 0.42f else 0.62f,
        )
        base
    }
    val initials = remember(title, domain) { LumoFormat.initials(title, domain) }
    val favicon = remember(url) { faviconUrl(url) }
    val faviconRequest = remember(favicon, context) {
        ImageRequest.Builder(context)
            .data(favicon)
            .crossfade(true)
            .build()
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(placeholder.copy(alpha = if (theme.isDark) 0.32f else 0.26f))
            .border(1.dp, theme.hairline(0.5f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        AsyncImage(
            // Remembered per url. Built inline this allocated a URI, a String, a whole ImageRequest
            // and a crossfade transformer on *every* recomposition of *every* visible card 闂?that is
            // continuously during a fling 闂?and Coil then had to re-diff a brand-new model each time.
            model = faviconRequest,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size * 0.62f),
        )
    }
}

/**
 * Public favicon endpoint, keyed by host.
 *
 * A deliberate choice over hitting `${scheme}://${host}/favicon.ico` directly: many sites do not serve
 * one at the root, and fetching per-site assets from inside the WebView context would be slower.
 * Requests carry only the hostname, are cached on disk by Coil, and nothing about the user's library
 * is transmitted.
 */
internal fun faviconUrl(url: String): String {
    val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
    return "https://icons.duckduckgo.com/ip3/$host.ico"
}

/** Empty / no-results state with a single clear action. */
@Composable
private fun EmptyState(
    title: String,
    body: String,
    onAdd: () -> Unit,
    addLabel: String,
    showAdd: Boolean,
) {
    val theme = LumoTheme.state
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TestTags.HOME_EMPTY)
            .padding(top = 60.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(theme.accent.copy(alpha = if (theme.isDark) 0.14f else 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = LumoIcons.Favorite,
                contentDescription = null,
                tint = theme.accent,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        if (showAdd) {
            Spacer(Modifier.height(20.dp))
            com.lumobrowser.app.ui.components.LumoPrimaryButton(
                text = addLabel,
                onClick = onAdd,
                leadingIcon = LumoIcons.Add,
                modifier = Modifier.testTag(TestTags.HOME_EMPTY_ACTION),
            )
        }
    }
}

/** Deterministically maps a domain to a hue; exported for the favicon placeholder and tests. */
internal fun hueForDomain(domain: String): Int =
    (LumoFormat.stableHash(domain) % 360).absoluteValue

/** Section header used by screens that need one above a grid of cards. */
@Composable
internal fun HomeSectionHeader(text: String, modifier: Modifier = Modifier) {
    LumoSectionHeader(text = text, modifier = modifier)
}
