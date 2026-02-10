package io.github.footermandev.tritium.core.project

import kotlinx.serialization.Serializable

/**
 * Modpack-specific metadata stored in `.tr/manifest.json`.
 */
@Serializable
data class ModpackMeta(
    val id: String,
    val minecraftVersion: String,
    val loader: String,
    val loaderVersion: String,
    val source: String,
    val license: String? = null,
    val icon: String? = null
)
