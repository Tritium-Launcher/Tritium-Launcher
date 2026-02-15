package io.github.footermandev.tritium.platform

import io.github.footermandev.tritium.mainLogger
import io.github.footermandev.tritium.toURI
import kotlinx.io.IOException
import org.slf4j.Logger
import java.awt.Desktop
import java.io.File
import java.util.concurrent.TimeUnit

enum class Platform {
    Windows, MacOSX, Linux, Unknown;

    companion object {
        val name: String = System.getProperty("os.name")
        val userHome: String = System.getProperty("user.home")
        val userName: String = System.getProperty("user.name")
        val tempDir: File = File(System.getProperty("java.io.tmpdir"))
        val version: String = System.getProperty("os.version", "unknown")
        val arch: String = System.getProperty("os.arch", "unknown")

        val current = when(name.lowercase()) {
            "windows" -> Windows
            "mac"     -> MacOSX
            "linux"   -> Linux
            else      -> Unknown
        }

        val isWindows = current == Windows
        val isMacOS   = current == MacOSX
        val isLinux   = current == Linux

        fun openBrowser(url: String): Boolean {
            try {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    desktop.browse(url.toURI())
                    return true
                }
            } catch (t: Throwable) {
                mainLogger.warn("Failed to open browser with AWT", t)
            }

            try {
                when(current) {
                    Windows -> {
                        val url = listOf("rundll32", "url.dll,FileProtocolHandler", url)
                        val process = runAndLogProcess(url)
                        if(!process) return false
                    }
                    MacOSX -> {
                        val url = listOf("open", url)
                        val process = runAndLogProcess(url)
                        if(!process) return false
                    }
                    else -> {
                        val candidates = listOf(
                            listOf("/usr/bin/xdg-open", url),
                            listOf("gio", "open", url)
                        )
                        candidates.forEach { cmd ->
                            if(runAndLogProcess(cmd)) {
                                return true
                            }
                        }
                        return false
                    }
                }
            } catch (e: IOException) {
                throw IllegalStateException("Failed to open browser for URL: $url", e)
            }

            return false
        }

        private fun runAndLogProcess(cmd: List<String>): Boolean {
            try {
                mainLogger.info("Running: ${cmd.joinToString(" ")}")
                val pb = ProcessBuilder(cmd)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText()
                val finished = proc.waitFor(5, TimeUnit.SECONDS)
                val exit = if(finished) proc.exitValue() else -1
                mainLogger.info("Exit code: $exit; output: $output")
                return exit == 0
            } catch (e: IOException) {
                mainLogger.warn("Exception running ${cmd.joinToString(" ")}", e)
                return false
            }
        }

        internal fun printSystemDetails(logger: Logger) {
            logger.info("=== SYSTEM ===")
            logger.info("OS: $current")
            logger.info("ARCH: $arch")
            logger.info("VERSION: $version")
            logger.info("=== SYSTEM ===")
        }

        fun String.redactUserHome(): String = replace(userHome, "~/")
        fun String.redactUserName(): String = replace(userName, "****")
    }

    override fun toString(): String {
        return when(current) {
            Windows -> "Windows"
            MacOSX   -> "MacOSX"
            Linux   -> "Linux"
            Unknown -> "Unknown"
        }
    }
}