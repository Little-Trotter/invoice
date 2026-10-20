package de.littletrotter.xpdf.impl

import java.nio.file.Files
import java.nio.file.Path

/**
 * Scratch space for resources that are unpacked from the classpath so that third-party engines
 * (which only accept file URIs) can read them. Every directory lives under one per-process root
 * that is removed on JVM exit, so a crashed or forgotten `close()` never accumulates directories
 * in the OS temp folder across thousands of test JVMs; owners still release their own directory
 * eagerly via [deleteRecursively] as soon as it is no longer needed.
 */
internal object ScratchDirectories {
    private val root: Path by lazy {
        val dir = Files.createTempDirectory("xpdf-scratch-")
        Runtime.getRuntime().addShutdownHook(Thread { deleteRecursively(dir) })
        dir
    }

    fun create(prefix: String): Path = Files.createTempDirectory(root, prefix)

    fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
