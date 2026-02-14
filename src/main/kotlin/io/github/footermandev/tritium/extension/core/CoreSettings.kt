package io.github.footermandev.tritium.extension.core

import io.github.footermandev.tritium.connect
import io.github.footermandev.tritium.settings.RefreshableSettingWidget
import io.github.footermandev.tritium.settings.SettingWidgetContext
import io.github.footermandev.tritium.settings.settingsDefinition
import io.github.footermandev.tritium.ui.widgets.InfoLineEditWidget
import io.github.footermandev.tritium.ui.widgets.constructor_functions.hBoxLayout
import io.qt.core.Qt
import io.qt.widgets.QLabel
import io.qt.widgets.QWidget
import kotlinx.serialization.builtins.serializer

private val WINDOW_SIZE_REGEX = Regex("^([1-9][0-9]{0,4})x([1-9][0-9]{0,4})$")
private val WINDOW_DIMENSION_REGEX = Regex("^[1-9][0-9]{0,4}$")

private data class WindowSizeParts(
    val width: String,
    val height: String
)

private fun parseWindowSizeParts(raw: String): WindowSizeParts? {
    val match = WINDOW_SIZE_REGEX.matchEntire(raw.trim()) ?: return null
    return WindowSizeParts(
        width = match.groupValues[1],
        height = match.groupValues[2]
    )
}

private fun encodeWindowSize(widthRaw: String, heightRaw: String): String? {
    val width = widthRaw.trim()
    val height = heightRaw.trim()
    if (!WINDOW_DIMENSION_REGEX.matches(width) || !WINDOW_DIMENSION_REGEX.matches(height)) {
        return null
    }
    return "${width}x${height}"
}

private class WindowSizeWidget(
    private val ctx: SettingWidgetContext<String>,
    placeholder: String?
) : QWidget(), RefreshableSettingWidget {
    private val widthInput = InfoLineEditWidget(ctx.descriptor.description.orEmpty()).apply {
        objectName = "settingsInput"
        minimumWidth = 72
    }
    private val separator = QLabel("X").apply {
        setAlignment(Qt.AlignmentFlag.AlignCenter)
        minimumWidth = 12
    }
    private val heightInput = InfoLineEditWidget(ctx.descriptor.description.orEmpty()).apply {
        objectName = "settingsInput"
        minimumWidth = 72
    }

    private var isRefreshing = false

    init {
        val layout = hBoxLayout(this).apply {
            setContentsMargins(0, 0, 0, 0)
            widgetSpacing = 6
            setAlignment(Qt.AlignmentFlag.AlignVCenter)
        }
        layout.addWidget(widthInput, 1)
        layout.addWidget(separator, 0)
        layout.addWidget(heightInput, 1)

        val hint = parseWindowSizeParts(placeholder.orEmpty())
        widthInput.placeholderText = hint?.width ?: "Width"
        heightInput.placeholderText = hint?.height ?: "Height"

        widthInput.editingFinished.connect { commit() }
        heightInput.editingFinished.connect { commit() }

        refreshFromSettingValue()
    }

    override fun refreshFromSettingValue() {
        isRefreshing = true
        try {
            val current = ctx.currentValue().trim()
            val currentParts = parseWindowSizeParts(current)
            when {
                currentParts != null -> {
                    widthInput.text = currentParts.width
                    heightInput.text = currentParts.height
                    setInvalid(false)
                }
                current.isNotEmpty() -> {
                    val split = current.split('x', 'X', limit = 2)
                    widthInput.text = split.getOrElse(0) { "" }
                    heightInput.text = split.getOrElse(1) { "" }
                    setInvalid(true)
                }
                else -> {
                    val defaultRaw = (ctx.descriptor.defaultValue as? String).orEmpty()
                    val defaultParts = parseWindowSizeParts(defaultRaw)
                    widthInput.text = defaultParts?.width.orEmpty()
                    heightInput.text = defaultParts?.height.orEmpty()
                    setInvalid(false)
                }
            }
        } finally {
            isRefreshing = false
        }
    }

    private fun commit() {
        if (isRefreshing) return
        val encoded = encodeWindowSize(widthInput.text, heightInput.text)
        if (encoded == null) {
            setInvalid(true)
            return
        }
        setInvalid(false)
        ctx.updateValue(encoded)
    }

    private fun setInvalid(invalid: Boolean) {
        applyInvalid(widthInput, invalid)
        applyInvalid(heightInput, invalid)
    }

    private fun applyInvalid(input: InfoLineEditWidget, invalid: Boolean) {
        input.setProperty("invalid", invalid)
        input.style().unpolish(input)
        input.style().polish(input)
        input.update()
    }
}

/**
 * Core settings schema declarations.
 *
 * Define settings once here, register from extension bootstrap with a namespace.
 */
internal object CoreSettings {
    val registration = settingsDefinition {
        val general = category("general") {
            title = "General"
            description = "Common launcher preferences."
            allowForeignSettings = true
            allowForeignSubcategories = true
        }

        val versionControl = category("version_control") {
            title = "Version Control"
            description = "Git executable and related integration settings."
            parent = general
            allowForeignSettings = true
            allowForeignSubcategories = true
        }

        val ui = category("ui") {
            title = "Appearance & UI"
            description = "Themes, fonts, layout tweaks."
            parent = general
            allowForeignSettings = true
        }

        val projects = category("projects") {
            title = "Projects & Windows"
            description = "Project open behavior and default window sizing."
            parent = general
            allowForeignSettings = true
        }

        toggle(projects.path, "projects.close_dashboard_on_open") {
            title = "Close Dashboard When Opening Project"
            description = "Automatically close the dashboard after opening a project window."
            defaultValue = true
            comments = listOf(
                "When true, opening a project window closes the dashboard window.",
                "Set to false if you prefer keeping the dashboard open."
            )
        }

        widget<String>(ui.path, "ui.dashboard.window_size") {
            title = "Dashboard Window Size"
            description = "Fixed dashboard window size represented as WIDTH x HEIGHT."
            defaultValue = "650x400"
            serializer = String.serializer()
            comments = listOf(
                "Dashboard size in WIDTHxHEIGHT format.",
                "Example: 650x400"
            )
            widgetFactory = { ctx -> WindowSizeWidget(ctx, "650x400") }
        }

        widget<String>(projects.path, "ui.project_window.default_size") {
            title = "Project Window Default Size"
            description = "Default project window size used when no saved UI geometry exists."
            defaultValue = "1280x720"
            serializer = String.serializer()
            comments = listOf(
                "Default project window size in WIDTHxHEIGHT format.",
                "Used only when no saved geometry is available."
            )
            widgetFactory = { ctx -> WindowSizeWidget(ctx, "1280x720") }
        }

        val gameMaximized = toggle(projects.path, "game.maximized") {
            title = "Game Launch Maximized"
            description = "Launches the game window maximized instead of using an explicit resolution."
            defaultValue = false
            comments = listOf(
                "When enabled, game launch arguments should prefer maximized window behavior.",
                "Resolution fields are disabled while maximized is enabled."
            )
        }

        val gameResolution = widget<String>(projects.path, "game.default_resolution") {
            title = "Game Launch Resolution"
            description = "Launch resolution represented as WIDTH x HEIGHT used for token replacement."
            defaultValue = "1280x720"
            serializer = String.serializer()
            comments = listOf(
                "Default Minecraft launch resolution in WIDTHxHEIGHT format.",
                "Used when replacing resolution tokens in launch arguments."
            )
            widgetFactory = { ctx -> WindowSizeWidget(ctx, "1280x720") }
        }
        gameMaximized.addChild(gameResolution) { maximized -> !maximized }

        text(versionControl.path, "git.path") {
            title = "Git Executable Path"
            description = "Optional absolute path to the git executable. Leave blank to use auto-detection."
            defaultValue = ""
            placeholder = "Auto-detected from PATH"
            comments = listOf(
                "Optional absolute path to the git executable.",
                "Leave blank to allow Tritium to auto-detect git from PATH."
            )
        }

        category("extensions") {
            title = "Extensions"
            description = "Extension-provided settings."
            allowForeignSubcategories = true
            allowForeignSettings = false
        }
    }
}
