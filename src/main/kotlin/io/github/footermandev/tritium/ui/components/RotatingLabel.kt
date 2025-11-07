package io.github.footermandev.tritium.ui.components

import io.qt.Nullable
import io.qt.gui.QPaintEvent
import io.qt.gui.QPainter
import io.qt.widgets.QLabel
import io.qt.widgets.QWidget

class RotatingLabel(parent: QWidget? = null) : QLabel(parent) {
    var angle = 0.0
        set(value) {
            field = value % 360
            update()
        }

    override fun paintEvent(e: @Nullable QPaintEvent?) {
        val pm = pixmap
        if (pm == null) {
            super.paintEvent(e)
            return
        }
        val p = QPainter(this)
        p.setRenderHint(QPainter.RenderHint.Antialiasing, true)

        val drawAngle = angle % 360.0
        p.translate(width.toDouble() / 2.0, height.toDouble() / 2.0)
        p.rotate(drawAngle)
        val pw = pm.width().toDouble()
        val ph = pm.height().toDouble()
        p.drawPixmap((-pw / 2.0).toInt(), (-ph / 2.0).toInt(), pm)
        p.end()
    }
}