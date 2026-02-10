package io.github.footermandev.tritium.ui.project.menu

import io.github.footermandev.tritium.connect
import io.github.footermandev.tritium.core.project.ProjectBase
import io.github.footermandev.tritium.extension.core.BuiltinRegistries
import io.github.footermandev.tritium.logger
import io.github.footermandev.tritium.ui.widgets.constructor_functions.hBoxLayout
import io.qt.gui.QAction
import io.qt.widgets.*

/**
 * Command bar that replaces the native menu bar and supports:
 * - Action buttons
 * - Drop-down menus
 * - Separators
 * - Arbitrary embedded widgets via [MenuItem.widgetFactory]
 *
 * Extensions contribute items via the `ui.menu` registry.
 * Mnemonics are supported via '&' in titles (Qt standard).
 */
class ProjectMenuBar : QWidget() {
    private val logger = logger()

    private val layout = hBoxLayout(this).apply {
        widgetSpacing = 6
        setContentsMargins(6, 2, 6, 2)
    }

    fun attach(window: QMainWindow) {
        window.setMenuWidget(this)
    }

    fun rebuildFor(window: QMainWindow, project: ProjectBase?, selection: Any?) {
        clearLayout()

        val items = BuiltinRegistries.MenuItem.all().sortedWith(compareBy({ it.parentId ?: "" }, { it.order }, { it.title }))

        val children = HashMap<String, MutableList<MenuItem>>()
        val roots = mutableListOf<MenuItem>()

        for (it in items) {
            val pid = it.parentId
            if (pid == null) roots.add(it) else children.computeIfAbsent(pid) { mutableListOf() }.add(it)
        }

        fun buildMenu(parent: QMenu, parentId: String) {
            val kids = children[parentId] ?: return
            val sorted = kids.sortedWith(compareBy({ it.order }, { it.title }))
            for (k in sorted) {
                if (!k.visible) continue
                if (k.kind == MenuItemKind.SEPARATOR) {
                    parent.addSeparator()
                    continue
                }
                val subK = children[k.id]
                val hasChildren = !subK.isNullOrEmpty()
                val action = QAction(k.title, window)
                action.isEnabled = k.enabled
                k.shortcut?.let { sc -> action.setShortcut(sc) }
                k.tooltip?.let { action.toolTip = it }
                action.triggered.connect {
                    try {
                        val ctx = MenuActionContext(project, window, selection, k.meta)
                        k.action?.invoke(ctx)
                    } catch (t: Throwable) {
                        logger.warn("Menu action '{}' failed", k.id, t)
                    }
                }

                if (hasChildren && k.kind != MenuItemKind.ACTION) {
                    val submenu = QMenu(k.title, window)
                    submenu.addAction(action)
                    buildMenu(submenu, k.id)
                    parent.addMenu(submenu)
                } else {
                    parent.addAction(action)
                }
            }
        }

        val topSorted = roots.sortedWith(compareBy({ it.order }, { it.title }))
        for (top in topSorted) {
            if (!top.visible) continue
            when (top.kind) {
                MenuItemKind.WIDGET -> {
                    val ctx = MenuActionContext(project, window, selection, top.meta)
                    val widget = top.widgetFactory?.invoke(ctx)
                    if (widget != null) {
                        layout.addWidget(widget)
                    }
                }

                MenuItemKind.ACTION -> {
                    layout.addWidget(makeActionButton(window, top, project, selection))
                }

                MenuItemKind.MENU -> {
                    layout.addWidget(makeMenuButton(window, top, children, project, selection))
                }

                MenuItemKind.SEPARATOR -> {
                    layout.addWidget(makeSeparator())
                }
            }
        }

        layout.addStretch(1)
        update()
    }

    private fun makeSeparator(): QWidget {
        val sep = QFrame()
        sep.frameShape = QFrame.Shape.VLine
        sep.frameShadow = QFrame.Shadow.Sunken
        return sep
    }

    private fun makeActionButton(window: QMainWindow, item: MenuItem, project: ProjectBase?, selection: Any?): QPushButton {
        val btn = QPushButton(item.title)
        btn.isFlat = true
        btn.isEnabled = item.enabled
        btn.toolTip = item.tooltip
        btn.clicked.connect {
            try {
                val ctx = MenuActionContext(project, window, selection, item.meta)
                item.action?.invoke(ctx)
            } catch (t: Throwable) {
                logger.warn("Menu action '{}' failed", item.id, t)
            }
        }
        return btn
    }

    private fun makeMenuButton(
        window: QMainWindow,
        item: MenuItem,
        children: Map<String, List<MenuItem>>,
        project: ProjectBase?,
        selection: Any?
    ): QToolButton {
        val btn = QToolButton()
        btn.text = item.title
        btn.isEnabled = item.enabled
        btn.toolTip = item.tooltip
        btn.popupMode = QToolButton.ToolButtonPopupMode.InstantPopup

        val menu = QMenu(window)
        val kids = children[item.id]
        if (!kids.isNullOrEmpty()) {
            for (child in kids.sortedWith(compareBy({ it.order }, { it.title }))) {
                if (!child.visible) continue
                if (child.kind == MenuItemKind.SEPARATOR) {
                    menu.addSeparator()
                    continue
                }
                val submenuKids = children[child.id]
                if (!submenuKids.isNullOrEmpty() && child.kind != MenuItemKind.ACTION) {
                    val submenu = QMenu(child.title, window)
                    submenuKids.sortedWith(compareBy({ it.order }, { it.title })).forEach { grand ->
                        addActionToMenu(submenu, grand, window, project, selection, children)
                    }
                    menu.addMenu(submenu)
                } else {
                    addActionToMenu(menu, child, window, project, selection, children)
                }
            }
        }

        // Allow top-level action for menu button
        if (item.action != null) {
            val act = QAction(item.title, window)
            act.isEnabled = item.enabled
            item.shortcut?.let { act.setShortcut(it) }
            act.triggered.connect {
                try {
                    val ctx = MenuActionContext(project, window, selection, item.meta)
                    item.action.invoke(ctx)
                } catch (t: Throwable) {
                    logger.warn("Menu action '{}' failed", item.id, t)
                }
            }
            menu.insertAction(menu.actions().firstOrNull(), act)
        }

        btn.setMenu(menu)
        return btn
    }

    private fun addActionToMenu(
        menu: QMenu,
        item: MenuItem,
        window: QMainWindow,
        project: ProjectBase?,
        selection: Any?,
        children: Map<String, List<MenuItem>>
    ) {
        if (!item.visible) return
        if (item.kind == MenuItemKind.SEPARATOR) {
            menu.addSeparator()
            return
        }

        val subKids = children[item.id]
        if (!subKids.isNullOrEmpty() && item.kind != MenuItemKind.ACTION) {
            val submenu = QMenu(item.title, window)
            subKids.sortedWith(compareBy({ it.order }, { it.title })).forEach { sub ->
                addActionToMenu(submenu, sub, window, project, selection, children)
            }
            menu.addMenu(submenu)
            return
        }

        val act = QAction(item.title, window)
        act.isEnabled = item.enabled
        item.shortcut?.let { act.setShortcut(it) }
        item.tooltip?.let { act.toolTip = it }
        act.triggered.connect {
            try {
                val ctx = MenuActionContext(project, window, selection, item.meta)
                item.action?.invoke(ctx)
            } catch (t: Throwable) {
                logger.warn("Menu action '{}' failed", item.id, t)
            }
        }
        menu.addAction(act)
    }

    private fun clearLayout() {
        val count = layout.count()
        for (i in 0 until count) {
            val item = layout.takeAt(0)
            item?.widget()?.let { w ->
                w.hide()
                w.disposeLater()
            }
        }
    }
}
