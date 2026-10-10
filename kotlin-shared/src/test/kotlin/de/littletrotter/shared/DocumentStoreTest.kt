package de.littletrotter.shared

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.assertj.core.api.Assertions.assertThat
import java.time.LocalDate

/**
 * Tests for DocumentStore interface's default implementations.
 * These test the extension methods that handle year-based key structures and fallbacks.
 */
class DocumentStoreTest {

    // A test double that tracks calls to the base methods
    private class TestDocumentStore : DocumentStore {
        val putCalls = mutableListOf<Triple<String, ByteArray, String>>()
        val getCalls = mutableListOf<String>()
        val deleteCalls = mutableListOf<String>()
        var getReturnValue: ByteArray? = null
        var shouldThrowOnGet = false

        override fun put(objectKey: String, data: ByteArray, contentType: String) {
            putCalls.add(Triple(objectKey, data, contentType))
        }

        override fun get(objectKey: String): ByteArray {
            getCalls.add(objectKey)
            if (shouldThrowOnGet) throw RuntimeException("not found")
            return getReturnValue ?: ByteArray(0)
        }

        override fun exists(objectKey: String): Boolean {
            return false
        }

        override fun enableImmutability(objectKey: String) {}

        override fun delete(objectKey: String) {
            deleteCalls.add(objectKey)
        }
    }

    // ── listKeysForYear ────────────────────────────────────────────────────────

    @Test
    fun `listKeysForYear delegates to listObjectKeys with year prefix`() {
        val store = object : DocumentStore {
            override fun put(objectKey: String, data: ByteArray, contentType: String) {}
            override fun get(objectKey: String): ByteArray = ByteArray(0)
            override fun exists(objectKey: String): Boolean = false
            override fun enableImmutability(objectKey: String) {}
            override fun delete(objectKey: String) {}
            override fun listObjectKeys(prefix: String): List<String> {
                assertEquals("documents/2026/", prefix)
                return listOf("documents/2026/invoice-1.pdf", "documents/2026/invoice-2.pdf")
            }
        }

        val result = store.listKeysForYear(2026)

        assertThat(result).containsExactly("documents/2026/invoice-1.pdf", "documents/2026/invoice-2.pdf")
    }

    @Test
    fun `listKeysForYear handles empty result`() {
        val store = object : DocumentStore {
            override fun put(objectKey: String, data: ByteArray, contentType: String) {}
            override fun get(objectKey: String): ByteArray = ByteArray(0)
            override fun exists(objectKey: String): Boolean = false
            override fun enableImmutability(objectKey: String) {}
            override fun delete(objectKey: String) {}
            override fun listObjectKeys(prefix: String): List<String> = emptyList()
        }

        val result = store.listKeysForYear(2026)

        assertThat(result).isEmpty()
    }

    // ── put with LocalDate ─────────────────────────────────────────────────────

    @Test
    fun `put with date builds year-based key and delegates to put(key, data, type)`() {
        val store = TestDocumentStore()
        val date = LocalDate.of(2026, 8, 27)
        val data = byteArrayOf(1, 2, 3)
        val contentType = "application/pdf"

        store.put(date, "invoice.pdf", data, contentType)

        assertEquals(1, store.putCalls.size)
        assertEquals("documents/2026/invoice.pdf", store.putCalls[0].first)
        assertThat(store.putCalls[0].second).containsExactly(1, 2, 3)
        assertEquals(contentType, store.putCalls[0].third)
    }

    @Test
    fun `put with date passes all parameters correctly`() {
        val store = TestDocumentStore()
        val date = LocalDate.of(2025, 1, 15)
        val data = byteArrayOf(5, 10, 15)
        val contentType = "image/png"

        store.put(date, "logo.png", data, contentType)

        assertEquals(1, store.putCalls.size)
        assertEquals("documents/2025/logo.png", store.putCalls[0].first)
        assertEquals(contentType, store.putCalls[0].third)
    }

    // ── get with LocalDate ─────────────────────────────────────────────────────

    @Test
    fun `get with date tries year-based key first`() {
        val store = TestDocumentStore()
        store.getReturnValue = byteArrayOf(1, 2, 3)
        val date = LocalDate.of(2026, 8, 27)

        val result = store.get(date, "invoice.pdf")

        assertEquals(1, store.getCalls.size)
        assertEquals("documents/2026/invoice.pdf", store.getCalls[0])
        assertThat(result).containsExactly(1, 2, 3)
    }

    @Test
    fun `get with date falls back to legacy key when year-based key not found`() {
        val store = object : DocumentStore {
            val getCalls = mutableListOf<String>()
            override fun put(objectKey: String, data: ByteArray, contentType: String) {}
            override fun exists(objectKey: String): Boolean = false
            override fun enableImmutability(objectKey: String) {}
            override fun delete(objectKey: String) {}
            override fun get(objectKey: String): ByteArray {
                getCalls.add(objectKey)
                if (objectKey == "documents/2026/invoice.pdf") throw RuntimeException("not found")
                return byteArrayOf(4, 5, 6)
            }
        }

        val date = LocalDate.of(2026, 8, 27)
        val result = store.get(date, "invoice.pdf")

        assertEquals(2, store.getCalls.size)
        assertEquals("documents/2026/invoice.pdf", store.getCalls[0])
        assertEquals("invoice.pdf", store.getCalls[1])
        assertThat(result).containsExactly(4, 5, 6)
    }

    @Test
    fun `get with date throws when both keys not found`() {
        val store = object : DocumentStore {
            override fun put(objectKey: String, data: ByteArray, contentType: String) {}
            override fun exists(objectKey: String): Boolean = false
            override fun enableImmutability(objectKey: String) {}
            override fun delete(objectKey: String) {}
            override fun get(objectKey: String): ByteArray {
                throw RuntimeException("not found")
            }
        }

        val date = LocalDate.of(2026, 8, 27)
        assertThrows(RuntimeException::class.java) {
            store.get(date, "invoice.pdf")
        }
    }

    // ── exists with LocalDate ──────────────────────────────────────────────────

    @Test
    fun `exists with date checks year-based key first`() {
        val store = object : DocumentStore {
            override fun put(objectKey: String, data: ByteArray, contentType: String) {}
            override fun get(objectKey: String): ByteArray = ByteArray(0)
            override fun enableImmutability(objectKey: String) {}
            override fun delete(objectKey: String) {}
            override fun exists(objectKey: String): Boolean = objectKey == "documents/2026/invoice.pdf"
        }

        val date = LocalDate.of(2026, 8, 27)
        val result = store.exists(date, "invoice.pdf")

        assertThat(result).isTrue
    }

    @Test
    fun `exists with date checks legacy key if year-based key missing`() {
        val store = object : DocumentStore {
            override fun put(objectKey: String, data: ByteArray, contentType: String) {}
            override fun get(objectKey: String): ByteArray = ByteArray(0)
            override fun enableImmutability(objectKey: String) {}
            override fun delete(objectKey: String) {}
            override fun exists(objectKey: String): Boolean = objectKey == "invoice.pdf"
        }

        val date = LocalDate.of(2026, 8, 27)
        val result = store.exists(date, "invoice.pdf")

        assertThat(result).isTrue
    }

    @Test
    fun `exists with date returns false only when neither key exists`() {
        val store = object : DocumentStore {
            override fun put(objectKey: String, data: ByteArray, contentType: String) {}
            override fun get(objectKey: String): ByteArray = ByteArray(0)
            override fun enableImmutability(objectKey: String) {}
            override fun delete(objectKey: String) {}
            override fun exists(objectKey: String): Boolean = false
        }

        val date = LocalDate.of(2026, 8, 27)
        val result = store.exists(date, "invoice.pdf")

        assertThat(result).isFalse
    }

    // ── delete with LocalDate ──────────────────────────────────────────────────

    @Test
    fun `delete with date attempts both year-based and legacy keys (best-effort)`() {
        val store = TestDocumentStore()
        val date = LocalDate.of(2026, 8, 27)

        store.delete(date, "invoice.pdf")

        // Both keys are always attempted (best-effort semantics)
        assertEquals(2, store.deleteCalls.size)
        assertEquals("documents/2026/invoice.pdf", store.deleteCalls[0])
        assertEquals("invoice.pdf", store.deleteCalls[1])
    }

    @Test
    fun `delete with date continues to legacy key even if year-based fails`() {
        val store = object : DocumentStore {
            val deleteCalls = mutableListOf<String>()
            override fun put(objectKey: String, data: ByteArray, contentType: String) {}
            override fun get(objectKey: String): ByteArray = ByteArray(0)
            override fun exists(objectKey: String): Boolean = false
            override fun enableImmutability(objectKey: String) {}
            override fun delete(objectKey: String) {
                deleteCalls.add(objectKey)
                if (objectKey == "documents/2026/invoice.pdf") throw RuntimeException("not found")
            }
        }

        val date = LocalDate.of(2026, 8, 27)
        store.delete(date, "invoice.pdf")

        assertEquals(2, store.deleteCalls.size)
        assertEquals("documents/2026/invoice.pdf", store.deleteCalls[0])
        assertEquals("invoice.pdf", store.deleteCalls[1])
    }

    @Test
    fun `delete with date is best-effort when both deletions fail`() {
        val store = object : DocumentStore {
            override fun put(objectKey: String, data: ByteArray, contentType: String) {}
            override fun get(objectKey: String): ByteArray = ByteArray(0)
            override fun exists(objectKey: String): Boolean = false
            override fun enableImmutability(objectKey: String) {}
            override fun delete(objectKey: String) {
                throw RuntimeException("cannot delete")
            }
        }

        val date = LocalDate.of(2026, 8, 27)
        // Should not throw, best-effort semantics
        store.delete(date, "invoice.pdf")
    }

    // ── listObjectKeys default implementation ──────────────────────────────────

    @Test
    fun `listObjectKeys default throws UnsupportedOperationException`() {
        val store = object : DocumentStore {
            override fun put(objectKey: String, data: ByteArray, contentType: String) {}
            override fun get(objectKey: String): ByteArray = ByteArray(0)
            override fun exists(objectKey: String): Boolean = false
            override fun enableImmutability(objectKey: String) {}
            override fun delete(objectKey: String) {}
        }

        val ex = assertThrows(UnsupportedOperationException::class.java) {
            store.listObjectKeys("prefix/")
        }

        assertThat(ex.message).contains("listObjectKeys").contains("override")
    }

    // ── headLastModified default implementation ────────────────────────────────

    @Test
    fun `boundary - headLastModified default returns null when a backend does not override it`() {
        val store = object : DocumentStore {
            override fun put(objectKey: String, data: ByteArray, contentType: String) {}
            override fun get(objectKey: String): ByteArray = ByteArray(0)
            override fun exists(objectKey: String): Boolean = false
            override fun enableImmutability(objectKey: String) {}
            override fun delete(objectKey: String) {}
        }

        val result = store.headLastModified("documents/2026/invoice.pdf")

        assertEquals(null, result)
    }
}
