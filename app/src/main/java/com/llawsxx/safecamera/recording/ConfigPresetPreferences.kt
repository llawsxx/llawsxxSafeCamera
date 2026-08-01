package com.llawsxx.safecamera.recording

import android.content.Context
import java.util.UUID

data class ConfigPreset(
    val id: String,
    val name: String,
    val config: RecordingConfig,
    val updatedAt: Long,
)

object ConfigPresetPreferences {
    private const val INDEX_NAME = "recording_config_presets"
    private const val IDS_KEY = "ids"
    private const val PRESET_PREFIX = "recording_config_preset_"

    fun load(context: Context): List<ConfigPreset> {
        val index = context.getSharedPreferences(INDEX_NAME, Context.MODE_PRIVATE)
        return index.getStringSet(IDS_KEY, emptySet()).orEmpty()
            .mapNotNull { id ->
                val name = index.getString(nameKey(id), null)?.trim().orEmpty()
                if (name.isBlank()) return@mapNotNull null
                ConfigPreset(
                    id = id,
                    name = name,
                    config = ConfigPreferences.load(presetPreferences(context, id)),
                    updatedAt = index.getLong(updatedAtKey(id), 0L),
                )
            }
            .sortedWith(compareByDescending<ConfigPreset> { it.updatedAt }.thenBy { it.name })
    }

    fun create(context: Context, name: String, config: RecordingConfig): ConfigPreset {
        val id = UUID.randomUUID().toString()
        val normalizedName = name.trim()
        val updatedAt = System.currentTimeMillis()
        ConfigPreferences.save(presetPreferences(context, id), config)
        val index = context.getSharedPreferences(INDEX_NAME, Context.MODE_PRIVATE)
        val ids = index.getStringSet(IDS_KEY, emptySet()).orEmpty().toMutableSet().apply { add(id) }
        index.edit()
            .putStringSet(IDS_KEY, ids)
            .putString(nameKey(id), normalizedName)
            .putLong(updatedAtKey(id), updatedAt)
            .apply()
        return ConfigPreset(id, normalizedName, config, updatedAt)
    }

    fun update(context: Context, preset: ConfigPreset, config: RecordingConfig): ConfigPreset {
        val updatedAt = System.currentTimeMillis()
        ConfigPreferences.save(presetPreferences(context, preset.id), config)
        context.getSharedPreferences(INDEX_NAME, Context.MODE_PRIVATE).edit()
            .putLong(updatedAtKey(preset.id), updatedAt)
            .apply()
        return preset.copy(config = config, updatedAt = updatedAt)
    }

    fun delete(context: Context, preset: ConfigPreset) {
        presetPreferences(context, preset.id).edit().clear().apply()
        val index = context.getSharedPreferences(INDEX_NAME, Context.MODE_PRIVATE)
        val ids = index.getStringSet(IDS_KEY, emptySet()).orEmpty().toMutableSet().apply {
            remove(preset.id)
        }
        index.edit()
            .putStringSet(IDS_KEY, ids)
            .remove(nameKey(preset.id))
            .remove(updatedAtKey(preset.id))
            .apply()
    }

    private fun presetPreferences(context: Context, id: String) =
        context.getSharedPreferences(PRESET_PREFIX + id, Context.MODE_PRIVATE)

    private fun nameKey(id: String) = "name_$id"
    private fun updatedAtKey(id: String) = "updated_at_$id"
}
