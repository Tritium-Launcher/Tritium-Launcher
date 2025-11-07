package io.github.footermandev.tritium

import com.microsoft.aad.msal4j.SilentParameters
import io.github.footermandev.tritium.TConstants.TR
import io.github.footermandev.tritium.auth.MSAL
import io.github.footermandev.tritium.auth.MicrosoftAuth
import io.github.footermandev.tritium.auth.ProfileMngr
import io.github.footermandev.tritium.koin.Koin
import io.github.footermandev.tritium.service.loadAllServices
import io.github.footermandev.tritium.ui.dashboard.Dashboard
import io.github.footermandev.tritium.ui.theme.ThemeMngr
import io.qt.gui.QIcon
import io.qt.widgets.QApplication
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.logger.slf4jLogger
import org.slf4j.Logger
import org.slf4j.LoggerFactory

val mainLogger: Logger = LoggerFactory.getLogger("$TR::Main")

@Volatile
internal var appInstance: QApplication? = null

val TApp: QApplication
    get() = appInstance ?: throw IllegalStateException("QApplication not initialized.")


class Main {
    companion object {

        @JvmStatic
        fun main(vararg args: String) {
            mainLogger.info("Starting with args: ${args.joinToString(" ")}")

            manageArguments(args.toList())
            mainLogger.info(userHome)

            Koin.app = startKoin {
                slf4jLogger()
                modules(
                )
            }

            attemptAutoSignIn()

            ThemeMngr.init()

            mainLogger.info("Dashboard.NavBg -> ${ThemeMngr.getColorHex("Dashboard.NavBg")}")

            if (QApplication.instance() == null) QApplication.initialize(args)
            appInstance = QApplication.instance() as QApplication

            QApplication.setWindowIcon(QIcon(resourceIcon("icons/tritium.png", TConstants.classLoader)!!))
            QApplication.setDesktopFileName("tritium")
            QApplication.setApplicationName("tritium")

            loadAllServices()


            Dashboard.createAndShow()

            QApplication.exec()


        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun attemptAutoSignIn() {
    GlobalScope.launch {
        while(true) {
            try {
                val accounts = MSAL.app.accounts.await()
                val account = accounts.iterator().asSequence().firstOrNull()
                if (account != null) {
                    val scopes = setOf("XboxLive.signin", "offline_access")
                    val params = SilentParameters.builder(scopes, account).build()
                    val result = MSAL.app.acquireTokenSilently(params).await()

                    val mcToken = MicrosoftAuth().getMCToken(result.accessToken())
                    ProfileMngr.Cache.init(mcToken)
                } else {
                    mainLogger.info("No accounts available.")
                }

                break
            } catch (e: Exception) {
                mainLogger.error("Sign-in failed, will retry in 60s: ${e.message}", e)
                delay(60_000)
            }
        }
    }
}