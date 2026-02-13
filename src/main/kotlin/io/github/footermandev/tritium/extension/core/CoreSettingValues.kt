package io.github.footermandev.tritium.extension.core

import io.github.footermandev.tritium.logger
import io.github.footermandev.tritium.settings.NamespacedId
import io.github.footermandev.tritium.settings.SettingsMngr

private val WINDOW_SIZE_REGEX = Regex("^([1-9][0-9]{0,4})x([1-9][0-9]{0,4})$")

/**
 * Core settings keys consumed by built-in runtime code.
 */
internal object CoreSettingKeys {
    val GitPath: NamespacedId = NamespacedId("tritium", "git.path")
    val CloseDashboardOnProjectOpen: NamespacedId = NamespacedId("tritium", "projects.close_dashboard_on_open")
    val DashboardWindowSize: NamespacedId = NamespacedId("tritium", "ui.dashboard.window_size")
    val ProjectWindowDefaultSize: NamespacedId = NamespacedId("tritium", "ui.project_window.default_size")
    val GameLaunchMaximized: NamespacedId = NamespacedId("tritium", "game.maximized")
    val GameDefaultResolution: NamespacedId = NamespacedId("tritium", "game.default_resolution")
}

/**
 * Typed readers for core settings values with safe fallbacks.
 */
internal object CoreSettingValues {
    private val logger = logger()

    /**
     * Whether dashboard should close when opening a project window.
     */
    fun closeDashboardOnProjectOpen(): Boolean =
        (SettingsMngr.currentValueOrNull(CoreSettingKeys.CloseDashboardOnProjectOpen) as? Boolean) ?: true

    /**
     * Fixed dashboard window size.
     */
    fun dashboardWindowSize(): Pair<Int, Int> =
        parseWindowSize(CoreSettingKeys.DashboardWindowSize, 650, 400)

    /**
     * Default project window size when no saved geometry exists.
     */
    fun projectWindowDefaultSize(): Pair<Int, Int> =
        parseWindowSize(CoreSettingKeys.ProjectWindowDefaultSize, 1280, 720)

    /**
     * Whether game launch should prefer maximized window behavior.
     */
    fun gameLaunchMaximized(): Boolean =
        (SettingsMngr.currentValueOrNull(CoreSettingKeys.GameLaunchMaximized) as? Boolean) ?: false

    /**
     * Default WIDTHxHEIGHT resolution used by game launch token replacement.
     */
    fun gameLaunchResolution(): Pair<Int, Int> =
        parseWindowSize(CoreSettingKeys.GameDefaultResolution, 1280, 720)

    private fun parseWindowSize(key: NamespacedId, fallbackWidth: Int, fallbackHeight: Int): Pair<Int, Int> {
        val raw = (SettingsMngr.currentValueOrNull(key) as? String)?.trim().orEmpty()
        if (raw.isEmpty()) return fallbackWidth to fallbackHeight

        val match = WINDOW_SIZE_REGEX.matchEntire(raw)
        if (match == null) {
            logger.warn("Invalid window size '{}' for {}. Falling back to {}x{}", raw, key, fallbackWidth, fallbackHeight)
            return fallbackWidth to fallbackHeight
        }

        val width = match.groupValues[1].toIntOrNull()
        val height = match.groupValues[2].toIntOrNull()
        if (width == null || height == null || width <= 0 || height <= 0) {
            logger.warn("Invalid window size '{}' for {}. Falling back to {}x{}", raw, key, fallbackWidth, fallbackHeight)
            return fallbackWidth to fallbackHeight
        }
        return width to height
    }
}
