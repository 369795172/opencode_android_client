package ai.opencode.client.ui

import ai.opencode.client.data.model.ProviderModel
import ai.opencode.client.data.model.ProvidersResponse

internal fun isProviderModelSelectable(model: ProviderModel): Boolean {
    val status = model.status
    return status == null || status == "active"
}

/**
 * Merge strategy:
 * 1) Keep preset models that exist and are selectable on server.
 * 2) For presets with [modelIdPrefix], when exact modelId is missing, pick the
 *    latest selectable model matching the prefix (sorted descending so higher
 *    versions win, e.g. "glm-5.1" over "glm-5").
 * 3) If providers unavailable, fall back to presets.
 */
internal fun resolveAvailableModels(
    presets: List<AppState.ModelOption>,
    providers: ProvidersResponse?
): List<AppState.ModelOption> {
    val list = providers?.providers.orEmpty()
    if (list.isEmpty()) return presets
    val byId = list.associateBy { it.id }
    val preferred = presets.mapNotNull { preset ->
        val provider = byId[preset.providerId] ?: return@mapNotNull null
        val exactModel = provider.models[preset.modelId]
        if (exactModel != null && isProviderModelSelectable(exactModel)) {
            return@mapNotNull preset
        }
        val prefix = preset.modelIdPrefix
        if (prefix != null) {
            val best = provider.models.entries
                .filter { it.key.startsWith(prefix) && isProviderModelSelectable(it.value) }
                .maxByOrNull { it.key }
            if (best != null) {
                return@mapNotNull preset.copy(
                    displayName = preset.displayName,
                    modelId = best.key
                )
            }
        }
        null
    }
    return if (preferred.isEmpty()) presets else preferred
}

internal fun remapSelectedModelIndex(
    previousList: List<AppState.ModelOption>,
    newList: List<AppState.ModelOption>,
    previousIndex: Int
): Int {
    if (newList.isEmpty()) return 0
    val safePrev = previousIndex.coerceIn(0, (previousList.size - 1).coerceAtLeast(0))
    val selected = previousList.getOrNull(safePrev) ?: return 0
    val idx = newList.indexOfFirst {
        it.providerId == selected.providerId && it.modelId == selected.modelId
    }
    return if (idx >= 0) idx else 0
}
