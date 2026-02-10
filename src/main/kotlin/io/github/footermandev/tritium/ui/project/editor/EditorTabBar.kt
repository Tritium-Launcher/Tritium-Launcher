package io.github.footermandev.tritium.ui.project.editor

import io.github.footermandev.tritium.connect
import io.github.footermandev.tritium.m
import io.github.footermandev.tritium.onClicked
import io.github.footermandev.tritium.qs
import io.github.footermandev.tritium.ui.theme.TIcons
import io.github.footermandev.tritium.ui.theme.ThemeMngr
import io.github.footermandev.tritium.ui.theme.qt.icon
import io.github.footermandev.tritium.ui.theme.qt.qtStyle
import io.github.footermandev.tritium.ui.widgets.constructor_functions.hBoxLayout
import io.github.footermandev.tritium.ui.widgets.constructor_functions.toolButton
import io.github.footermandev.tritium.ui.widgets.constructor_functions.widget
import io.qt.Nullable
import io.qt.core.*
import io.qt.gui.QIcon
import io.qt.gui.QWheelEvent
import io.qt.widgets.*

/**
 * An individual Tab Bar for the Editor.
 * @see EditorTab
 * @see EditorArea
 */
class EditorTabBar(parent: QWidget? = null) : QWidget(parent) {
    var onCurrentChanged:  ((Int) -> Unit)? = null
    var onTabCloseRequest: ((Int) -> Unit)? = null

    private val scrollArea    = QScrollArea(this)
    private val content       = widget()
    private val contentLayout = hBoxLayout(content)
    private val tabWidgets    = ArrayList<EditorTab>()
    private val dropdownBtn   = toolButton()
    private val themeListener: () -> Unit = {
        updateBorderStyle()
        tabWidgets.forEach { it.updateColors() }
    }

    var selectedHoveredTabColor: String = "rgba(255,255,255,0.3)"
        set(value) {
            field = value
            tabWidgets.forEach { it.updateColors() }
        }

    var normalTabColor: String = "transparent"
        set(value) {
            field = value
            tabWidgets.forEach { it.updateColors() }
        }

    var borderColor: String = "rgba(255,255,255,0.1)"
        set(value) {
            field = value
            updateBorderStyle()
        }

    var borderWidth: Int = 1
        set(value) {
            field = value
            updateBorderStyle()
        }

    enum class ScrollBehavior { SmoothPixels, WholeTab; }

    var scrollBehavior: ScrollBehavior = ScrollBehavior.SmoothPixels
    var invertScrollDirection: Boolean = false

    var currentIndex: Int = -1
        private set

    private val tabBarHeight = 32

    init {
        setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
        setFixedHeight(tabBarHeight)

        val mainLayout = hBoxLayout(this) {
            contentsMargins = 0.m
            widgetSpacing = 0
            addWidget(scrollArea)
            addWidget(dropdownBtn)
        }

        scrollArea.apply {
            widgetResizable = true
            setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
            setFixedHeight(tabBarHeight)
            verticalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
            horizontalScrollBarPolicy = Qt.ScrollBarPolicy.ScrollBarAlwaysOff
            frameShape = QFrame.Shape.NoFrame
        }

        contentLayout.apply {
            widgetSpacing = 6
            setContentsMargins(4,2,4,2)
            addStretch()
        }

        dropdownBtn.apply {
            setFixedSize(28, tabBarHeight)
            icon = TIcons.SmallArrowDown.icon
            iconSize = qs(16, 16)
            autoRaise = true
            onClicked { showTabListMenu() }
        }

        ThemeMngr.addListener(themeListener)
        destroyed.connect { ThemeMngr.removeListener(themeListener) }

        content.setFixedHeight(tabBarHeight)
        content.setLayout(contentLayout)
        scrollArea.setWidget(content)

        scrollArea.installEventFilter(object : QObject(scrollArea) {
            override fun eventFilter(
                watched: @Nullable QObject?,
                event: @Nullable QEvent?
            ): Boolean {
                if(event?.type() == QEvent.Type.Wheel) {
                    this@EditorTabBar.wheelEvent(event as QWheelEvent)
                    return true
                }
                return super.eventFilter(watched, event)
            }
        })
    }

    fun addTab(icon: QIcon?, text: String): Int = insertTab(tabWidgets.size, icon, text)

    fun insertTab(index: Int, icon: QIcon?, text: String): Int {
        val idx = index.coerceIn(0, tabWidgets.size)
        val tab = EditorTab(icon, text, this)

        tabWidgets.add(idx, tab)
        contentLayout.insertWidget(idx, tab)

        updateCloseVisibility()

        tab.onClicked = {
            val realIdx = tabWidgets.indexOf(tab)
            if(realIdx >= 0) setCurrentIndex(realIdx)
        }
        tab.onCloseClicked = {
            val realIdx = tabWidgets.indexOf(tab)
            if(realIdx >= 0) onTabCloseRequest?.invoke(realIdx)
        }
        tab.onHoverChanged = { updateCloseVisibility() }

        if(this.currentIndex == -1) {
            setCurrentIndex(0)
        } else {
            content.update()
            content.repaint()
        }

        return idx
    }

    fun removeTab(index: Int) {
        if(index < 0 || index >= tabWidgets.size) return
        val w = tabWidgets.removeAt(index)
        contentLayout.removeWidget(w)
        w.setParent(null)
        w.disposeLater()

        if(tabWidgets.isEmpty()) {
            currentIndex = -1
        } else {
            if(currentIndex == index) {
                val newIdx = (index - 1).coerceAtLeast(0)
                setCurrentIndex(newIdx)
            } else if(currentIndex > index) {
                currentIndex -= 1
            }
        }

        updateCloseVisibility()
        content.update()
        content.repaint()
    }

    fun setTabIconAt(idx: Int, icon: QIcon?) {
        val w = tabWidgets.getOrNull(idx) ?: return
        w.setIcon(icon)
        w.update()
    }

    fun setCurrentIndex(idx: Int) {
        if(idx < 0 || idx >= tabWidgets.size) return
        val prev = currentIndex
        if(prev == idx) {
            makeTabVisible(idx)
            return
        }
        currentIndex = idx
        updateTabSelection(prev, idx)
        onCurrentChanged?.invoke(idx)
        makeTabVisible(idx)
    }

    private fun updateTabSelection(prev: Int, next: Int) {
        if(prev in tabWidgets.indices) tabWidgets[prev].isSelected = false
        if(next in tabWidgets.indices) tabWidgets[next].isSelected = true
        updateCloseVisibility()
    }

    private fun updateCloseVisibility() {
        for(t in tabWidgets) t.showClose = (t.isSelected || t.isHovered)
    }

    private fun makeTabVisible(index: Int, instant: Boolean = false) {
        val w = tabWidgets.getOrNull(index) ?: return

        val run = run@{
            val sb = scrollArea.horizontalScrollBar() ?: return@run
            contentLayout.activate()
            content.adjustSize()

            val tabRect = w.geometry
            val viewRect = scrollArea.viewport()?.rect() ?: return@run
            val viewLeft = sb.value
            val viewRight = viewLeft + viewRect.width()

            val tabLeft = tabRect.x()
            val tabRight = tabRect.x() + tabRect.width()

            val newVal = when {
                tabLeft < viewLeft -> tabLeft
                tabRight > viewRight -> tabRight - viewRect.width()
                else -> return@run
            }.coerceIn(sb.minimum, sb.maximum)

            sb.value = newVal
        }

        if(instant) {
            run()
        } else {
            QTimer.singleShot(0, run)
        }
    }

    private fun showTabListMenu() {
        val menu = QMenu(this)

        for(i in tabWidgets.indices) {
            val tab = tabWidgets[i]
            val action = menu.addAction(tab.getText())!!

            action.icon = tab.getIcon()

            action.triggered.connect { _ ->
                setCurrentIndex(i)
            }
        }

        menu.exec(dropdownBtn.mapToGlobal(QPoint(0, dropdownBtn.height)))
    }

    private var wheelAccumulator = 0

    override fun wheelEvent(e: @Nullable QWheelEvent?) {
        if (e == null) {
            super.wheelEvent(e)
            return
        }

        val pixel = e.pixelDelta
        val angle = e.angleDelta

        var deltaPx = if(pixel.x() != 0 || pixel.y() != 0) {
            pixel.y()
        } else {
            wheelAccumulator += angle.y()
            val steps = wheelAccumulator / 120
            val px = -steps * 24
            if(steps != 0) wheelAccumulator -= steps * 120
            px
        }

        if(invertScrollDirection) deltaPx = -deltaPx

        if(scrollBehavior == ScrollBehavior.WholeTab) {
            deltaPx = scrollByWholeTab(deltaPx)
        }

        if(deltaPx == 0) {
            e.accept()
            return
        }

        val sb = scrollArea.horizontalScrollBar() ?: run {
            e.accept()
            return
        }

        val newVal = (sb.value + deltaPx).coerceIn(sb.minimum, sb.maximum)
        sb.value = newVal

        e.accept()
    }

    private fun scrollByWholeTab(delta: Int): Int {
        if(delta == 0 || tabWidgets.isEmpty()) return 0

        val sb = scrollArea.horizontalScrollBar() ?: return 0
        scrollArea.viewport()?.geometry ?: return 0
        val viewLeft = sb.value

        var targetIdx = -1
        for(i in tabWidgets.indices) {
            val tabRect = tabWidgets[i].geometry
            if(tabRect.x() >= viewLeft) {
                targetIdx = if(delta > 0) {
                    (i + 1).coerceAtMost(tabWidgets.size - 1)
                } else {
                    (i - 1).coerceAtLeast(0)
                }
                break
            }
        }

        if(targetIdx < 0) return 0

        val targetTab = tabWidgets[targetIdx]
        val targetRect = targetTab.geometry

        return targetRect.x() - viewLeft
    }

    private fun updateBorderStyle() {
        styleSheet = qtStyle {
            selector("QScrollArea") {
                border(direction = "left", style = "none")
                border(direction = "right", style = "none")
                border(borderWidth, borderColor, "top", "solid")
                border(borderWidth, borderColor, "bottom", "solid")
                background("transparent")
            }
            selector("QToolButton") {
                border()
                padding(0)
                margin(0)
            }
        }.toStyleSheet()
    }

    val count: Int get() = tabWidgets.size

    fun setTabText(idx: Int, text: String) {
        tabWidgets.getOrNull(idx)?.setText(text)
    }

    fun tabText(idx: Int): String? = tabWidgets.getOrNull(idx)?.getText()

    fun tabIcon(idx: Int): QIcon? = tabWidgets.getOrNull(idx)?.getIcon()

    fun allTabTexts(): List<String> = tabWidgets.map { it.getText() }

    fun findTabByText(text: String): Int = tabWidgets.indexOfFirst { it.getText() == text }
}
