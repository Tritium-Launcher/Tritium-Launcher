package io.github.footermandev.tritium.ui.project.toolbar.icon

//object FileIconResolver : KoinComponent {
//    private val providers: List<FileIconProvider> = getFromKoin()
//    private val iconCache = mutableMapOf<String, FlatSVGIcon>()
//
//    fun resolve(file: File): FlatSVGIcon {
//        val path = file.absolutePath
//
//        iconCache[path]?.let { return it }
//
//        val ctx = FileContext.fromFile(file)
//        val match = providers.firstOrNull { it.accepts(file, ctx) }
//
//        val icon = match?.iconFor(file, ctx)
//            ?: providers.first { it is DefaultFileIconProvider }.iconFor(file, ctx)
//
//        iconCache[path] = icon
//        return icon
//    }
//
//    fun invalidate(file: File) { iconCache.remove(file.absolutePath) }
//    fun clearAll() { iconCache.clear() }
//}