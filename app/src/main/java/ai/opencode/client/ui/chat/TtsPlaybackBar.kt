package ai.opencode.client.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TtsPlaybackBar(
    isPlaying: Boolean,
    isPaused: Boolean,
    progress: Float,
    currentChunk: Int,
    totalChunks: Int,
    speechRate: Float,
    onSeek: (Float) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sliderProgress by remember { mutableFloatStateOf(progress) }
    var isUserDragging by remember { mutableStateOf(false) }
    val chunkLabel = if (totalChunks > 1) {
        "${currentChunk + 1}/$totalChunks"
    } else {
        null
    }

    LaunchedEffect(progress) {
        if (!isUserDragging) {
            sliderProgress = progress.coerceIn(0f, 1f)
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        if (isPaused) onResume() else onPause()
                    },
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Resume reading" else "Pause reading",
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Reading AI reply",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = buildString {
                            append("${(sliderProgress * 100).toInt()}%")
                            chunkLabel?.let { append(" · chunk $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Stop reading",
                    )
                }
            }

            Slider(
                value = sliderProgress.coerceIn(0f, 1f),
                onValueChange = { value ->
                    isUserDragging = true
                    sliderProgress = value
                },
                onValueChangeFinished = {
                    isUserDragging = false
                    onSeek(sliderProgress)
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Speed",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TtsSpeedOptions.forEach { rate ->
                    FilterChip(
                        selected = speechRate == rate,
                        onClick = { onSpeechRateChange(rate) },
                        label = { Text("${rate}x") },
                    )
                }
            }
        }
    }
}

private val TtsSpeedOptions = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
