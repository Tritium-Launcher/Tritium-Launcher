package io.github.footermandev.tritium.io

import java.nio.file.Files

/**
 * Materialize [cache] into [dest] using a symlink when possible, falling back to a copy.
 */
fun linkOrCopyFromCache(cache: VPath, dest: VPath): Boolean {
    if (!cache.exists()) return false
    val cacheSize = cache.sizeOrNull() ?: 0L
    if (cacheSize <= 0L) return false

    val destPath = dest.toJPath()
    if (Files.exists(destPath)) {
        val destSize = dest.sizeOrNull() ?: 0L
        if (destSize > 0L) return true
    }

    if (Files.isSymbolicLink(destPath)) {
        try {
            Files.deleteIfExists(destPath)
        } catch (_: Throwable) {
        }
    } else if (dest.exists()) {
        try {
            dest.delete()
        } catch (_: Throwable) {
        }
    }

    dest.parent().mkdirs()
    return try {
        Files.createSymbolicLink(destPath, cache.toJPath())
        true
    } catch (_: Throwable) {
        try {
            dest.writeBytes(cache.bytesOrNothing())
            true
        } catch (_: Throwable) {
            false
        }
    }
}
