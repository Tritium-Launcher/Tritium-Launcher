package io.github.footermandev.tritium.ui.helpers

import io.qt.core.QTimer
import io.qt.widgets.QApplication

internal fun runOnGuiThread(action: () -> Unit) {
    val app = QApplication.instance()
    if(app == null) {
        try { action() } catch (_: Throwable) {}
        return
    }

    QTimer.singleShot(0) { try { action() } catch (_: Throwable) {} }
}