package io.github.footermandev.tritium.ui.widgets

import io.github.footermandev.tritium.platform.Platform
import io.qt.Nullable
import io.qt.core.QEvent
import io.qt.core.Qt
import io.qt.gui.QCursor
import io.qt.widgets.QLabel
import io.qt.widgets.QWidget

class BrowseLabel(
    private val url: String,
    parent: QWidget? = null
): QLabel(parent) {

    init {
        text = "<a href=\"$url\">$url</a>"
        setTextInteractionFlags(
            Qt.TextInteractionFlag.TextBrowserInteraction
        )
        openExternalLinks = false
        cursor = QCursor(Qt.CursorShape.PointingHandCursor)
    }

    override fun event(e: @Nullable QEvent?): Boolean {
        if(e?.type() == QEvent.Type.MouseButtonRelease) {
            Platform.openBrowser(url)
            return true
        }
        return super.event(e)
    }
}