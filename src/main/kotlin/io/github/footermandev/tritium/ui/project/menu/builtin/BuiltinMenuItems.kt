package io.github.footermandev.tritium.ui.project.menu.builtin

import io.github.footermandev.tritium.TConstants
import io.github.footermandev.tritium.core.project.ProjectBase
import io.github.footermandev.tritium.fromTR
import io.github.footermandev.tritium.logger
import io.github.footermandev.tritium.platform.CompanionBridge
import io.github.footermandev.tritium.platform.CompanionBridgeResponse
import io.github.footermandev.tritium.platform.GameLauncher
import io.github.footermandev.tritium.platform.GameProcessMngr
import io.github.footermandev.tritium.ui.helpers.runOnGuiThread
import io.github.footermandev.tritium.ui.notifications.NotificationMngr
import io.github.footermandev.tritium.ui.project.menu.MenuActionContext
import io.github.footermandev.tritium.ui.project.menu.MenuItem
import io.github.footermandev.tritium.ui.project.menu.MenuItemKind
import io.github.footermandev.tritium.ui.project.menu.builtin.BuiltinMenuItems.All
import io.github.footermandev.tritium.ui.theme.TIcons
import io.github.footermandev.tritium.ui.theme.qt.grayOverlay
import io.github.footermandev.tritium.ui.theme.qt.icon
import io.qt.widgets.QMessageBox
import kotlinx.coroutines.*
import java.nio.file.Files

/**
 * Built-in menu items contributed by the core extension.
 *
 * These entries are registered into the `ui.menu` registry and collected in [All].
 */
object BuiltinMenuItems {
    private val logger = logger()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private const val CLOSE_TIMEOUT_MS = 15_000L
    private const val COMPANION_PING_TIMEOUT_MS = 1_500L
    private const val STOP_POLL_INTERVAL_MS = 200L
    private const val STOP_POLL_TIMEOUT_MS = 8_000L
    private const val KILL_WAIT_TIMEOUT_MS = 4_000L

    val Play = MenuItem(
        id = "play",
        title = "Play",
        order = 1_000,
        kind = MenuItemKind.ACTION,
        meta = mapOf(
            "align" to "right",
            "iconOnly" to "true"
        ),
        icon = TIcons.Run.icon,
        iconResolver = { ctx ->
            val running = ctx.project?.let { GameLauncher.isGameRunning(it) } == true
            if (running) TIcons.Rerun.icon else TIcons.Run.icon
        },
        tooltip = "Play game (reruns if already running)",
        action = { ctx ->
            val project = ctx.project ?: return@MenuItem
            scope.launch {
                val wasRunning = GameLauncher.isGameRunning(project)
                if (wasRunning) {
                    val stop = stopGame(project)
                    if (!stop.stopped) {
                        logger.warn("Play/Rerun stop phase failed: {}", stop.message)
                        return@launch
                    }
                }
                GameLauncher.launch(project)
            }
        }
    )

    val Stop = MenuItem(
        id = "stop_game",
        title = "Stop",
        order = 1_010,
        kind = MenuItemKind.ACTION,
        meta = mapOf(
            "align" to "right",
            "iconOnly" to "true"
        ),
        icon = TIcons.Stop.icon,
        enabledResolver = { ctx ->
            ctx.project?.let { GameLauncher.isGameRunning(it) } == true
        },
        iconResolver = { ctx ->
            val running = ctx.project?.let { GameLauncher.isGameRunning(it) } == true
            if (running) TIcons.Stop.icon else TIcons.Stop.icon.grayOverlay(alpha = 170)
        },
        tooltip = "Request game shutdown",
        action = { ctx ->
            val project = ctx.project ?: return@MenuItem
            scope.launch {
                val stop = stopGame(project)
                if (!stop.stopped) {
                    logger.warn("Stop action failed: {}", stop.message)
                }
            }
        }
    )

    val File = MenuItem("file", "&File", order = 0, kind = MenuItemKind.MENU)
    val Edit = MenuItem("edit", "&Edit", order = 10, kind = MenuItemKind.MENU)
    val View = MenuItem("view", "&View", order = 20, kind = MenuItemKind.MENU)
    val Game = MenuItem("game", "&Game", order = 30, kind = MenuItemKind.MENU)
    val Help = MenuItem("help", "&Help", order = 100, kind = MenuItemKind.MENU)

    val InvalidateCaches = MenuItem(
        id = "invalidate_caches",
        title = "Invalidate Caches",
        parentId = File.id,
        order = 90,
        kind = MenuItemKind.ACTION,
        enabledResolver = {
            GameProcessMngr.active().none { ctx -> ctx.isRunning }
        },
        tooltip = "Delete shared runtime caches so they regenerate on next launch",
        action = { ctx ->
            if (!confirmInvalidateCaches(ctx)) return@MenuItem

            scope.launch(Dispatchers.IO) {
                val result = invalidateCaches()
                runOnGuiThread {
                    showInvalidateResult(ctx, result)
                }
            }
        }
    )

    val ReloadServer = MenuItem(
        id = "reload_server",
        title = "Reload Server",
        parentId = Game.id,
        order = 0,
        kind = MenuItemKind.ACTION,
        action = { ctx ->
            val project = ctx.project
            scope.launch {
                val response = CompanionBridge.reloadServer()
                postBridgeResponse(project, "Reload Server", response)
            }
        }
    )

    val DumpRegistry = MenuItem(
        id = "dump_registry",
        title = "Dump Registry",
        parentId = Game.id,
        order = 10,
        kind = MenuItemKind.ACTION,
        action = { ctx ->
            val project = ctx.project
            scope.launch {
                val response = CompanionBridge.sendCommand("dumpRegistry")
                postBridgeResponse(project, "Dump Registry", response)
            }
        }
    )

    private fun postBridgeResponse(project: ProjectBase?, actionName: String, response: CompanionBridgeResponse) {
        if (!response.ok) {
            logger.warn("Companion action '{}' failed: {}", actionName, response.message)
        }

        postActionStatus(
            project,
            if (response.ok) actionName else "$actionName Failed",
            response.message,
            if (response.ok) TIcons.Run.icon else TIcons.Cross.icon
        )
    }

    private fun postActionStatus(project: ProjectBase?, header: String, message: String, icon: io.qt.gui.QIcon) {
        runOnGuiThread {
            NotificationMngr.post(
                id = "generic",
                project = project,
                header = header,
                description = message,
                icon = icon
            )
        }
    }

    private data class InvalidateCachesResult(
        val deleted: List<String>,
        val failed: List<String>
    )

    private fun confirmInvalidateCaches(ctx: MenuActionContext): Boolean {
        val box = QMessageBox(ctx.window)
        box.icon = QMessageBox.Icon.Question
        box.windowTitle = "Invalidate Caches"
        box.text = "Invalidate shared launcher caches?"
        box.informativeText = "This will delete cache, loaders, and assets under ~/tritium. They will be regenerated on next launch."
        val invalidateButton = box.addButton("Invalidate", QMessageBox.ButtonRole.AcceptRole)
        box.addButton(QMessageBox.StandardButton.Cancel)
        box.exec()
        return box.clickedButton() == invalidateButton
    }

    private fun showInvalidateResult(ctx: MenuActionContext, result: InvalidateCachesResult) {
        val box = QMessageBox(ctx.window)
        if (result.failed.isEmpty()) {
            box.icon = QMessageBox.Icon.Information
            box.windowTitle = "Caches Invalidated"
            box.text = "Shared caches were invalidated."
            box.informativeText = "Deleted: ${result.deleted.joinToString(", ")}"
        } else {
            box.icon = QMessageBox.Icon.Warning
            box.windowTitle = "Cache Invalidation Incomplete"
            box.text = "Some cache directories could not be invalidated."
            box.informativeText = "Deleted: ${result.deleted.joinToString(", ")}\nFailed: ${result.failed.joinToString(", ")}"
        }
        box.addButton(QMessageBox.StandardButton.Ok)
        box.exec()
    }

    private fun invalidateCaches(): InvalidateCachesResult {
        val targets = listOf(
            TConstants.Dirs.CACHE,
            TConstants.Dirs.LOADERS,
            TConstants.Dirs.ASSETS
        )

        val deleted = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (name in targets) {
            val dir = fromTR(name)
            val ok = deleteTree(dir)
            if (ok) {
                deleted += name
                runCatching { dir.mkdirs() }
            } else {
                failed += name
            }
        }
        return InvalidateCachesResult(deleted = deleted, failed = failed)
    }

    private fun deleteTree(path: io.github.footermandev.tritium.io.VPath): Boolean {
        if (!path.exists()) return true
        return try {
            Files.walk(path.toJPath()).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { p ->
                    Files.deleteIfExists(p)
                }
            }
            true
        } catch (t: Throwable) {
            logger.warn("Failed to delete cache directory {}", path.toAbsolute(), t)
            false
        }
    }

    private suspend fun stopGame(project: ProjectBase): StopResult {
        if (!GameLauncher.isGameRunning(project)) {
            return StopResult(true, "Game is not running.")
        }

        val companionAvailable = isCompanionAvailable()
        if (!companionAvailable) {
            return forceKillAndAwait(project, "Companion is unavailable; game process was force-stopped.")
        }

        val closeResponse = try {
            CompanionBridge.closeGame(timeoutMs = CLOSE_TIMEOUT_MS)
        } catch (t: Throwable) {
            logger.warn("Companion close_game request failed", t)
            CompanionBridgeResponse(false, "Companion close_game request failed.")
        }

        val stopped = waitForStopped(project, STOP_POLL_TIMEOUT_MS)
        if (stopped) {
            val msg = if (closeResponse.ok) {
                "Game closed."
            } else {
                "Game stopped."
            }
            return StopResult(true, msg)
        }

        val fallback = closeResponse.message.ifBlank { "Unable to stop game gracefully." }
        return forceKillAndAwait(project, fallback)
    }

    private suspend fun waitForStopped(project: ProjectBase, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
        while (System.currentTimeMillis() < end) {
            if (!GameLauncher.isGameRunning(project)) return true
            delay(STOP_POLL_INTERVAL_MS)
        }
        return !GameLauncher.isGameRunning(project)
    }

    private suspend fun isCompanionAvailable(): Boolean {
        val ping = try {
            CompanionBridge.ping(timeoutMs = COMPANION_PING_TIMEOUT_MS)
        } catch (t: Throwable) {
            logger.debug("Companion ping failed", t)
            return false
        }
        return ping.ok
    }

    private suspend fun forceKillAndAwait(project: ProjectBase, baseMessage: String): StopResult {
        val killed = GameLauncher.killGameProcess(project, force = true)
        if (!killed) {
            return StopResult(false, baseMessage.ifBlank { "Unable to stop game process." })
        }
        val stoppedAfterKill = waitForStopped(project, KILL_WAIT_TIMEOUT_MS)
        if (!stoppedAfterKill) {
            return StopResult(false, "Stop requested, but the game process is still running.")
        }
        return StopResult(true, if (baseMessage.isBlank()) "Game process was force-stopped." else baseMessage)
    }

    private data class StopResult(
        val stopped: Boolean,
        val message: String
    )

    val All = listOf(
        Play,
        Stop,
        File,
        InvalidateCaches,
        Edit,
        View,
        Game,
        Help,
        ReloadServer,
        DumpRegistry
    )
}
