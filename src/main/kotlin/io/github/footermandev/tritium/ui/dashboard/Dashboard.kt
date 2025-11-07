package io.github.footermandev.tritium.ui.dashboard

import io.github.footermandev.tritium.*
import io.github.footermandev.tritium.ui.theme.ColorPart
import io.github.footermandev.tritium.ui.theme.TColors
import io.github.footermandev.tritium.ui.theme.TIcons
import io.github.footermandev.tritium.ui.theme.applyThemeStyle
import io.qt.core.Qt
import io.qt.gui.QIcon
import io.qt.widgets.*
import java.awt.Color

val bgDashboardLogger = logger("Dashboard::Background")
val dashboardLogger   = logger("Dashboard")

class Dashboard : QMainWindow() {

    private val logger = logger()

    companion object {
        var I: Dashboard? = null

        @JvmStatic
        fun createAndShow() {
            val win = Dashboard()
            win.show()
            I = win
        }
    }

    private val stackedWidget = QStackedWidget()
    private var selectedButton: QPushButton? = null

    init {
        windowTitle = "Tritium - Dashboard"
        minimumSize = qs(650, 390)
        maximumSize = qs(650, 390)
        isWindowModified = false
        windowIcon = QIcon(resourceIcon("icons/tritium.png", javaClass.classLoader)!!)

        try {
            windowIcon = QIcon(TIcons.Tritium)
        } catch (t: Throwable) {
            logger.debug("Could not set window icon: ${t.message}")
        }

        val central = QWidget()
        central.applyThemeStyle("Dashboard.NavBg", ColorPart.Bg)

        val mainLayout = QHBoxLayout(central)
        mainLayout.widgetSpacing = 0
        mainLayout.contentsMargins = 0.m

        val leftWidget = QWidget()
        leftWidget.applyThemeStyle("Dashboard.NavBg", ColorPart.Bg)

        val leftLayout = QVBoxLayout(leftWidget)
        leftLayout.contentsMargins = 10.m
        leftLayout.widgetSpacing = 0


        val projectsBtn = createNavBtn("Projects")
        val accountBtn = createNavBtn("Account")
        val settingsBtn = createNavBtn("Settings")
        val themesBtn = createNavBtn("Themes")

        selectedButton = projectsBtn
        updateBtnAppearance(projectsBtn, true)

        projectsBtn.onClicked {
            updateSelectedBtn(projectsBtn)
            stackedWidget.currentIndex = 0
        }

        accountBtn.onClicked {
            updateSelectedBtn(accountBtn)
            stackedWidget.currentIndex = 1
        }

        settingsBtn.onClicked {
            updateSelectedBtn(settingsBtn)
            stackedWidget.currentIndex = 2
        }

        themesBtn.onClicked {
            updateSelectedBtn(themesBtn)
            stackedWidget.currentIndex = 3
        }

        leftLayout.add(projectsBtn, accountBtn, settingsBtn, themesBtn)
        leftLayout.addStretch(1)

        val bottomWidget = QWidget()
        val bottomLayout = QVBoxLayout(bottomWidget)
        bottomLayout.widgetSpacing = 10
        bottomLayout.contentsMargins = 0.m

        leftLayout.add(bottomWidget)

        stackedWidget.addWidget(NewProjectsPanel())
        stackedWidget.addWidget(AccountPanel())
        stackedWidget.addWidget(SettingsPanelQt())
        stackedWidget.addWidget(ThemesPanel())

        stackedWidget.frameShape = QFrame.Shape.NoFrame
        stackedWidget.lineWidth = 0
        stackedWidget.applyThemeStyle("Dashboard.ProjectsBg", ColorPart.Bg)

        mainLayout.addWidget(leftWidget, 0)
        mainLayout.addWidget(stackedWidget, 1)

        setCentralWidget(central)
    }

    private fun createNavBtn(label: String): QPushButton {
        val btn = QPushButton(label)
        btn.isEnabled = true
        btn.focusPolicy = Qt.FocusPolicy.StrongFocus
        QSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)

        // language=qss
        btn.styleSheet = """
            QPushButton {
                border-radius: 4px;
                border: none;
                text-align: left;
                padding: 6px 12px;
                color: white;
                background: transparent;
            }
            QPushButton:focus { outline: none; }
        """.trimIndent()
        return btn
    }

    private fun updateSelectedBtn(newBtn: QPushButton) {
        selectedButton?.let { updateBtnAppearance(it, false) }
        updateBtnAppearance(newBtn, true)
        selectedButton = newBtn
    }

    private fun updateBtnAppearance(btn: QPushButton, isSelected: Boolean) {
        if(isSelected) {
            //language=qss
            btn.styleSheet = """
                QPushButton {
                    border-radius: 4px;
                    border: none;
                    text-align: left;
                    padding: 6px 12px;
                    color: white;
                    background: ${TColors.Accent};
                }
            """.trimIndent()
        } else {
            //language=qss
            btn.styleSheet = """
                QPushButton {
                    border-radius: 4px;
                    border: none;
                    text-align: left;
                    padding: 6px 12px;
                    color: white;
                    background: transparent;
                }
            """.trimIndent()
        }
    }

    private fun qColorToCss(color: Color): String {
        val r = color.red
        val g = color.green
        val b = color.blue
        return "rgb($r,$g,$b)"
    }
}

class SettingsPanelQt : QWidget() {
    init {
        val layout = QVBoxLayout(this)
        val label = QPushButton("Settings - port your SettingsPanel UI here")
        label.isEnabled = false
        layout.addWidget(label)
    }
}