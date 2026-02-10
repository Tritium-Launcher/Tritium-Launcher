package io.github.footermandev.tritium

import io.github.footermandev.tritium.accounts.MicrosoftAuth.attemptAutoSignIn
import io.github.footermandev.tritium.bootstrap.runLowPriorityTasks
import io.github.footermandev.tritium.bootstrap.startHost
import io.github.footermandev.tritium.font.loadFont
import io.github.footermandev.tritium.git.Git
import io.github.footermandev.tritium.platform.Platform
import io.github.footermandev.tritium.ui.dashboard.Dashboard
import io.github.footermandev.tritium.ui.theme.ThemeMngr
import io.github.footermandev.tritium.ui.theme.TritiumProxyStyle
import io.qt.core.QCoreApplication
import io.qt.core.Qt
import io.qt.gui.QFont
import io.qt.gui.QIcon
import io.qt.widgets.QApplication
import io.qt.widgets.QWidget
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.prefs.Preferences

// TODO: Needs some cleanup

internal val mainLogger: Logger = LoggerFactory.getLogger(Main::class.java)

@Volatile
internal var appInstance: QApplication? = null

val TApp: QApplication
    get() = appInstance ?: throw IllegalStateException("QApplication not initialized.")

lateinit var referenceWidget: QWidget

class Main {
    companion object {

        @JvmStatic
        fun main(vararg args: String) {
            mainLogger.info("Starting with args: ${args.joinToString(" ")}")
            Platform.printSystemDetails(mainLogger)

            if (QApplication.instance() == null) QApplication.initialize(args)
            appInstance = QApplication.instance() as QApplication

            QCoreApplication.setAttribute(Qt.ApplicationAttribute.AA_EnableHighDpiScaling, true)
            QCoreApplication.setAttribute(Qt.ApplicationAttribute.AA_UseHighDpiPixmaps, true)


            referenceWidget = QWidget()

            manageArguments(args.toList())

            ThemeMngr.init()

            Git.init()

            val loaders = startHost(TConstants.EXT_DIR)

            attemptAutoSignIn()

            val baseStyle = QApplication.style()
            QApplication.setStyle(TritiumProxyStyle(baseStyle))
            ThemeMngr.setTheme(ThemeMngr.currentThemeId)

            applyStartupFont()

            QApplication.setWindowIcon(QIcon(resourceIcon("icons/tritium.png", TConstants.classLoader)!!))
            QApplication.setDesktopFileName("tritium")
            QApplication.setApplicationName("tritium")

            Dashboard.createAndShow()

            runBlocking {
                runLowPriorityTasks()
            }

            QApplication.exec()
        }

        private fun applyStartupFont() {
            val prefs = Preferences.userRoot().node("/tritium")
            val defaultLoaded = loadFont("/fonts/Inter/InterVariable.ttf")?.let { QFont(it, 10) }

            val savedFamily = prefs.get("globalFontFamily", null)
            val savedSize = prefs.getInt("globalFontSize", -1)
            val useSaved = !savedFamily.isNullOrBlank() && savedSize > 0

            val fontToSet = when {
                useSaved -> QFont(savedFamily, savedSize)
                defaultLoaded != null -> defaultLoaded
                else -> null
            }

            fontToSet?.let { QApplication.setFont(it) }
        }
    }
}
