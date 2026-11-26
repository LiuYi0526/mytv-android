@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package top.yogiczy.mytv.tv.ui.material

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Nested scrolling containers must start from the original policy, not inherit another axis's insets.
internal val LocalUnpaddedScrollSpec = staticCompositionLocalOf<BringIntoViewSpec?> { null }

@Composable
private fun PreserveVerticalFocusPadding(
    padding: PaddingValues,
    reverseLayout: Boolean,
    focusRowSpacing: Dp? = null,
    content: @Composable () -> Unit,
) {
    val baseSpec = LocalUnpaddedScrollSpec.current ?: LocalBringIntoViewSpec.current
    val top = with(LocalDensity.current) { padding.calculateTopPadding().toPx() }
    val bottom = with(LocalDensity.current) { padding.calculateBottomPadding().toPx() }
    val rowSpacing = with(LocalDensity.current) { focusRowSpacing?.toPx() }
    val leading = if (reverseLayout) bottom else top
    val trailing = if (reverseLayout) top else bottom
    val paddedSpec = remember(baseSpec, leading, trailing, rowSpacing) {
        object : BringIntoViewSpec by baseSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val contentOffset = offset - leading
                val contentHeight = (containerSize - leading - trailing).coerceAtLeast(0f)
                if (rowSpacing != null && size * 2 + rowSpacing <= contentHeight) {
                    // Leave a full row above the focused card. The grid clamps scrolling at its ends.
                    return contentOffset - (size + rowSpacing)
                }
                return baseSpec.calculateScrollDistance(contentOffset, size, contentHeight)
            }
        }
    }
    CompositionLocalProvider(
        LocalUnpaddedScrollSpec provides baseSpec,
        LocalBringIntoViewSpec provides paddedSpec,
        content = content,
    )
}

@Composable
fun PaddedLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = if (reverseLayout) Arrangement.Bottom else Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    PreserveVerticalFocusPadding(contentPadding, reverseLayout) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = modifier,
            state = state,
            contentPadding = contentPadding,
            reverseLayout = reverseLayout,
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            flingBehavior = flingBehavior,
            userScrollEnabled = userScrollEnabled,
            content = content,
        )
    }
}

@Composable
fun PaddedLazyVerticalGrid(
    columns: GridCells,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = if (reverseLayout) Arrangement.Bottom else Arrangement.Top,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    focusOnSecondRow: Boolean = false,
    content: LazyGridScope.() -> Unit,
) {
    PreserveVerticalFocusPadding(
        contentPadding,
        reverseLayout,
        focusRowSpacing = verticalArrangement.spacing.takeIf { focusOnSecondRow },
    ) {
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = columns,
            modifier = modifier,
            state = state,
            contentPadding = contentPadding,
            reverseLayout = reverseLayout,
            verticalArrangement = verticalArrangement,
            horizontalArrangement = horizontalArrangement,
            flingBehavior = flingBehavior,
            userScrollEnabled = userScrollEnabled,
            content = content,
        )
    }
}
