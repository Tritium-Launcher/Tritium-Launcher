package io.github.footermandev.tritium.registry

/**
 * Marker for registry entries that expose an [id].
 */
interface Registrable {
    val id: String
}
