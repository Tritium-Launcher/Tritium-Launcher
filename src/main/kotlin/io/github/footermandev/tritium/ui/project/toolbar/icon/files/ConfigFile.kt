package io.github.footermandev.tritium.ui.project.toolbar.icon.files

//@Single
//class ConfigFile: FileIconProvider {
//    override fun accepts(
//        file: File,
//        ctx: FileContext
//    ): Boolean {
//        val proj = ProjectMngr.activeProject!!.path.toPath().name
//        return file.parentFile.name == "config" && file.parentFile.parentFile.name == proj // Checks if the file is in /ProjectName/config
//    }
//
//    override fun iconFor(
//        file: File,
//        ctx: FileContext
//    ): FlatSVGIcon = TIcons.ConfigFile
//}