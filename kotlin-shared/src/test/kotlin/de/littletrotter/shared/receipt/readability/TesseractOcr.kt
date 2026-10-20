package de.littletrotter.shared.receipt.readability

import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.File
import java.util.UUID

/**
 * Runs `tesseract` inside a pinned container image, so neither developer machines nor CI need a
 * local binary. The container starts lazily on first use, is shared by all callers of the JVM and
 * is removed by the Testcontainers reaper on exit. Test code only, never part of the runtime.
 */
object TesseractOcr {

    private const val IMAGE = "jitesoft/tesseract-ocr:5.5.0"
    private const val OCR_DPI = 300
    private const val WORK_DIR = "/tmp"

    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse(IMAGE))
            .withCreateContainerCmdModifier { it.withEntrypoint("sleep") }
            .withCommand("infinity")
            .also { it.start() }
    }

    /** Recognised text of the image file at [path]; the image is expected at [OCR_DPI] or upscaled to it by the caller. */
    fun recognise(path: File): String {
        val remote = "$WORK_DIR/ocr-${UUID.randomUUID()}.png"
        container.copyFileToContainer(MountableFile.forHostPath(path.absolutePath), remote)
        try {
            val result = container.execInContainer(
                "env", "OMP_THREAD_LIMIT=1", "tesseract", remote, "stdout", "-l", "eng", "--psm", "6", "--dpi", OCR_DPI.toString(),
            )
            check(result.exitCode == 0) { "tesseract failed (${result.exitCode}): ${result.stderr}" }
            return result.stdout
        } finally {
            container.execInContainer("rm", "-f", remote)
        }
    }

    /** Share of [truth] characters (whitespace ignored) reproduced by [recognised], from the Levenshtein distance; 0..1. */
    fun accuracy(truth: String, recognised: String): Double {
        val a = truth.filterNot { it.isWhitespace() }
        val b = recognised.filterNot { it.isWhitespace() }
        if (a.isEmpty()) return 1.0
        return (1.0 - levenshtein(a, b).toDouble() / a.length).coerceAtLeast(0.0)
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
