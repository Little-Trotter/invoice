package de.littletrotter.domain.invoice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class B2bHeaderFreeTextMergeTest {
    @Test
    fun `appends nonblank unmapped values once after existing editable text`() {
        val result = mergeUnmappedB2bHeaderValues(
            freeText = "Manual text  ",
            alreadyImportedCodes = setOf("BT-12"),
            values = listOf(
                UnmappedB2bHeaderValue("BT-12", "old"),
                UnmappedB2bHeaderValue("BT-18", "  Building A  "),
                UnmappedB2bHeaderValue("BT-19", "   "),
                UnmappedB2bHeaderValue("BT-56", null),
            ),
        )

        assertEquals("Manual text\nBuilding A", result.freeText)
        assertEquals(setOf("BT-12", "BT-18"), result.importedCodes)
    }

    @Test
    fun `starts empty free text without leading separator and carries the bare value only`() {
        val result = mergeUnmappedB2bHeaderValues(
            freeText = null,
            alreadyImportedCodes = emptySet(),
            values = listOf(UnmappedB2bHeaderValue("BT-56", " Herr Müller ")),
        )

        assertEquals("Herr Müller", result.freeText)
        assertEquals(setOf("BT-56"), result.importedCodes)
    }

    @Test
    fun `two values become two lines in input order`() {
        val result = mergeUnmappedB2bHeaderValues(
            freeText = "",
            alreadyImportedCodes = emptySet(),
            values = listOf(
                UnmappedB2bHeaderValue("BT-56", "Herr Müller"),
                UnmappedB2bHeaderValue("BT-19", "4711"),
            ),
        )

        assertEquals("Herr Müller\n4711", result.freeText)
        assertEquals(setOf("BT-56", "BT-19"), result.importedCodes)
    }

    @Test
    fun `no value to add leaves the text untouched`() {
        val result = mergeUnmappedB2bHeaderValues(
            freeText = "Manual text  ",
            alreadyImportedCodes = setOf("BT-12"),
            values = listOf(UnmappedB2bHeaderValue("BT-19", null), UnmappedB2bHeaderValue("BT-12", "old")),
        )

        assertEquals("Manual text  ", result.freeText)
        assertEquals(setOf("BT-12"), result.importedCodes)
    }

    @Test
    fun `does not recreate a manually removed imported line`() {
        val result = mergeUnmappedB2bHeaderValues(
            freeText = "Manager replacement",
            alreadyImportedCodes = setOf("BT-12"),
            values = listOf(UnmappedB2bHeaderValue("BT-12", "C-2")),
        )

        assertEquals("Manager replacement", result.freeText)
        assertEquals(setOf("BT-12"), result.importedCodes)
    }
}
