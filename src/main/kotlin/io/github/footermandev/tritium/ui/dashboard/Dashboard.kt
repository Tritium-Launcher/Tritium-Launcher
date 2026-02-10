package io.github.footermandev.tritium.ui.dashboard

import io.github.footermandev.tritium.*
import io.github.footermandev.tritium.ui.theme.TColors
import io.github.footermandev.tritium.ui.theme.TIcons
import io.github.footermandev.tritium.ui.theme.qt.setThemedStyle
import io.github.footermandev.tritium.ui.widgets.constructor_functions.hBoxLayout
import io.github.footermandev.tritium.ui.widgets.constructor_functions.qWidget
import io.github.footermandev.tritium.ui.widgets.constructor_functions.vBoxLayout
import io.qt.core.Qt
import io.qt.gui.QIcon
import io.qt.widgets.*
import java.awt.Color

/**
 * The main window for viewing Projects, Accounts, Themes and Settings.
 */
class Dashboard internal constructor() : QMainWindow() {
    private object DashboardBackground

    companion object {
        var I: Dashboard? = null

        @JvmStatic
        fun createAndShow() {
            val win = Dashboard()
            win.show()
            I = win
        }

        internal val logger = logger()
        internal val bgDashboardLogger = logger(DashboardBackground::class)
    }

    private val stackedWidget = QStackedWidget().apply {
        objectName = "dashboardStack"
        frameShape = QFrame.Shape.NoFrame
        lineWidth = 0
    }
    private var selectedButton: QPushButton? = null

    init {
        windowTitle = "Tritium - Dashboard"
        minimumSize = qs(650, 400)
        maximumSize = qs(650, 400)
        isWindowModified = false
        windowIcon = QIcon(TIcons.Tritium)

        val central = qWidget {
            objectName = "dashboard"
        }

        val mainLayout = hBoxLayout(central).apply {
            setSpacing(0)
            setContentsMargins(0, 0, 0, 0)
        }

        val leftWidget = qWidget {
            objectName = "dashboardNav"
            setContentsMargins(0, 0, 0, 0)
        }

        stackedWidget.setContentsMargins(0, 0, 0, 0)

        val leftLayout = vBoxLayout(leftWidget)
        leftLayout.contentsMargins = 10.m
        leftLayout.widgetSpacing = 0

        val projectsBtn = createNavBtn("Projects")
        val accountBtn  = createNavBtn("Accounts")
        val themesBtn   = createNavBtn("Themes")
        val settingsBtn = createNavBtn("Settings")

        selectedButton = projectsBtn
        projectsBtn.isChecked = true

        projectsBtn.onClicked {
            updateSelectedBtn(projectsBtn)
            stackedWidget.currentIndex = 0
        }

        accountBtn.onClicked {
            updateSelectedBtn(accountBtn)
            stackedWidget.currentIndex = 1
        }

        themesBtn.onClicked {
            updateSelectedBtn(themesBtn)
            stackedWidget.currentIndex = 2
        }

        settingsBtn.onClicked {
            updateSelectedBtn(settingsBtn)
            stackedWidget.currentIndex = 3
        }

        leftLayout.add(projectsBtn, accountBtn, themesBtn, settingsBtn)
        leftLayout.addStretch(1)

        val bottomWidget = qWidget()
        val bottomLayout = vBoxLayout(bottomWidget)
        bottomLayout.widgetSpacing = 10
        bottomLayout.contentsMargins = 0.m

        leftLayout.add(bottomWidget)

        setupNavPanels()

        val divider = QFrame().apply {
            objectName = "dashboardDivider"
            frameShape = QFrame.Shape.NoFrame
            frameShadow = QFrame.Shadow.Plain
            lineWidth = 0
            setAttribute(Qt.WidgetAttribute.WA_StyledBackground, true)
            setFixedWidth(1)
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Expanding)
        }

        mainLayout.addWidget(leftWidget, 0)
        mainLayout.addWidget(divider, 0)
        mainLayout.addWidget(stackedWidget, 1)


        setCentralWidget(central)

        central.setThemedStyle {
            selector("#dashboard") {
                backgroundColor(TColors.Surface0)
            }

            selector("#dashboardNav") {
                backgroundColor(TColors.Surface1)
            }

            selector("#dashboardDivider") {
                backgroundColor(TColors.Surface2)
            }

            selector("#navBtn") {
                borderRadius(4)
                border()
                textAlign("bottom")
                padding(top = 6, bottom = 6)
                background("transparent")
            }
            selector("#navBtn:focus") {
                outlineColor("none")
            }
            selector("QPushButton#navBtn:checked") {
                backgroundColor(TColors.Highlight)
            }
        }
    }

    private fun createNavBtn(label: String): QPushButton {
        val btn = QPushButton(label).apply {
            isCheckable = true
            objectName = "navBtn"
            isEnabled = true
            focusPolicy = Qt.FocusPolicy.StrongFocus
            sizePolicy = QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
            minimumHeight = 20
        }
        return btn
    }

    private fun updateSelectedBtn(newBtn: QPushButton) {
        selectedButton?.isChecked = false
        newBtn.isChecked = true
        selectedButton = newBtn
    }

    private fun qColorToCss(color: Color): String {
        val r = color.red
        val g = color.green
        val b = color.blue
        return "rgb($r,$g,$b)"
    }

    private fun setupNavPanels() {
        // Projects
        val projectsPanel = ProjectsPanel()
        stackedWidget.addWidget(projectsPanel)

        // Accounts
        val accountsPanel = AccountsPanel()
        stackedWidget.addWidget(accountsPanel)

        // Themes
        val themesPanel = ThemesPanel()
        stackedWidget.addWidget(themesPanel)

        // TODO: Settings


    }
}
