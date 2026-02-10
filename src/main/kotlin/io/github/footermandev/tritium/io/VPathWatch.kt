package io.github.footermandev.tritium.io

import io.github.footermandev.tritium.io.VPath.Companion.parse
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

data class VWatchOptions(
    val recursive: Boolean = false,
    val followSymLinks: Boolean = false,
    val kinds: List<WatchEvent.Kind<Path>> = listOf(ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY),
    val pollTimeoutMillis: Long = 0L
)

data class VWatchEvent(
    val kind: Kind,
    val path: VPath,
    val ctxPath: String? = null
) {
    enum class Kind { Create, Modify, Delete, Overflow }

    companion object {
        internal fun fromWatchEvent(kind: WatchEvent.Kind<Path>, base: VPath, ctx: WatchEvent<Path>?): VWatchEvent {
            return when(kind) {
                ENTRY_CREATE -> {
                    val ctx = ctx?.context().toString()
                    val p = base.resolve(ctx)
                    VWatchEvent(Kind.Create, p, ctx)
                }
                ENTRY_MODIFY -> {
                    val ctx = ctx?.context().toString()
                    val p = base.resolve(ctx)
                    VWatchEvent(Kind.Modify, p, ctx)
                }
                ENTRY_DELETE -> {
                    val ctx = ctx?.context().toString()
                    val p = base.resolve(ctx)
                    VWatchEvent(Kind.Delete, p, ctx)
                }

                OVERFLOW -> {
                    VWatchEvent(Kind.Overflow, base, null)
                }
                else -> VWatchEvent(Kind.Overflow, base, ctx?.context().toString())
            }
        }
    }
}

class VPathWatcher internal constructor(
    private val service: WatchService,
    private val job: Job,
    private val registeredKeys: Map<WatchKey, VPath>
): Closeable {
    override fun close() {
        try {
            job.cancel()
        } catch (_: Exception) {}
        try {
            service.close()
        } catch (_: Exception) {}
    }

    fun registeredDirectories(): List<VPath> = registeredKeys.values.toList()
}

private fun registerDirToService(dir: Path, service: WatchService, kinds: List<WatchEvent.Kind<Path>>, followSymLinks: Boolean): WatchKey {
    return dir.register(service, kinds.toTypedArray())
}

fun VPath.watch(
    callback: (VWatchEvent) -> Unit,
    options: VWatchOptions = VWatchOptions(),
    ctx: CoroutineContext = Dispatchers.IO
): VPathWatcher {
    val rootJ = this.toJPath()

    if(!Files.exists(rootJ) || !Files.isDirectory(rootJ)) {
        throw IllegalArgumentException("Path is not an existing directory: '$this'")
    }

    val service = FileSystems.getDefault().newWatchService()

    val registered = HashMap<WatchKey, VPath>()
    
    fun register(startPath: Path) {
        if(!Files.isDirectory(startPath)) return
        Files.walkFileTree(startPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                dir: Path,
                attrs: BasicFileAttributes
            ): FileVisitResult {
                if(!options.followSymLinks && Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE
                val key = registerDirToService(dir, service, options.kinds, options.followSymLinks)
                registered[key] = parse(dir.toString()).toAbsolute()
                return FileVisitResult.CONTINUE
            }
        })
    }

    if(options.recursive) {
        register(rootJ)
    } else {
        val key = registerDirToService(rootJ, service, options.kinds, options.followSymLinks)
        registered[key] = this.toAbsolute()
    }

    val scope = CoroutineScope(ctx + SupervisorJob())
    val job = scope.launch {
        try {
            while(isActive) {
                val key = if(options.pollTimeoutMillis > 0) {
                    service.poll(options.pollTimeoutMillis, TimeUnit.MILLISECONDS)
                } else {
                    try {
                        service.take()
                    } catch (ise: InterruptedException) { null }
                } ?: continue

                val watchedBaseV = registered[key] ?: this@watch.toAbsolute()

                for(e in key.pollEvents()) {
                    val ePath = e as? WatchEvent<Path>
                    val kind = e.kind()
                    val ve = VWatchEvent.fromWatchEvent(kind as WatchEvent.Kind<Path>, watchedBaseV, ePath)
                    try {
                        callback(ve)
                    } catch (t: Throwable) {
                        logger.error("Exception in watch callback for event $ve", t)
                    }

                    if(options.recursive && kind == ENTRY_CREATE) {
                        val ctx = ePath?.context()
                        if(ctx != null) {
                            val childV = watchedBaseV.resolve(ctx.toString()).toAbsolute()
                            try {
                                val childJ = childV.toJPath()
                                if(Files.isDirectory(childJ) && (!Files.isSymbolicLink(childJ) || options.followSymLinks)) {
                                    register(childJ)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }

                val valid = key.reset()
                if(!valid) {
                    registered.remove(key)
                    if(registered.isEmpty()) break
                }
            }
        } catch (t: Throwable) {
            logger.warn("VPath watch loop terminated due to error", t)
        } finally {

        }
    }

    return VPathWatcher(service, job, registered)
}

fun VPath.watchAsFlow(options: VWatchOptions = VWatchOptions()): Flow<VWatchEvent> = callbackFlow {
    val service = FileSystems.getDefault().newWatchService()
    val registered = HashMap<WatchKey, VPath>()

    fun register(startPath: Path) {
        if (!Files.isDirectory(startPath)) return
        Files.walkFileTree(startPath, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!options.followSymLinks && Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE
                val key = registerDirToService(dir, service, options.kinds, options.followSymLinks)
                registered[key] = parse(dir.toString()).toAbsolute()
                return FileVisitResult.CONTINUE
            }
        })
    }

    if (options.recursive) {
        register(this@watchAsFlow.toJPath())
    } else {
        val key = registerDirToService(this@watchAsFlow.toJPath(), service, options.kinds, options.followSymLinks)
        registered[key] = this@watchAsFlow.toAbsolute()
    }

    val watcherJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
        try {
            while (isActive) {
                val key = service.take()
                val base = registered[key] ?: this@watchAsFlow.toAbsolute()

                for (ev in key.pollEvents()) {
                    @Suppress("UNCHECKED_CAST")
                    val evPath = ev as? WatchEvent<Path>
                    val kind = ev.kind()
                    val vpe = VWatchEvent.fromWatchEvent(kind as WatchEvent.Kind<Path>, base, evPath)

                    try {
                        trySend(vpe).isSuccess
                    } catch (_: Exception) {
                    }

                    if (options.recursive && kind == ENTRY_CREATE) {
                        val ctx = evPath?.context()
                        if (ctx != null) {
                            val childVp = base.resolve(ctx.toString()).toAbsolute()
                            try {
                                val childJvm = childVp.toJPath()
                                if (Files.isDirectory(childJvm) && (!Files.isSymbolicLink(childJvm) || options.followSymLinks)) {
                                    register(childJvm)
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                }

                val valid = key.reset()
                if (!valid) {
                    registered.remove(key)
                    if (registered.isEmpty()) {
                        break
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            close(t)
        } finally {
        }
    }

    awaitClose {
        try { watcherJob.cancel() } catch (_: Exception) {}
        try { service.close() } catch (_: Exception) {}
    }
}.flowOn(Dispatchers.IO)