package io.github.footermandev.tritium.core.project

interface ProjectMngrListener {
    fun onProjectCreated(project: ProjectBase)
    fun onProjectOpened(project: ProjectBase)
    fun onProjectDeleted(project: ProjectBase)
    fun onProjectUpdated(project: ProjectBase)
    fun onProjectsFinishedLoading(projects: List<ProjectBase>)
    fun onProjectFailedToGenerate(project: ProjectBase, errorMsg: String, exception: Exception?)
}