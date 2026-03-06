package com.yage.opencode_client.ui.session

import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yage.opencode_client.data.model.Session
import kotlin.math.roundToInt

private enum class SwipeAnchor { Start, End }

private const val SESSION_PAGE_SIZE = 20

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeRevealRow(
    dragState: AnchoredDraggableState<SwipeAnchor>,
    enabled: Boolean,
    onDelete: () -> Unit,
    altBg: Boolean,
    isSelected: Boolean,
    displayName: String,
    onSelect: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.error)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.CenterEnd
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete session",
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(x = -dragState.requireOffset().roundToInt(), y = 0) }
                .anchoredDraggable(
                    state = dragState,
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    reverseDirection = true
                )
                .background(
                    if (altBg) MaterialTheme.colorScheme.surfaceContainerLow
                    else MaterialTheme.colorScheme.surface
                )
                .clickable(onClick = onSelect)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun SessionList(
    sessions: List<Session>,
    currentSessionId: String?,
    onSelectSession: (String) -> Unit,
    onCreateSession: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onOpenSettings: (() -> Unit)? = null
) {
    var displayedCount by remember { mutableStateOf(SESSION_PAGE_SIZE) }
    val sessionsToShow = sessions.take(displayedCount)
    val listState = rememberLazyListState()

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }.collect { lastVisible ->
            if (lastVisible >= sessionsToShow.size - 2 && sessionsToShow.size < sessions.size) {
                displayedCount = (displayedCount + SESSION_PAGE_SIZE).coerceAtMost(sessions.size)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 0.dp,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Sessions",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onCreateSession) {
                    Text("New")
                }
                if (onOpenSettings != null) {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            itemsIndexed(sessionsToShow, key = { _, s -> s.id }) { index, session ->
                val isSelected = session.id == currentSessionId
                val altBg = index % 2 == 1
                val density = LocalDensity.current
                val deleteWidthPx = with(density) { 56.dp.toPx() }
                val decay = rememberSplineBasedDecay<Float>()
                val dragState = remember(deleteWidthPx) {
                    AnchoredDraggableState(
                        initialValue = SwipeAnchor.Start,
                        anchors = DraggableAnchors {
                            SwipeAnchor.Start at 0f
                            SwipeAnchor.End at deleteWidthPx
                        },
                        positionalThreshold = { total: Float -> total * 0.5f },
                        velocityThreshold = { with(density) { 100.dp.toPx() } },
                        snapAnimationSpec = tween(),
                        decayAnimationSpec = decay
                    )
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwipeRevealRow(
                        dragState = dragState,
                        enabled = !listState.isScrollInProgress,
                        onDelete = { onDeleteSession(session.id) },
                        altBg = altBg,
                        isSelected = isSelected,
                        displayName = session.displayName,
                        onSelect = { onSelectSession(session.id) }
                    )
                    if (index < sessionsToShow.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
            if (sessionsToShow.size < sessions.size) {
                item(key = "load_more") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Loading more...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}
