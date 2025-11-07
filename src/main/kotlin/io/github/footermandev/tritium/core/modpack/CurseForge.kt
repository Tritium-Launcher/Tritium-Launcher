package io.github.footermandev.tritium.core.modpack

import io.github.footermandev.tritium.ui.theme.TIcons
import io.qt.gui.QPixmap

data class CurseForge(
    override val id: String = "curseforge",
    override val displayName: String = "CurseForge",
    override val icon: QPixmap = TIcons.CurseForge,
    override val webpage: String = "https://www.curseforge.com/"
) : ModpackSource() {
    override fun toString(): String = id

//    @AutoService(ModpackSource.Provider::class)
//    internal class Provider: ModpackSource.Provider {
//        override fun create(): ModpackSource = Modrinth()
//    }
}