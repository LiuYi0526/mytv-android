package top.yogiczy.mytv.tv.ui.material

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yogiczy.mytv.tv.ui.utils.backHandler
import top.yogiczy.mytv.tv.ui.utils.saveFocusRestorer
import top.yogiczy.mytv.tv.ui.utils.saveRequestFocus

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun LazyRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    horizontalArrangement: Arrangement.Horizontal =
        if (!reverseLayout) Arrangement.Start else Arrangement.End,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    backHandler: Boolean = false,
    initialFocusRequester: FocusRequester? = null,
    preservePaddingOnFocus: Boolean = false,
    restoreFocus: Boolean = true,
    focusOnSecondCard: Boolean = false,
    content: LazyListScope.(LazyListRuntime) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val firstItemFocusRequester = remember { FocusRequester() }
    val lastItemFocusRequester = remember { FocusRequester() }
    var isFirstItemFocused by remember { mutableStateOf(false) }
    var isBrowsingHorizontally by remember { mutableStateOf(false) }
    val layoutDirection = LocalLayoutDirection.current
    val leftInset = with(LocalDensity.current) { contentPadding.calculateLeftPadding(layoutDirection).toPx() }
    val rightInset = with(LocalDensity.current) { contentPadding.calculateRightPadding(layoutDirection).toPx() }
    val itemSpacing = with(LocalDensity.current) { horizontalArrangement.spacing.toPx() }
    val defaultScrollSpec = LocalUnpaddedScrollSpec.current ?: LocalBringIntoViewSpec.current
    val scrollSpec = remember(defaultScrollSpec, leftInset, rightInset, itemSpacing, preservePaddingOnFocus, focusOnSecondCard) {
        if (!preservePaddingOnFocus && !focusOnSecondCard) defaultScrollSpec else object : BringIntoViewSpec by defaultScrollSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                // Reserve the row's safe area when bringing a focused card back into view.
                val availableWidth = (containerSize - leftInset - rightInset).coerceAtLeast(0f)
                if (focusOnSecondCard) {
                    val secondCardOffset = size + itemSpacing
                    if (isBrowsingHorizontally && secondCardOffset + size <= availableWidth) {
                        // LazyRow clamps the scroll at both ends of the list.
                        return offset - leftInset - secondCardOffset
                    }
                    if (!isBrowsingHorizontally && offset < containerSize && offset + size > 0f) {
                        // A card reached from another row keeps its current horizontal position.
                        return 0f
                    }
                }
                return defaultScrollSpec.calculateScrollDistance(offset - leftInset, size, availableWidth)
            }
        }
    }

    fun scrollToFirst() {
        coroutineScope.launch {
            state.scrollToItem(0)
            firstItemFocusRequester.requestFocus()
        }
    }

    fun scrollToLast() {
        coroutineScope.launch {
            state.scrollToItem(state.layoutInfo.totalItemsCount - 1)
            lastItemFocusRequester.requestFocus()
        }
    }

    val horizontalFocusModifier = if (focusOnSecondCard) {
        Modifier
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    isBrowsingHorizontally = event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                }
                false
            }
            .onFocusChanged { if (!it.hasFocus) isBrowsingHorizontally = false }
    } else {
        Modifier
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides scrollSpec) {
        androidx.compose.foundation.lazy.LazyRow(
            modifier = modifier
                .then(horizontalFocusModifier)
                .then(if (restoreFocus) Modifier.saveFocusRestorer { initialFocusRequester ?: firstItemFocusRequester } else Modifier)
                .focusGroup()
                .backHandler({ backHandler && !isFirstItemFocused }) {
                    coroutineScope.launch {
                        scrollToFirst()
                        firstItemFocusRequester.saveRequestFocus()
                    }
                },
            state = state,
            contentPadding = contentPadding,
            reverseLayout = reverseLayout,
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = verticalAlignment,
            flingBehavior = flingBehavior,
            userScrollEnabled = userScrollEnabled,
            content = {
                content(
                    LazyListRuntime(
                        LazyListDirection.Horizontal,
                        firstItemFocusRequester,
                        lastItemFocusRequester,
                        { isFirstItemFocused = it },
                        { scrollToFirst() },
                        { scrollToLast() },
                    )
                )
            },
        )
    }
}
