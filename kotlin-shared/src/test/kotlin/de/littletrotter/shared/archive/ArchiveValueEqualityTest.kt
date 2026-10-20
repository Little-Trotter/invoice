package de.littletrotter.shared.archive

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [ArchivePayload] and [ArchivePackage] both override `equals`/`hashCode` to compare their
 * `ByteArray` property by content rather than reference. [ArchivePackageBuilderTest] exercises the
 * builder end to end but never calls these overrides directly; this class pins every branch of
 * both (reference equality, type check, the scalar field, and the byte-array content check).
 */
class ArchiveValueEqualityTest {

    // ── ArchivePayload ───────────────────────────────────────────────────────────────────────

    @Test
    fun `ArchivePayload equals itself by reference`() {
        val payload = ArchivePayload("a.pdf", "bytes".toByteArray())

        assertThat(payload).isEqualTo(payload)
    }

    @Test
    fun `ArchivePayload is not equal to null or a different type`() {
        val payload = ArchivePayload("a.pdf", "bytes".toByteArray())

        assertThat(payload).isNotEqualTo(null)
        assertThat(payload as Any).isNotEqualTo("a.pdf")
    }

    @Test
    fun `ArchivePayload with the same filename and different content bytes are not equal`() {
        val first = ArchivePayload("a.pdf", "one".toByteArray())
        val second = ArchivePayload("a.pdf", "two".toByteArray())

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `ArchivePayload with different filenames and the same content bytes are not equal`() {
        val first = ArchivePayload("a.pdf", "same".toByteArray())
        val second = ArchivePayload("b.pdf", "same".toByteArray())

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `ArchivePayload with the same filename and content-equal but distinct byte arrays are equal`() {
        val first = ArchivePayload("a.pdf", "same content".toByteArray())
        val second = ArchivePayload("a.pdf", "same content".toByteArray())

        assertThat(first.bytes).isNotSameAs(second.bytes)
        assertThat(first).isEqualTo(second)
        assertThat(first.hashCode()).isEqualTo(second.hashCode())
    }

    // ── ArchivePackage ───────────────────────────────────────────────────────────────────────

    @Test
    fun `ArchivePackage equals itself by reference`() {
        val pkg = ArchivePackage("bytes".toByteArray(), "sha-1")

        assertThat(pkg).isEqualTo(pkg)
    }

    @Test
    fun `ArchivePackage is not equal to null or a different type`() {
        val pkg = ArchivePackage("bytes".toByteArray(), "sha-1")

        assertThat(pkg).isNotEqualTo(null)
        assertThat(pkg as Any).isNotEqualTo("sha-1")
    }

    @Test
    fun `ArchivePackage with the same sha256 and different content bytes are not equal`() {
        val first = ArchivePackage("one".toByteArray(), "sha-shared")
        val second = ArchivePackage("two".toByteArray(), "sha-shared")

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `ArchivePackage with different sha256 and the same content bytes are not equal`() {
        val first = ArchivePackage("same".toByteArray(), "sha-a")
        val second = ArchivePackage("same".toByteArray(), "sha-b")

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `ArchivePackage with the same sha256 and content-equal but distinct byte arrays are equal`() {
        val first = ArchivePackage("same content".toByteArray(), "sha-shared")
        val second = ArchivePackage("same content".toByteArray(), "sha-shared")

        assertThat(first.bytes).isNotSameAs(second.bytes)
        assertThat(first).isEqualTo(second)
        assertThat(first.hashCode()).isEqualTo(second.hashCode())
    }
}
