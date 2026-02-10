package io.github.footermandev.tritium.ui.project

import io.github.footermandev.tritium.connect
import io.github.footermandev.tritium.core.project.ProjectBase
import io.github.footermandev.tritium.logger
import io.github.footermandev.tritium.ui.dashboard.Dashboard
import io.github.footermandev.tritium.ui.helpers.runOnGuiThread
import io.qt.core.QThread
import io.qt.widgets.QApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages project window instances and focuses existing windows when possible.
 */
object ProjectWindows {
    private val logger = logger()

    private val openWindows = ConcurrentHashMap<String, CompletableDeferred<ProjectViewWindow>>()

    /**
     * Open (or focus) a project window for [project].
     *
     * @param closeDashboard When true, closes the dashboard after opening.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun openProject(project: ProjectBase, closeDashboard: Boolean = true) {
        val canonical = project.path.toString().trim()

        val newDeferred = CompletableDeferred<ProjectViewWindow>()
        val prevDeferred = openWindows.putIfAbsent(canonical, newDeferred)

        if(prevDeferred == null) {
            createWindowAsync(project, newDeferred, closeDashboard)
            return
        }

        // If an earlier creation is still pending, cancel it and start fresh to avoid deadlocks.
        if(!prevDeferred.isCompleted) {
            logger.warn("Previous project window creation still pending for '{}', restarting.", project.name)
            prevDeferred.cancel()
            openWindows[canonical] = newDeferred
            createWindowAsync(project, newDeferred, closeDashboard)
            return
        }

        if(prevDeferred.isCancelled) {
            openWindows[canonical] = newDeferred
            createWindowAsync(project, newDeferred, closeDashboard)
            return
        }

        // Otherwise re-show existing window.
        prevDeferred.invokeOnCompletion {
            if(prevDeferred.isCompleted) {
                try {
                    val w = prevDeferred.getCompleted()
                    runOnGuiThread {
                        try {
                            w.show()
                            w.raise()
                            w.activateWindow()
                            if(closeDashboard) {
                                Dashboard.I?.close()
                            }
                        } catch (t: Throwable) {
                            logger.debug("Failed to focus existing {} for '{}'", ProjectViewWindow::class.qualifiedName, project.name, t)
                        }
                    }
                } catch (t: Throwable) {
                    logger.debug("Deferred completed but failed to get window", t)
                }
            }
        }
        return
    }

    private fun createWindowAsync(
        project: ProjectBase,
        deferred: CompletableDeferred<ProjectViewWindow>,
        closeDashboard: Boolean
    ) {
        if (isGuiThread()) {
            createWindow(project, deferred, closeDashboard)
            return
        }

        runOnGuiThread { createWindow(project, deferred, closeDashboard) }
    }

    private fun createWindow(
        project: ProjectBase,
        deferred: CompletableDeferred<ProjectViewWindow>,
        closeDashboard: Boolean
    ) {
        try {
            val window = ProjectViewWindow(project)
            window.show()

            try {
                window.raise()
                window.activateWindow()
            } catch (t: Throwable) {
                logger.debug("Failed to raise / activate window for '{}'", project.name, t)
            }

            deferred.complete(window)

            window.destroyed.connect { openWindows.remove(project.path.toString().trim()) }
            if(closeDashboard) {
                Dashboard.I?.close()
            }
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            openWindows.remove(project.path.toString().trim())
            logger.error("Failed to create {} for '{}'", ProjectViewWindow::class.qualifiedName, project.name, t)
        }
    }

    private fun isGuiThread(): Boolean {
        val app = QApplication.instance() ?: return false
        return QThread.currentThread() == app.thread()
    }
}
