package io.github.footermandev.tritium.core.modloader

import io.github.footermandev.tritium.TConstants.TR_DIR
import io.qt.gui.QPixmap
import java.io.File
import java.net.URI

val modLoaderRegistry = mutableListOf<ModLoader>()

/**
 * Open class for ModLoader implementations.
 * Tritium will support NeoForge and Fabric. Support for other Mod Loaders may be added in the future.
 * Additionally, Plugins will be able to add their own ModLoader implementations.
 *
 * TODO: Mainly just a note for the future, currently this is extended directly in Tritium,
 * but it may be changed to be customized via scripting, not sure yet.
 */
abstract class ModLoader {
    abstract val id: String
    abstract val displayName: String
    abstract val repository: URI
    abstract val oldestVersion: String
    abstract val icon: QPixmap

    abstract suspend fun download(version: String): Boolean
    abstract suspend fun uninstall(version: String): Boolean
    abstract suspend fun getVersions(): List<String>
    abstract suspend fun getCompatibleVersions(version: String): List<String>

    abstract fun isInstalled(version: String): Boolean
    abstract fun getInstalled(): List<String>

    abstract suspend fun getLatest(): String?
    abstract suspend fun getDownloadUrl(version: String): URI?
    abstract suspend fun update(version: String): Boolean

    companion object {
        val INSTALL_DIR = File(TR_DIR, "loaders")

        fun of(loader: String?): ModLoader? =
            if(loader == null) null else modLoaderRegistry.firstOrNull { it.id.equals(loader, ignoreCase = true) }
    }

    override fun toString(): String = displayName

    fun interface Provider {
        fun create(): ModLoader
    }
}