package io.github.footermandev.tritium.core.project

import io.github.footermandev.tritium.TConstants
import io.github.footermandev.tritium.core.project.templates.MigrationRegistry
import io.github.footermandev.tritium.core.project.templates.ProjectFileLoader
import io.github.footermandev.tritium.core.project.templates.TemplateDescriptor
import io.github.footermandev.tritium.core.project.templates.TemplateRegistry
import io.github.footermandev.tritium.extension.core.CoreSettingValues
import io.github.footermandev.tritium.fromTR
import io.github.footermandev.tritium.io.VPath
import io.github.footermandev.tritium.logger
import io.github.footermandev.tritium.ui.dashboard.Dashboard
import io.github.footermandev.tritium.ui.project.ProjectWindows
import io.github.footermandev.tritium.ui.theme.TIcons
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.prefs.Preferences

/**
 * Central manager for discovering, loading, and tracking projects on disk.
 */
object ProjectMngr {
    private val logger = logger()
    @Volatile var generationActive: Boolean = false

    private val listeners = CopyOnWriteArrayList<ProjectMngrListener>()

    private val _projects = mutableListOf<ProjectBase>()
    val projects: List<ProjectBase>
        get() {
            if(_projects.isEmpty()) {
                getProjectsFromBaseDir(RefreshSource.BACKGROUND)
            }
            return _projects.toList()
        }

    var activeProject: ProjectBase? = null
    val projectsDir = fromTR(TConstants.Dirs.PROJECTS)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * Register a listener for project events.
     */
    fun addListener(listener: ProjectMngrListener) { listeners.add(listener) }
    /**
     * Unregister a listener.
     */
    fun removeListener(listener: ProjectMngrListener) { listeners.remove(listener) }

    private fun notifyProjectCreated(project: ProjectBase) {
        listeners.forEach { it.onProjectCreated(project) }
    }
    private fun notifyProjectFailedToGenerate(project: ProjectBase, errorMsg: String, exception: Exception?) {
        listeners.forEach { it.onProjectFailedToGenerate(project, errorMsg, exception) }
    }
    private fun notifyProjectOpened(project: ProjectBase) {
        listeners.forEach { it.onProjectOpened(project) }
    }
    private fun notifyFinishedLoading() {
        listeners.forEach { it.onProjectsFinishedLoading(_projects.toList()) }
    }

    private fun loadProjectFromDir(dir: VPath): ProjectBase? {
        val trMeta = ProjectFiles.readTrProject(dir) ?: run {
            logger.warn("No trproj.json found in {}", dir)
            return null
        }

        val typeId = trMeta.type
        val name = trMeta.name.ifBlank { "Unknown" }
        val icon = trMeta.icon.ifBlank { TIcons.defaultProjectIcon }
        val schemaVersion = trMeta.schemaVersion
        val metaElem = trMeta.meta.jsonObjectOrEmpty()

        val descriptor = TemplateRegistry.get(typeId)
        if(descriptor is ProjectFileLoader) {
            return try {
                descriptor.loadFromProjectFile(trMeta, dir)
            } catch (e: Exception) {
                logger.error("Failed to load project via ProjectFileLoader for type=$typeId in $dir", e)
                ProjectBase(typeId, dir, name, icon, metaElem.jsonObjectOrEmpty())
            }
        }

        if(descriptor != null) {
            try {
                val serializer = descriptor.serializer as KSerializer<Any>
                val migratedMeta = if(schemaVersion < descriptor.currentSchema) {
                    try {
                        MigrationRegistry.migrate(typeId, schemaVersion, descriptor.currentSchema, metaElem)
                    } catch (t: Throwable) {
                        logger.warn("No migration registered for $typeId from $schemaVersion", t)
                        metaElem
                    }
                } else metaElem
                val typed = json.decodeFromJsonElement(serializer, migratedMeta)
                return (descriptor as TemplateDescriptor<Any>).createProjectFromMeta(typed, descriptor.currentSchema, dir)
            } catch (e: Exception) {
                logger.error("Failed to decode meta for type=$typeId in $dir", e)
                return ProjectBase(typeId, dir, name, icon, metaElem.jsonObjectOrEmpty())
            }
        }

        logger.warn("Unknown project type: $typeId (directory $dir)")
        return ProjectBase(typeId, dir, name, icon, metaElem.jsonObjectOrEmpty())
    }

    /**
     * Load a project from a directory if a project definition exists.
     */
    fun loadProject(dir: VPath): ProjectBase? = loadProjectFromDir(dir)

    private fun JsonElement.jsonObjectOrEmpty(): JsonObject = (this as? JsonObject) ?: JsonObject(emptyMap())

    private fun getProjectsFromBaseDir(source: RefreshSource): MutableList<ProjectBase> {
        if (source == RefreshSource.DASHBOARD && (generationActive || !isDashboardActive())) {
            logger.debug("Skipping project list refresh (source={}, generationActive={}, dashboardActive={})", source, generationActive, isDashboardActive())
            return _projects
        }
        synchronized(_projects) {
            _projects.clear()
            logger.info("Loading Projects from $projectsDir...")
            val dirs = projectsDir.listFiles { it.isDir() }
            for (dir in dirs) {
                val proj = loadProjectFromDir(dir)
                if (proj != null) {
                    _projects.add(proj)
                    logger.info("Loaded project: ${proj.name} (type=${proj.typeId})")
                }
            }
        }
        notifyFinishedLoading()
        return _projects
    }

    /**
     * Find a project by display name.
     */
    fun getProject(name: String): ProjectBase? {
        return projects.find { it.name == name }
    }

    /**
     * Find a project by its root directory path.
     */
    fun getProject(path: VPath): ProjectBase? {
        return projects.find { it.projectDir.toAbsolute() == path.toAbsolute() }
    }

    /**
     * Open a project in the UI and mark it as active.
     */
    fun openProject(project: ProjectBase) {
        logger.info("Loading project {}", project.name)
        val wasDifferent = activeProject !== project
        activeProject = project
        val closeDashboard = CoreSettingValues.closeDashboardOnProjectOpen() && wasDifferent

        try {
            ProjectWindows.openProject(project, closeDashboard = closeDashboard)
        } catch (t: Throwable) {
            logger.debug("Failed to open project", t)
        }

        notifyProjectOpened(project)
    }

    /**
     * Persist the current active project for next app launch.
     */
    fun saveActiveProject() {
        activeProject?.let { project ->
            val prefs = Preferences.userRoot().node("project-mngr")
            prefs.put("active-project", project.projectDir.toAbsolute().toString())
            logger.info("Set active project to {}", project.name)
        }
    }

    /**
     * Persist the given project as active.
     */
    fun saveProjectAsActive(project: ProjectBase) {
        val prefs = Preferences.userRoot().node("project-mngr")
        prefs.put("active-project", project.projectDir.toAbsolute().toString())
        logger.info("Set active project to {}", project.name)
    }

    /**
     * Load the previously active project if it still exists on disk.
     */
    fun loadActiveProject() {
        val prefs = Preferences.userRoot().node("project-mngr")
        val activeProjectPath = prefs.get("active-project", "")

        if(activeProjectPath.isNotEmpty()) {
            val projectDir = VPath.get(activeProjectPath)
            if(projectDir.exists() && projectDir.isDir()) {
                val project = loadProjectFromDir(projectDir)
                if(project != null) openProject(project)
                else logger.error("Active project definition exists but failed to load: $activeProjectPath")
            } else logger.warn("Saved active project directory no longer exists: $activeProjectPath")
        }
    }

    /**
     * Refresh the project list from disk.
     */
    fun refreshProjects(source: RefreshSource = RefreshSource.DASHBOARD): MutableList<ProjectBase> {
        return getProjectsFromBaseDir(source)
    }

    private fun isDashboardActive(): Boolean {
        val dash = Dashboard.I ?: return false
        return dash.isVisible
    }

    enum class RefreshSource {
        DASHBOARD,
        BACKGROUND
    }

    /**
     * Notify listeners that a project was created outside the manager.
     */
    fun notifyCreatedExternal(project: ProjectBase) {
        synchronized(_projects) {
            val existing = _projects.any { it.projectDir.toAbsolute() == project.projectDir.toAbsolute() }
            if(!existing) {
                _projects.add(project)
            } else {
                val idx = _projects.indexOfFirst { it.projectDir.toAbsolute() == project.projectDir.toAbsolute() }
                if(idx >= 0) _projects[idx] = project
            }
        }
        notifyProjectCreated(project)
    }
}
