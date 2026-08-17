package ai.opencode.client.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class ModelPresetJson(
    @SerialName("display_name") val displayName: String,
    @SerialName("provider_id") val providerId: String,
    @SerialName("model_id") val modelId: String,
    @SerialName("model_id_prefix") val modelIdPrefix: String? = null,
)

internal object ModelPresetSync {
    const val WORKSPACE_PATH = "contexts/model_presets.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(models: List<AppState.ModelOption>): String {
        val payload = models.map {
            ModelPresetJson(
                displayName = it.displayName,
                providerId = it.providerId,
                modelId = it.modelId,
                modelIdPrefix = it.modelIdPrefix,
            )
        }
        return json.encodeToString(payload)
    }

    fun parse(raw: String): List<AppState.ModelOption>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return try {
            json.decodeFromString<List<ModelPresetJson>>(trimmed).map { dto ->
                AppState.ModelOption(
                    displayName = dto.displayName,
                    providerId = dto.providerId,
                    modelId = dto.modelId,
                    modelIdPrefix = dto.modelIdPrefix,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun loadOrSeed(storedJson: String?, seed: List<AppState.ModelOption> = ModelPresets.list): PinnedModelsLoad {
        if (storedJson.isNullOrBlank()) {
            return PinnedModelsLoad(models = seed, shouldPersist = true)
        }
        val parsed = parse(storedJson)
        return if (parsed == null) {
            PinnedModelsLoad(models = seed, shouldPersist = true)
        } else {
            PinnedModelsLoad(models = parsed, shouldPersist = false)
        }
    }
}

internal data class PinnedModelsLoad(
    val models: List<AppState.ModelOption>,
    val shouldPersist: Boolean,
)
