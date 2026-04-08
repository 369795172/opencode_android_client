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
 * 2) Append remaining selectable server models not covered by presets.
 * 3) If providers unavailable, fall back to presets.
 */
internal fun resolveAvailableModels(
    presets: List<AppState.ModelOption>,
    providers: ProvidersResponse?
): List<AppState.ModelOption> {
    val list = providers?.providers.orEmpty()
    if (list.isEmpty()) return presets
    val byId = list.associateBy { it.id }
    val preferred = presets.filter { preset ->
        val provider = byId[preset.providerId] ?: return@filter false
        val model = provider.models[preset.modelId] ?: return@filter false
        isProviderModelSelectable(model)
    }
    val preferredKeys = preferred.map { "${it.providerId}/${it.modelId}" }.toSet()
    val discovered = list.flatMap { provider ->
        provider.models.values
            .filter { isProviderModelSelectable(it) }
            .map { model ->
                AppState.ModelOption(
                    displayName = model.name ?: model.id,
                    providerId = provider.id,
                    modelId = model.id
                )
            }
    }.filter { option ->
        "${option.providerId}/${option.modelId}" !in preferredKeys
    }.sortedBy { "${it.providerId}/${it.displayName}".lowercase() }
    val merged = preferred + discovered
    return if (merged.isEmpty()) presets else merged
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
