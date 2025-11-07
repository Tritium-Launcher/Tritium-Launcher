package io.github.footermandev.tritium.ui.dashboard

import io.github.footermandev.tritium.connect
import io.github.footermandev.tritium.logger
import io.github.footermandev.tritium.m
import io.github.footermandev.tritium.onClicked
import io.github.footermandev.tritium.ui.theme.ThemeMngr
import io.qt.core.QUrl
import io.qt.core.Qt
import io.qt.gui.QDesktopServices
import io.qt.widgets.*

class ThemesPanel : QWidget() {
    private val logger = logger()
    private val layout = QVBoxLayout()
    private val list = QListWidget()
    private val openFolderBtn = QPushButton("Folder")
    private val refreshBtn = QPushButton("Refresh")
    private val hint = QLabel("Select a Theme to apply it immediately")
    private val listener: () -> Unit = { refreshThemeList() }

    init {
        setLayout(layout)
        layout.contentsMargins = 8.m
        layout.widgetSpacing = 8

        list.selectionMode = QAbstractItemView.SelectionMode.SingleSelection
        list.minimumHeight = 200
        layout.addWidget(list)

        val btnRow = QWidget()
        val btnRowLayout = QHBoxLayout(btnRow)
        btnRowLayout.contentsMargins = 0.m
        btnRowLayout.widgetSpacing = 8
        btnRowLayout.addWidget(openFolderBtn)
        btnRowLayout.addWidget(refreshBtn)
        btnRowLayout.addStretch(1)
        layout.addWidget(btnRow)

        hint.isEnabled = false
        layout.addWidget(hint)
        layout.addStretch(1)

        refreshThemeList()

        val applyThemeFromItem: (QListWidgetItem?) -> Unit = { item ->
            item?.let {
                val id = it.text()
                try {
                    ThemeMngr.setTheme(id)
                } catch (e: Exception) {
                    logger.warn("Failed to set theme '{}': {}", id, e.message)
                }
                refreshSelection()
            }
        }

        list.itemClicked.connect { item: QListWidgetItem? -> applyThemeFromItem(item) }
        list.itemActivated.connect { item: QListWidgetItem? -> applyThemeFromItem(item) }

        list.itemSelectionChanged.connect { refreshSelection() }

        openFolderBtn.onClicked {
            try {
                val themePath = ThemeMngr.userThemesDir
                QDesktopServices.openUrl(QUrl.fromLocalFile(themePath.toString()))
            } catch(e: Exception) {
                logger.warn("Failed to open themes folder: ${e.message}")
            }
        }

        refreshBtn.onClicked { refreshThemeList() }

        ThemeMngr.addListener(listener)

        refreshSelection()
    }

    private fun refreshThemeList() {
        try {
            val ids = ThemeMngr.availableThemeIds()
            val prev = list.currentItem()?.text()
            list.clear()
            for(id in ids) {
                list.addItem(id)
            }
            val target = prev ?: ThemeMngr.currentThemeId
            selectById(target)
        } catch (e: Exception) {
            logger.error("Failed to refresh theme list", e)
        }
    }

    private fun refreshSelection() {
        val current = ThemeMngr.currentThemeId
        selectById(current)
    }

    private fun selectById(id: String) {
        val items = list.findItems(id, Qt.MatchFlag.MatchExactly)
        if(items.isNotEmpty()) list.setCurrentItem(items.first()) else list.setCurrentItem(null)
    }
}