package ai.opencode.client.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ai.opencode.client.R
import ai.opencode.client.ui.AppState
import ai.opencode.client.ui.isPinnedModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelManagerSheet(
    allModels: List<AppState.ModelOption>,
    pinnedModels: List<AppState.ModelOption>,
    onPin: (AppState.ModelOption) -> Unit,
    onUnpin: (AppState.ModelOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val grouped = allModels.groupBy { it.providerId }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 560.dp)
                .padding(horizontal = 20.dp)
                .testTag("model.manager.sheet")
        ) {
            item {
                Text(
                    stringResource(R.string.settings_manage_models),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_manage_models_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            if (grouped.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.settings_manage_models_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            } else {
                grouped.forEach { (providerId, models) ->
                    item(key = "header-$providerId") {
                        Text(
                            providerId,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    items(models, key = { "${it.providerId}/${it.modelId}" }) { model ->
                        val pinned = isPinnedModel(pinnedModels, model)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(model.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    model.modelId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = pinned,
                                onCheckedChange = { checked ->
                                    if (checked) onPin(model) else onUnpin(model)
                                },
                            )
                        }
                    }
                    item(key = "divider-$providerId") {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}
