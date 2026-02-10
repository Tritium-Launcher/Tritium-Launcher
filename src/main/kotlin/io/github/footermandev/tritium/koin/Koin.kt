package io.github.footermandev.tritium.koin

import io.github.footermandev.tritium.registry.Registrable
import io.github.footermandev.tritium.registry.Registry
import io.github.footermandev.tritium.registry.RegistryMngr
import org.koin.core.context.GlobalContext


inline fun <reified T: Registrable> getRegistry(name: String): Registry<T> =
    GlobalContext.get().get<RegistryMngr>().getOrCreateRegistry<T>(name)
