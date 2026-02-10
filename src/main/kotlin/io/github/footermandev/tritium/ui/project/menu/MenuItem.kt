package io.github.footermandev.tritium.ui.project.menu

import io.github.footermandev.tritium.registry.Registrable
import io.qt.widgets.QWidget

/**
 * Menu contribution model.
 *
 * Supports actions, menus, separators, and arbitrary widgets.
 * Extensions register instances via the `ui.menu` registry.
 */
enum class MenuItemKind { ACTION, MENU, SEPARATOR, WIDGET }

data class MenuItem(
    override val id: String,
    val title: String,
    val parentId: String? = null,
    val action: ((MenuActionContext) -> Unit)? = null,
    val order: Int = 0,
    val visible: Boolean = true,
    val enabled: Boolean = true,
    val shortcut: String? = null,
    val meta: Map<String, String> = emptyMap(),
    val kind: MenuItemKind = MenuItemKind.MENU,
    val widgetFactory: ((MenuActionContext) -> QWidget)? = null,
    val tooltip: String? = null
): Registrable
