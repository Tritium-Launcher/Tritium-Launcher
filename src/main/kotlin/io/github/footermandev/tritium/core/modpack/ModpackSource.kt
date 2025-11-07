package io.github.footermandev.tritium.core.modpack

import io.qt.gui.QPixmap

val modpackSources = mutableListOf<ModpackSource>()

abstract class ModpackSource {
    abstract val id: String
    abstract val displayName: String
    abstract val icon: QPixmap
    abstract val webpage: String

    companion object {

        fun of(id: String?): ModpackSource? =
            if(id == null) null else modpackSources.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    override fun toString(): String = id

    fun interface Provider {
        fun create(): ModpackSource
    }
}