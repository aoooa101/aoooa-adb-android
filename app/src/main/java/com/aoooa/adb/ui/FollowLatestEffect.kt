package com.aoooa.adb.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*

/** 根据内容版本跟随列表末尾；用户拖动时暂停，回到底部后恢复。 */
@Composable
internal fun FollowLatestEffect(
    state: LazyListState,
    enabled: Boolean,
    itemCount: Int,
    contentVersion: Any?
) {
    var following by remember(state) { mutableStateOf(true) }
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) following = false
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { !state.isScrollInProgress && !state.canScrollForward }
            .collect { atBottom -> if (atBottom) following = true }
    }
    LaunchedEffect(enabled, itemCount, contentVersion, following) {
        if (enabled && following && itemCount > 0) {
            state.scrollToItem(itemCount - 1)
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull { it.index == itemCount - 1 }
            if (last != null) state.scrollBy(last.size.toFloat())
        }
    }
}
