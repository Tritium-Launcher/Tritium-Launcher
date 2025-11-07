package io.github.footermandev.tritium.core.project

import io.github.footermandev.tritium.debugLogging
import io.github.footermandev.tritium.logger
import kotlinx.coroutines.*
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*

class ProjectDirWatcher(private val rootDir: Path) {
    private val log = logger()
    private fun debug(msg: String, condition: Boolean = debugLogging) { if(condition) log.debug(msg) }
    private val service = FileSystems.getDefault().newWatchService()
    private val keys = mutableMapOf<WatchKey, Path>()
    private var job: Job? = null

    init {
        Files.walk(rootDir)
            .filter { Files.isDirectory(it) }
            .forEach { registerDir(it) }
    }

    private fun registerDir(dir: Path) {
        try {
            val key = dir.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            keys[key] = dir
            debug("Registered directory for watch: ${dir.toAbsolutePath()}")
        } catch (e: Exception) {
            debug("Failed to register directory ${dir.toAbsolutePath()}: ${e.message}")
        }
    }

    fun start(onChange: () -> Unit, debounceMillis: Long = 200L) {
        debug("Starting ProjectDirWatcher for ${rootDir.toAbsolutePath()}")



        job = CoroutineScope(Dispatchers.IO).launch {
            var debounceJob: Job? = null

            try {
                while (isActive) {
                    val key = try {
                        service.take()
                    } catch (_: ClosedWatchServiceException) {
                        break
                    }

                    val dir = keys[key] ?: run {
                        if (!key.reset()) keys.remove(key)
                        continue
                    }

                    var overflow = false
                    for (e in key.pollEvents()) {
                        val kind = e.kind()
                        if (kind == OVERFLOW) {
                            overflow = true
                            continue
                        }

                        val nameObj = e.context()
                        val namePath = nameObj as? Path ?: continue
                        val child = dir.resolve(namePath)

                        if (kind == ENTRY_CREATE) {
                            try {
                                if (Files.isDirectory(child)) registerDir(child)
                            } catch (_: Exception) { }
                        }
                    }

                    if (overflow) {
                        debounceJob?.cancel()
                        debounceJob = null
                        withContext(NonCancellable) { onChange() }
                    } else {
                        debounceJob?.cancel()
                        debounceJob = launch {
                            delay(debounceMillis)
                            withContext(NonCancellable) { onChange() }
                        }
                    }

                    if (!key.reset()) {
                        keys.remove(key)
                        if (keys.isEmpty()) break
                    }
                }
            } finally {
                service.close()
            }
        }
    }

    fun stop() {
        debug("Stopping ProjectDirWatcher for ${rootDir.toAbsolutePath()}")
        try {
            job?.cancel()
            job = null
        } finally {
            try { service.close() } catch (_: Exception) {}
        }
    }
}