package ai.opencode.client.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var selectedProvider by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = selectedProvider != null) {
        selectedProvider = null
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 560.dp)
                .padding(horizontal = 20.dp)
                .testTag("model.manager.sheet")
        ) {
            item {
                if (selectedProvider == null) {
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
                } else {
                    val providerTitle = selectedProvider
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedProvider = null }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back),
                                modifier = Modifier.testTag("model.manager.back"),
                            )
                        }
                        Text(
                            providerTitle.orEmpty(),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
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
                val provider = selectedProvider?.let { grouped[it] }
                if (provider == null) {
                    val pinnedCountByProvider = pinnedModels.groupingBy { it.providerId }.eachCount()
                    val sortedProviders = grouped.entries.sortedWith(
                        compareByDescending<Map.Entry<String, List<AppState.ModelOption>>> { pinnedCountByProvider[it.key] ?: 0 }
                            .thenBy { it.key },
                    )
                    sortedProviders.forEach { (providerId, models) ->
                        item(key = "provider-$providerId") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedProvider = providerId }
                                    .padding(vertical = 12.dp)
                                    .testTag("model.manager.provider"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(providerId, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        stringResource(
                                            R.string.settings_manage_models_provider_summary,
                                            models.size,
                                            pinnedCountByProvider[providerId] ?: 0,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                } else {
                    items(provider, key = { "${it.providerId}/${it.modelId}" }) { model ->
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
                }
            }
        }
    }
}
