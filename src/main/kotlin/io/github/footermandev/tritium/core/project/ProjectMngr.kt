package io.github.footermandev.tritium.core.project

import io.github.footermandev.tritium.core.project.template.TemplateDescriptor
import io.github.footermandev.tritium.core.project.template.TemplateRegistry
import io.github.footermandev.tritium.fromTR
import io.github.footermandev.tritium.logger
import io.github.footermandev.tritium.ui.theme.TIcons
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Path
import java.util.prefs.Preferences

object ProjectMngr {
    private val logger = logger()
    private val listeners = mutableListOf<ProjectMngrListener>()
    private val _projects = mutableListOf<ProjectBase>()
    val projects: List<ProjectBase>
        get() {
            if(_projects.isEmpty()) {
                getProjectsFromBaseDir()
            }
            return _projects
        }
    var activeProject: ProjectBase? = null
    val projectsDir = File(fromTR(), "projects")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun addListener(listener: ProjectMngrListener) { listeners.add(listener) }
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

    private fun loadProjectFromDir(dir: File): ProjectBase? {
        val metaFile = File(dir, "trmeta.json")
        if(!metaFile.exists()) return null

        val rootElement = try {
            json.parseToJsonElement(metaFile.readText())
        } catch (e: Exception) {
            logger.error("Failed to parse trmeta.json for ${dir.path}", e)
            return null
        }

        if(rootElement !is JsonObject) {
            logger.error("trmeta.json root is not an object in ${dir.path}")
        }

        val jo = rootElement as? JsonObject ?: run {
            logger.error("'trmeta.json' root is not a JSON object in ${dir.path}")
            return null
        }

        val typeId = jo["type"]?.jsonPrimitive?.contentOrNull ?: run {
            logger.warn("'trmeta.json' missing type in ${dir.path}")
            return null
        }

        val name = jo["name"]?.jsonPrimitive?.contentOrNull ?: run {
            logger.warn("'trmeta.json' missing name in ${dir.path}")
            return@run "Unknown"
        }

        val icon: String = jo["icon"]?.jsonPrimitive?.contentOrNull ?: run {
            logger.warn("'trmeta.json' missing icon in ${dir.path}; applying default")
            return@run TIcons.defaultProjectIcon
        }

        val schemaVersion = jo["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1
        val metaElem = jo["meta"] ?: JsonObject(emptyMap())

        val descriptor = TemplateRegistry.get(typeId)
        return if (descriptor != null) {
            try {
                val serializer = descriptor.serializer as KSerializer<Any>
                val typed = json.decodeFromJsonElement(serializer, metaElem)
                (descriptor as TemplateDescriptor<Any>).createProjectFromMeta(typed, schemaVersion, dir)
            } catch (e: Exception) {
                logger.error("Failed to decode meta for type=$typeId in ${dir.path}", e)
                ProjectBase(typeId, dir, name, icon, metaElem.jsonObjectOrEmpty())
            }
        } else {
            logger.warn("Unknown project type: $typeId (directory ${dir.path})")
            ProjectBase(typeId, dir, name, icon,metaElem.jsonObjectOrEmpty())
        }
    }

    private fun JsonElement.jsonObjectOrEmpty(): JsonObject = (this as? JsonObject) ?: JsonObject(emptyMap())

    private fun getProjectsFromBaseDir(): MutableList<ProjectBase> {
        _projects.clear()
        logger.info("Loading Projects from ${projectsDir.path}...")
        val dirs = projectsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        for(dir in dirs) {
            val proj = loadProjectFromDir(dir)
            if(proj != null) {
                _projects.add(proj)
                logger.info("Loaded project: ${proj.name} (type=${proj.typeId})")
            }
        }
        notifyFinishedLoading()
        return _projects
    }

    fun getProject(name: String): ProjectBase? {
        return projects.find { it.name == name }
    }

    fun getProject(path: Path): ProjectBase? {
        return projects.find { it.projectDir == path }
    }

    fun openProject(project: ProjectBase) {
        if(activeProject !== project) {
            activeProject = project
            notifyProjectOpened(project)
            logger.info("Opened project {}", project.name)
        }
    }

    fun saveActiveProject() {
        activeProject?.let { project ->
            val prefs = Preferences.userRoot().node("project-mngr")
            prefs.put("active-project", project.projectDir.absolutePath)
            logger.info("Set active project to {}", project.name)
        }
    }

    fun saveProjectAsActive(project: ProjectBase) {
        val prefs = Preferences.userRoot().node("project-mngr")
        prefs.put("active-project", project.projectDir.absolutePath)
        logger.info("Set active project to {}", project.name)
    }

    fun loadActiveProject() {
        val prefs = Preferences.userRoot().node("project-mngr")
        val activeProjectPath = prefs.get("active-project", "")

        if(activeProjectPath.isNotEmpty()) {
            val projectDir = File(activeProjectPath)
            if(projectDir.exists() && projectDir.isDirectory) {
                val project = loadProjectFromDir(projectDir)
                if(project != null) openProject(project)
                else logger.error("Active project trmeta exists but failed to load: $activeProjectPath")
            } else logger.warn("Saved active project directory no longer exists: $activeProjectPath")
        }
    }

    fun refreshProjects(): MutableList<ProjectBase> {
        return getProjectsFromBaseDir()
    }

    fun notifyCreatedExternal(project: ProjectBase) {
        _projects.add(project)
        notifyProjectCreated(project)
    }
}