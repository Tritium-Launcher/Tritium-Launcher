package io.github.footermandev.tritium.service

import io.github.footermandev.tritium.core.modloader.ModLoader
import io.github.footermandev.tritium.core.modloader.modLoaderRegistry
import io.github.footermandev.tritium.core.modpack.ModpackSource
import io.github.footermandev.tritium.core.modpack.modpackSources
import io.github.footermandev.tritium.mainLogger
import java.util.*

fun loadModLoaders() {
    val loader = ServiceLoader.load(ModLoader.Provider::class.java)
    for(p in loader) {
        val i = p.create()
        modLoaderRegistry.add(i)
    }

    mainLogger.info("Loaded Mod Loaders: ${modLoaderRegistry.map { it.id }}")
}

fun loadModpackSources() {
    val source = ServiceLoader.load(ModpackSource.Provider::class.java)
    source.forEach { s ->
        val i = s.create()
        modpackSources.add(i)
    }

    mainLogger.info("Loaded Modpack Sources: ${modpackSources.map { it.id }}")
}

fun loadAllServices() {
    mainLogger.info("Loading services...")


    loadModpackSources()
    loadModLoaders()
}