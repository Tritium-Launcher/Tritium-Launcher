package io.github.footermandev.tritium.ui.project

import kotlinx.serialization.Serializable

@Serializable
data class ProjectUIState internal constructor(
    val tabMode: String = "SINGLE_ROW",
    val openFiles: List<String> = emptyList(),
    val mainWindowState: ByteArray? = null,
    val mainWindowGeometry: ByteArray? = null,
) {
    companion object {
        fun fromParts(tabMode: String, openFiles: List<String>, state: ByteArray?, geom: ByteArray?): ProjectUIState {
            return ProjectUIState(tabMode, openFiles, state, geom)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProjectUIState) return false

        if (tabMode != other.tabMode) return false
        if (openFiles != other.openFiles) return false
        if (!mainWindowState.contentEquals(other.mainWindowState)) return false
        if (!mainWindowGeometry.contentEquals(other.mainWindowGeometry)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = tabMode.hashCode()
        result = 31 * result + openFiles.hashCode()
        result = 31 * result + (mainWindowState?.contentHashCode() ?: 0)
        result = 31 * result + (mainWindowGeometry?.contentHashCode() ?: 0)
        return result
    }
}
