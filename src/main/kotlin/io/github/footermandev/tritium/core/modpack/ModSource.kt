package io.github.footermandev.tritium.core.modpack

import io.github.footermandev.tritium.registry.Registrable
import io.qt.gui.QPixmap

/**
 * Mod Sources are web APIs that provide Mods and other content to users. Examples are [CurseForge] and [Modrinth].
 */
abstract class ModSource: Registrable {
    abstract override val id: String
    abstract val displayName: String
    abstract val icon: QPixmap
    abstract val webpage: String
    abstract val order: Int

    override fun toString(): String = id
}