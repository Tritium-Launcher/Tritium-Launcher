package io.github.footermandev.tritium.core.project.templates.generation

import org.slf4j.Logger
import java.nio.file.Path

/**
 * Context passed to generator steps during project creation.
 */
data class GeneratorContext(
    val projectRoot: Path,
    val variables: Map<String, String>,
    val logger: Logger,
    val workingDir: Path,
    val snapshotDir: Path
)
