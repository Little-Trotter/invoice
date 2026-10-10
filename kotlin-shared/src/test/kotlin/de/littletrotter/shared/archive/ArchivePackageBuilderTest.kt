package de.littletrotter.shared.archive

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.util.HexFormat

/**
 * [ArchivePackageBuilder]: manifest content, hash-file format, signature verify round-trip,
 * deterministic packaging, and the builder's own input-validation guards. Draft-invoice/issued-
 * document gating is [de.littletrotter.xpdf.impl.PdfExportServiceImpl]'s own concern (kotlin-xpdf,
 * not this document-agnostic builder) — covered by that module's own tests instead.
 */
class ArchivePackageBuilderTest {

    private lateinit var privateKey: PrivateKey
    private lateinit var publicKey: PublicKey
    private lateinit var signer: ArchiveSigner
    private lateinit var fingerprint: String

    @BeforeEach
    fun setup() {
        val keyPair = Ed25519Keys.generateKeyPair()
        privateKey = keyPair.private
        publicKey = keyPair.public
        fingerprint = Ed25519Keys.fingerprint(publicKey)
        signer = Ed25519ArchiveSigner(privateKey, fingerprint)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Unpacks a built tar.gz into a plain path -> bytes map, so assertions can read individual
     *  entries without every test hand-rolling Commons Compress plumbing. */
    private fun unpack(tarGzBytes: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        GzipCompressorInputStream(ByteArrayInputStream(tarGzBytes)).use { gzipIn ->
            TarArchiveInputStream(gzipIn).use { tarIn ->
                var entry = tarIn.nextEntry
                while (entry != null) {
                    entries[entry.name] = tarIn.readAllBytes()
                    entry = tarIn.nextEntry
                }
            }
        }
        return entries
    }

    private fun twoPayloads() = listOf(
        ArchivePayload("re-2026-0001.pdf", "pdf bytes".toByteArray()),
        ArchivePayload("re-2026-0001.xml", "<xml/>".toByteArray()),
    )

    private fun manifestFields() = mapOf(
        "invoiceNumber" to "RE-2026-0001",
        "typeCode" to "380",
        "timestamp" to "2026-09-04T12:00:00Z",
        "jobId" to "job-42",
        "actor" to "system",
    )

    // ── package layout / original/ + .sha256 format ─────────────────────────────────────────

    @Test
    fun `RS-9 - build writes every payload under original with its exact bytes`() {
        val pkg = ArchivePackageBuilder.build(twoPayloads(), manifestFields(), signer)
        val entries = unpack(pkg.bytes)

        assertThat(entries["original/re-2026-0001.pdf"]).isEqualTo("pdf bytes".toByteArray())
        assertThat(entries["original/re-2026-0001.xml"]).isEqualTo("<xml/>".toByteArray())
    }

    @Test
    fun `RS-9 - the sha256 file under original is sha256sum -c format - hex, two spaces, filename`() {
        val payloads = twoPayloads()
        val pkg = ArchivePackageBuilder.build(payloads, manifestFields(), signer)
        val entries = unpack(pkg.bytes)

        val expectedHash = sha256Hex(payloads[0].bytes)
        val line = String(entries.getValue("original/re-2026-0001.pdf.sha256"), Charsets.UTF_8)
        assertThat(line).isEqualTo("$expectedHash  re-2026-0001.pdf\n")
    }

    @Test
    fun `RS-9 - every original file and manifest json each get their own detached signature under signed`() {
        val pkg = ArchivePackageBuilder.build(twoPayloads(), manifestFields(), signer)
        val entries = unpack(pkg.bytes)

        assertThat(entries.keys).containsExactlyInAnyOrder(
            "manifest.json",
            "original/re-2026-0001.pdf",
            "original/re-2026-0001.pdf.sha256",
            "original/re-2026-0001.xml",
            "original/re-2026-0001.xml.sha256",
            "signed/re-2026-0001.pdf.sig",
            "signed/re-2026-0001.pdf.sha256.sig",
            "signed/re-2026-0001.xml.sig",
            "signed/re-2026-0001.xml.sha256.sig",
            "signed/manifest.json.sig",
        )
    }

    // ── signature verify round-trip ──────────────────────────────────────────────────────────

    @Test
    fun `RS-9 - every signed file verifies against the public key, over the exact original bytes`() {
        val payloads = twoPayloads()
        val pkg = ArchivePackageBuilder.build(payloads, manifestFields(), signer)
        val entries = unpack(pkg.bytes)

        assertThat(Ed25519Keys.verify(publicKey, entries.getValue("manifest.json"), entries.getValue("signed/manifest.json.sig"))).isTrue()
        payloads.forEach { payload ->
            val original = entries.getValue("original/${payload.filename}")
            val sig = entries.getValue("signed/${payload.filename}.sig")
            assertThat(Ed25519Keys.verify(publicKey, original, sig))
                .withFailMessage("signature for ${payload.filename} must verify against the original bytes")
                .isTrue()

            val hashFile = entries.getValue("original/${payload.filename}.sha256")
            val hashSig = entries.getValue("signed/${payload.filename}.sha256.sig")
            assertThat(Ed25519Keys.verify(publicKey, hashFile, hashSig)).isTrue()
        }
    }

    @Test
    fun `RS-9 - a signature does not verify against a different key's public half`() {
        val pkg = ArchivePackageBuilder.build(twoPayloads(), manifestFields(), signer)
        val entries = unpack(pkg.bytes)
        val otherPublicKey = Ed25519Keys.generateKeyPair().public

        assertThat(Ed25519Keys.verify(otherPublicKey, entries.getValue("manifest.json"), entries.getValue("signed/manifest.json.sig"))).isFalse()
    }

    // ── manifest content ──────────────────────────────────────────────────────────────────────

    @Test
    fun `RS-9 - manifest json carries every caller field plus files, algorithm and keyFingerprint`() {
        val payloads = twoPayloads()
        val pkg = ArchivePackageBuilder.build(payloads, manifestFields(), signer)
        val manifest = ObjectMapper().readTree(unpack(pkg.bytes).getValue("manifest.json"))

        assertThat(manifest.get("invoiceNumber").asText()).isEqualTo("RE-2026-0001")
        assertThat(manifest.get("typeCode").asText()).isEqualTo("380")
        assertThat(manifest.get("timestamp").asText()).isEqualTo("2026-09-04T12:00:00Z")
        assertThat(manifest.get("jobId").asText()).isEqualTo("job-42")
        assertThat(manifest.get("actor").asText()).isEqualTo("system")
        assertThat(manifest.get("algorithm").asText()).isEqualTo("Ed25519")
        assertThat(manifest.get("keyFingerprint").asText()).isEqualTo(fingerprint)
        assertThat(manifest.get("files").get("re-2026-0001.pdf").asText()).isEqualTo(sha256Hex(payloads[0].bytes))
        assertThat(manifest.get("files").get("re-2026-0001.xml").asText()).isEqualTo(sha256Hex(payloads[1].bytes))
    }

    // ── ArchivePackage.sha256 ────────────────────────────────────────────────────────────────

    @Test
    fun `RS-9 - ArchivePackage sha256 matches the actual digest of its own bytes`() {
        val pkg = ArchivePackageBuilder.build(twoPayloads(), manifestFields(), signer)

        assertThat(pkg.sha256).isEqualTo(sha256Hex(pkg.bytes))
    }

    // ── determinism ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `RS-9 - two builds of the same input produce byte-identical tar gz output`() {
        val payloads = twoPayloads()
        val fields = manifestFields()

        val first = ArchivePackageBuilder.build(payloads, fields, signer)
        val second = ArchivePackageBuilder.build(payloads, fields, signer)

        assertThat(second.bytes).isEqualTo(first.bytes)
        assertThat(second.sha256).isEqualTo(first.sha256)
    }

    @Test
    fun `RS-9 - build is insensitive to the caller's payload ordering - sorted internally`() {
        val payloads = twoPayloads()
        val fields = manifestFields()

        val forward = ArchivePackageBuilder.build(payloads, fields, signer)
        val reversed = ArchivePackageBuilder.build(payloads.reversed(), fields, signer)

        assertThat(reversed.bytes).isEqualTo(forward.bytes)
    }

    @Test
    fun `RS-9 - a different payload byte changes the output`() {
        val fields = manifestFields()
        val first = ArchivePackageBuilder.build(twoPayloads(), fields, signer)
        val mutated = listOf(
            ArchivePayload("re-2026-0001.pdf", "different bytes".toByteArray()),
            ArchivePayload("re-2026-0001.xml", "<xml/>".toByteArray()),
        )

        val second = ArchivePackageBuilder.build(mutated, fields, signer)

        assertThat(second.bytes).isNotEqualTo(first.bytes)
        assertThat(second.sha256).isNotEqualTo(first.sha256)
    }

    // ── input validation ─────────────────────────────────────────────────────────────────────

    @Test
    fun `RS-9 - build rejects an empty payload list`() {
        assertThatThrownBy { ArchivePackageBuilder.build(emptyList(), manifestFields(), signer) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `RS-9 - build rejects two payloads sharing the same filename`() {
        val duplicated = listOf(
            ArchivePayload("same.pdf", "a".toByteArray()),
            ArchivePayload("same.pdf", "b".toByteArray()),
        )

        assertThatThrownBy { ArchivePackageBuilder.build(duplicated, manifestFields(), signer) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `RS-9 - build rejects manifestFields using a reserved field name`() {
        val fieldsWithCollision = manifestFields() + mapOf("algorithm" to "not-allowed")

        assertThatThrownBy { ArchivePackageBuilder.build(twoPayloads(), fieldsWithCollision, signer) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
