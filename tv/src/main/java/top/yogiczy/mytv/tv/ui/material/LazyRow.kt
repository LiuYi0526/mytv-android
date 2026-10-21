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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
    content: LazyListScope.(LazyListRuntime) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val firstItemFocusRequester = remember { FocusRequester() }
    val lastItemFocusRequester = remember { FocusRequester() }
    var isFirstItemFocused by remember { mutableStateOf(false) }
    val layoutDirection = LocalLayoutDirection.current
    val leftInset = with(LocalDensity.current) { contentPadding.calculateLeftPadding(layoutDirection).toPx() }
    val rightInset = with(LocalDensity.current) { contentPadding.calculateRightPadding(layoutDirection).toPx() }
    val defaultScrollSpec = LocalBringIntoViewSpec.current
    val scrollSpec = remember(defaultScrollSpec, leftInset, rightInset, preservePaddingOnFocus) {
        if (!preservePaddingOnFocus) defaultScrollSpec else object : BringIntoViewSpec by defaultScrollSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                // Reserve the row's safe area when bringing a focused card back into view.
                return defaultScrollSpec.calculateScrollDistance(
                    offset - leftInset, size, (containerSize - leftInset - rightInset).coerceAtLeast(0f),
                )
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

    CompositionLocalProvider(LocalBringIntoViewSpec provides scrollSpec) {
        androidx.compose.foundation.lazy.LazyRow(
            modifier = modifier
                .focusGroup()
                .saveFocusRestorer { initialFocusRequester ?: firstItemFocusRequester }
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
