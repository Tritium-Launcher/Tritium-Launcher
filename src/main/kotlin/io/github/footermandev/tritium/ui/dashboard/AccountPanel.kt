package io.github.footermandev.tritium.ui.dashboard

import io.github.footermandev.tritium.TConstants
import io.github.footermandev.tritium.auth.MicrosoftAuth
import io.github.footermandev.tritium.auth.ProfileMngr
import io.github.footermandev.tritium.loadImageQt
import io.github.footermandev.tritium.m
import io.github.footermandev.tritium.onClicked
import io.qt.Nullable
import io.qt.core.QObject
import io.qt.core.QTimer
import io.qt.gui.QCloseEvent
import io.qt.gui.QPixmap
import io.qt.widgets.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class AccountPanel: QWidget() {
    @Volatile
    private var isLoading: Boolean = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val isRefreshing = AtomicBoolean(false)

    private val mainLayout = QVBoxLayout().apply {
        contentsMargins = 0.m
        widgetSpacing = 10
    }

    init {
        setLayout(mainLayout)

        this.destroyed.connect(this, "onWidgetDestroyed(QObject)")

        ProfileMngr.addListener { _ ->
            isLoading = false
            QTimer.singleShot(0) { refreshUI() }
        }

        QTimer.singleShot(0) { refreshUI() }

        scope.launch {
            bgDashboardLogger.info("Initial profile check started")
            val profile = ProfileMngr.Cache.get()
            if(profile != null) isLoading = false
            QTimer.singleShot(0) { refreshUI() }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onWidgetDestroyed(obj: QObject?) {
        scope.cancel()
    }

    override fun closeEvent(event: @Nullable QCloseEvent?) {
        try {
            scope.cancel()
        } finally {
            super.closeEvent(event)
        }
    }

    private fun clearLayout(layout: QLayout?) {
        val l = layout ?: return
        while(l.count() > 0) {
            val item = l.takeAt(0) ?: continue
            val widget = item.widget()
            if(widget != null) {
                widget.hide()
                widget.setParent(null)
                try { widget.dispose() } catch (_: Throwable) {}
            } else {
                clearLayout(item.layout())
            }
        }
    }

    private fun refreshUI() {
        if(!isRefreshing.compareAndSet(false, true)) return

        scope.launch {
            val profile = ProfileMngr.Cache.get()

            QTimer.singleShot(0) {
                try {
                    clearLayout(mainLayout)

                    if (profile != null) {
                        val row = QWidget()
                        val rowLayout = QHBoxLayout(row)
                        rowLayout.widgetSpacing = 10
                        rowLayout.contentsMargins = 10.m

                        val facePixmap: QPixmap? = try {
                            loadImageQt(TConstants.FACE_URL + profile.id, 100, 100, true)
                        } catch (_: Throwable) {
                            QPixmap()
                        }

                        val faceLabel = QLabel()
                        faceLabel.pixmap = facePixmap ?: QPixmap()
                        rowLayout.addWidget(faceLabel)

                        val nameLabel = QLabel(profile.name)
                        nameLabel.styleSheet = "font-size: 18px;"
                        rowLayout.addWidget(nameLabel, 1)

                        val uuidLabel = QLabel(profile.id)
                        uuidLabel.styleSheet = "font-size: 12px; color: gray;"
                        rowLayout.addWidget(uuidLabel)

                        val signOutBtn = QPushButton("Sign out").apply {
                            onClicked {
                                scope.launch {
                                    MicrosoftAuth().signOut()
                                    isLoading = false
                                    QTimer.singleShot(0) { refreshUI() }
                                }
                            }
                        }

                        rowLayout.addWidget(signOutBtn)
                        mainLayout.addWidget(row)
                    } else {
                        val signInBtn = QPushButton("Sign in")
                        signInBtn.onClicked {
                            dashboardLogger.info("Sign in flow started")
                            scope.launch {
                                MicrosoftAuth().newSignIn { _ ->
                                    QTimer.singleShot(0) {
                                        isLoading = false
                                        refreshUI()
                                    }
                                }
                            }
                        }
                        mainLayout.addWidget(signInBtn)
                    }

                    update()
                    repaint()
                } finally {
                    isRefreshing.set(false)
                }
            }
        }
    }
}