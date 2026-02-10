package io.github.footermandev.tritium.core.modpack

import io.github.footermandev.tritium.registry.Registrable
import io.github.footermandev.tritium.ui.theme.TIcons
import io.qt.gui.QPixmap

data class Modrinth(
    override val id: String = "modrinth",
    override val displayName: String = "Modrinth",
    override val icon: QPixmap = TIcons.Modrinth,
    override val webpage: String = "https://modrinth.com/",
    override val order: Int = 2
) : ModSource(), Registrable {
    override fun toString(): String = id
}
