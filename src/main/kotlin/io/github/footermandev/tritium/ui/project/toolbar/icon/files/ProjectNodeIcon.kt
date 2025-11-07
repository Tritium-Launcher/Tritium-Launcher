package io.github.footermandev.tritium.ui.project.toolbar.icon.files

//class ProjectNodeIcon: FileIconProvider {
//
//    override fun accepts(
//        file: File,
//        ctx: FileContext
//    ): Boolean {
//        val projectDir = File(ProjectMngr.activeProject!!.path).name
//        mainLogger.info(projectDir)
//        return file.isDirectory && file.name == projectDir
//    }
//
//    override fun iconFor(
//        file: File,
//        ctx: FileContext
//    ): FlatSVGIcon = TIcons.Fabric //TODO: Needs to be able to detect the mod loader and apply the icon accordingly.
//}