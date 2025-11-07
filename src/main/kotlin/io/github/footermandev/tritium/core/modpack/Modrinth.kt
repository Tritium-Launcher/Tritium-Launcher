package io.github.footermandev.tritium.core.modpack

import com.google.auto.service.AutoService
import io.github.footermandev.tritium.ui.theme.TIcons
import io.qt.gui.QPixmap

data class Modrinth(
    override val id: String = "modrinth",
    override val displayName: String = "Modrinth",
    override val icon: QPixmap = TIcons.Modrinth,
    override val webpage: String = "https://modrinth.com/"
) : ModpackSource() {
    override fun toString(): String = id

    @AutoService(ModpackSource.Provider::class)
    class Provider: ModpackSource.Provider {
        override fun create(): ModpackSource = Modrinth()
    }
}
