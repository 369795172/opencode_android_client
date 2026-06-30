package ai.opencode.client.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun TtsPlaybackBar(
    messageId: String?,
    isPaused: Boolean,
    speechRate: Float,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember(messageId) { mutableStateOf(false) }
    val statusText = if (isPaused) "Paused" else "Reading"

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { if (isPaused) onResume() else onPause() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Resume reading" else "Pause reading",
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = "$statusText · ${formatSpeechRate(speechRate)}",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = if (isExpanded) {
                            Icons.Default.KeyboardArrowDown
                        } else {
                            Icons.Default.KeyboardArrowUp
                        },
                        contentDescription = if (isExpanded) "Minimize controls" else "Expand controls",
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Stop reading",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (isExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Speed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TtsSpeedOptions.forEach { rate ->
                        FilterChip(
                            selected = speechRate == rate,
                            onClick = { onSpeechRateChange(rate) },
                            label = { Text("${rate}x", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        }
    }
}

private fun formatSpeechRate(rate: Float): String {
    val rounded = (rate * 100).toInt() / 100f
    return if (rounded == rounded.toLong().toFloat()) {
        "${rounded.toLong()}x"
    } else {
        "${rate}x"
    }
}

private val TtsSpeedOptions = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
