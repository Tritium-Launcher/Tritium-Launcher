package io.github.footermandev.tritium.ui.logging

import io.github.footermandev.tritium.connect
import io.github.footermandev.tritium.logging.Logs
import io.github.footermandev.tritium.m
import io.github.footermandev.tritium.qs
import io.github.footermandev.tritium.ui.helpers.runOnGuiThread
import io.github.footermandev.tritium.ui.theme.TColors
import io.github.footermandev.tritium.ui.theme.qt.setThemedStyle
import io.github.footermandev.tritium.ui.widgets.constructor_functions.vBoxLayout
import io.qt.gui.QTextCursor
import io.qt.widgets.QDialog
import io.qt.widgets.QLabel
import io.qt.widgets.QPlainTextEdit
import io.qt.widgets.QWidget

/**
 * Live log viewer dialog for `~/tritium/logs/tritium.log`.
 */
class LogDialog(parent: QWidget? = null) : QDialog(parent) {
    private val logPathLabel = QLabel()
    private val logView = QPlainTextEdit()
    private val unsubscribe: () -> Unit

    init {
        objectName = "tritiumLogDialog"
        windowTitle = "Tritium Log"
        modal = false
        resize(qs(1020, 640))
        minimumSize = qs(760, 460)

        logPathLabel.objectName = "tritiumLogPath"
        logPathLabel.text = Logs.currentLogFilePath()

        logView.objectName = "tritiumLogView"
        logView.isReadOnly = true
        logView.lineWrapMode = QPlainTextEdit.LineWrapMode.NoWrap

        vBoxLayout(this) {
            contentsMargins = 8.m
            widgetSpacing = 6
            addWidget(logPathLabel)
            addWidget(logView)
        }

        setThemedStyle {
            selector("#tritiumLogDialog") {
                backgroundColor(TColors.Surface0)
            }
            selector("#tritiumLogPath") {
                color(TColors.Subtext)
                fontSize(11)
            }
            selector("#tritiumLogView") {
                backgroundColor(TColors.Surface1)
                color(TColors.Text)
                border(1, TColors.Surface2)
                borderRadius(4)
            }
        }

        reloadFromDisk()
        unsubscribe = Logs.addEntryListener { entry ->
            runOnGuiThread {
                appendEntry(entry)
            }
        }

        destroyed.connect {
            unsubscribe()
        }
    }

    /**
     * Opens the dialog and refreshes content from the current log file.
     */
    fun openAndFocus() {
        reloadFromDisk()
        show()
        raise()
        activateWindow()
    }

    /**
     * Reloads full log text from disk.
     */
    private fun reloadFromDisk() {
        logPathLabel.text = Logs.currentLogFilePath()
        logView.setPlainText(Logs.readCurrentLog())
        moveCursorToBottom()
    }

    /**
     * Appends a single rendered log entry to the text view.
     */
    private fun appendEntry(entry: String) {
        if (entry.isEmpty()) return
        logView.moveCursor(QTextCursor.MoveOperation.End)
        logView.insertPlainText(entry)
        moveCursorToBottom()
    }

    /**
     * Keeps viewport anchored at the newest entry.
     */
    private fun moveCursorToBottom() {
        logView.moveCursor(QTextCursor.MoveOperation.End)
    }
}
