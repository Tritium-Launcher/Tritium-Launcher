/**
 * IO helpers shared across the project.
 */
package io.github.footermandev.tritium.io

import kotlinx.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Atomically write [data] to [path], creating parent directories when needed.
 *
 * Writes to a temporary file in the same directory and then replaces the target. If the platform
 * does not support atomic moves, falls back to a non-atomic replace.
 */
@Throws(IOException::class)
fun atomicWrite(path: VPath, data: ByteArray) {
    val target = path.toJPath()
    val parent = target.parent ?: throw IOException("No parent for path $path")
    Files.createDirectories(parent)
    val tmp = parent.resolve(".tmp-${System.nanoTime()}-${target.fileName}")
    try {
        Files.write(tmp, data)
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        try {
            Files.deleteIfExists(tmp)
        } catch (_: Exception) {
        }
    }
}
