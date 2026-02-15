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

        val versionControl = category("version_control") {
            title = "Version Control"
            allowForeignSettings = true
            allowForeignSubcategories = true
        }

        val ui = category("ui") {
            title = "Appearance & UI"
            allowForeignSettings = true
        }

        val projects = category("projects") {
            title = "Projects"
            allowForeignSettings = true
        }

        val javaRuntime = category("java_runtime") {
            title = "Java Runtime"
            parent = projects
            allowForeignSettings = true
        }

        toggle(projects.path, "projects.close_dashboard_on_open") {
            title = "Close Dashboard When Opening Project"
            description = "Automatically close the dashboard after opening a project window."
            defaultValue = true
            comments = listOf(
                "When true, opening a project window closes the dashboard window."
            )
        }

        toggle(projects.path, "minecraft.include_prerelease_versions") {
            title = "Include Pre-release MC Versions"
            description = "Include snapshot, pre-release, and release-candidate Minecraft versions in selectors."
            defaultValue = false
            comments = listOf(
                "When enabled, Minecraft version lists include pre-release versions."
            )
        }

        widget(ui.path, "ui.dashboard.window_size") {
            title = "Dashboard Window Size"
            description = "Fixed dashboard window size represented as WIDTH x HEIGHT."
            defaultValue = "650x400"
            serializer = String.serializer()
            comments = listOf(
                "Dashboard size in WIDTHxHEIGHT format."
            )
            widgetFactory = { ctx -> WindowSizeWidget(ctx, "650x400") }
        }

        widget(projects.path, "ui.project_window.default_size") {
            title = "Project Window Default Size"
            description = "Default project window size used when saved values are broken."
            defaultValue = "1280x720"
            serializer = String.serializer()
            comments = listOf(
                "Default project window size in WIDTHxHEIGHT format."
            )
            widgetFactory = { ctx -> WindowSizeWidget(ctx, "1280x720") }
        }

        val gameMaximized = toggle(projects.path, "game.maximized") {
            title = "Game Launch Maximized"
            description = "Launches the game window maximized."
            defaultValue = false
        }

        val gameResolution = widget(projects.path, "game.default_resolution") {
            title = "Game Launch Resolution"
            description = "Game resolution represented as WIDTH x HEIGHT when launched."
            defaultValue = "1280x720"
            serializer = String.serializer()
            comments = listOf(
                "Default Minecraft launch resolution in WIDTHxHEIGHT format."
            )
            widgetFactory = { ctx -> WindowSizeWidget(ctx, "1280x720") }
        }
        gameMaximized.addChild(gameResolution) { maximized -> !maximized }

        widget(javaRuntime.path, "java.path.8") {
            title = "Java 8 Path"
            description = "Java runtime for Minecraft 1.16.5 and below."
            defaultValue = ""
            serializer = String.serializer()
            comments = listOf(
                "Java executable path (or JAVA_HOME) for MC 1.16.5 and below."
            )
            widgetFactory = { ctx -> JavaPathSettingWidget(ctx, 8) }
        }

        widget(javaRuntime.path, "java.path.17") {
            title = "Java 17 Path"
            description = "Java runtime for Minecraft 1.17 to 1.20."
            defaultValue = ""
            serializer = String.serializer()
            comments = listOf(
                "Java executable path (or JAVA_HOME) for MC 1.17 through 1.20."
            )
            widgetFactory = { ctx -> JavaPathSettingWidget(ctx, 17) }
        }

        widget(javaRuntime.path, "java.path.21") {
            title = "Java 21 Path"
            description = "Java runtime for Minecraft 1.21 to 1.21.11."
            defaultValue = ""
            serializer = String.serializer()
            comments = listOf(
                "Java executable path (or JAVA_HOME) for MC 1.21 through 1.21.11."
            )
            widgetFactory = { ctx -> JavaPathSettingWidget(ctx, 21) }
        }

        widget(javaRuntime.path, "java.path.25") {
            title = "Java 25 Path"
            description = "Java runtime for Minecraft 26.1."
            defaultValue = ""
            serializer = String.serializer()
            comments = listOf(
                "Java executable path (or JAVA_HOME) for MC 26.*."
            )
            widgetFactory = { ctx -> JavaPathSettingWidget(ctx, 25) }
        }

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
