package io.github.footermandev.tritium.ui.project.menu.builtin

import io.github.footermandev.tritium.platform.GameLauncher
import io.github.footermandev.tritium.ui.project.menu.MenuItem
import io.github.footermandev.tritium.ui.project.menu.MenuItemKind
import io.github.footermandev.tritium.ui.project.menu.builtin.BuiltinMenuItems.All

/**
 * Built-in menu items contributed by the core extension.
 *
 * These entries are registered into the `ui.menu` registry and collected in [All].
 */
object BuiltinMenuItems {
    val Play = MenuItem(
        id = "play",
        title = "Play",
        order = -10,
        kind = MenuItemKind.ACTION,
        action = { ctx ->
            val project = ctx.project ?: return@MenuItem
            GameLauncher.launch(project)
        }
    )
    val File = MenuItem("file", "&File", order = 0, kind = MenuItemKind.MENU)
    val Edit = MenuItem("edit", "&Edit", order = 10, kind = MenuItemKind.MENU)
    val View = MenuItem("view", "&View", order = 20, kind = MenuItemKind.MENU)
    val Help = MenuItem("help", "&Help", order = 100, kind = MenuItemKind.MENU)

    val All = listOf(Play, File, Edit, View, Help)
}
