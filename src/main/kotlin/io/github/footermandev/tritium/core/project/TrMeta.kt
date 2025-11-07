package io.github.footermandev.tritium.core.project

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TrMeta(
    val type: String,
    val schemaVersion: Int = 1,
    val meta: JsonElement
)
