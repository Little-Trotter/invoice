package de.littletrotter.ui.component

import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermBinding
import de.littletrotter.shared.b2b.B2bTermLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** B3 Part B — pure derivation logic: [freeTextChipCandidates] (border cases B1/B2) and
 *  [b2bFreeHeaderChipTerms] (the generic-source filter). */
class FreeTextChipsCandidatesTest {

    // ═══ nothing filled → no candidates ═══

    @Test
    fun `B1 - no service period, project number or B2B term value renders no candidates`() {
        val candidates = freeTextChipCandidates(
            servicePeriod = ServicePeriodChipInput("Leistungszeitraum", null, null),
            projectNumber = ProjectNumberChipInput("Projektnummer", null),
            b2bTermValues = emptyList(),
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `B1 - a blank project number and a blank B2B term value do not count as filled`() {
        val candidates = freeTextChipCandidates(
            servicePeriod = ServicePeriodChipInput("Leistungszeitraum", null, null),
            projectNumber = ProjectNumberChipInput("Projektnummer", "   "),
            b2bTermValues = listOf(FreeTextChipTerm("BT-18", "Innenauftrag", "Innenauftrag") to "  "),
        )

        assertTrue(candidates.isEmpty())
    }

    // ═══ exactly one candidate filled ═══

    @Test
    fun `B2 - only project number filled yields exactly one candidate`() {
        val candidates = freeTextChipCandidates(
            servicePeriod = ServicePeriodChipInput("Leistungszeitraum", null, null),
            projectNumber = ProjectNumberChipInput("Projektnummer", "PRJ-42"),
            b2bTermValues = emptyList(),
        )

        assertEquals(1, candidates.size)
        assertEquals(FreeTextChipCandidate("Projektnummer", "Projektnummer", "PRJ-42"), candidates.single())
    }

    // ── service period: one combined chip, from/to/both ──

    @Test
    fun `service period with both dates renders one combined chip`() {
        val candidates = freeTextChipCandidates(
            servicePeriod = ServicePeriodChipInput("Leistungszeitraum", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10)),
            projectNumber = ProjectNumberChipInput("Projektnummer", null),
            b2bTermValues = emptyList(),
        )

        assertEquals(1, candidates.size)
        assertEquals("Leistungszeitraum", candidates.single().label)
        assertTrue(candidates.single().value.contains("01.08.2026"))
        assertTrue(candidates.single().value.contains("10.08.2026"))
    }

    @Test
    fun `service period with only a start date still renders a chip`() {
        val candidates = freeTextChipCandidates(
            servicePeriod = ServicePeriodChipInput("Leistungszeitraum", LocalDate.of(2026, 8, 1), null),
            projectNumber = ProjectNumberChipInput("Projektnummer", null),
            b2bTermValues = emptyList(),
        )

        assertEquals(1, candidates.size)
        assertEquals("01.08.2026", candidates.single().value)
    }

    @Test
    fun `service period with only an end date still renders a chip`() {
        val candidates = freeTextChipCandidates(
            servicePeriod = ServicePeriodChipInput("Leistungszeitraum", null, LocalDate.of(2026, 8, 10)),
            projectNumber = ProjectNumberChipInput("Projektnummer", null),
            b2bTermValues = emptyList(),
        )

        assertEquals(1, candidates.size)
        assertEquals("10.08.2026", candidates.single().value)
    }

    // ── B2B term values: generic source, label/value trimmed, code carried through ──

    @Test
    fun `a filled B2B term value becomes a candidate carrying its term code`() {
        val candidates = freeTextChipCandidates(
            servicePeriod = ServicePeriodChipInput("Leistungszeitraum", null, null),
            projectNumber = ProjectNumberChipInput("Projektnummer", null),
            b2bTermValues = listOf(FreeTextChipTerm("BT-19", "KostSt.", "Kostenstelle") to "  4711 "),
        )

        val candidate = candidates.single()
        assertEquals("KostSt.", candidate.label)
        assertEquals("Kostenstelle", candidate.fullName)
        assertEquals("4711", candidate.value)
        assertEquals("BT-19", candidate.b2bTermCode)
    }

    @Test
    fun `all three sources combine when everything is filled`() {
        val candidates = freeTextChipCandidates(
            servicePeriod = ServicePeriodChipInput("Leistungszeitraum", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10)),
            projectNumber = ProjectNumberChipInput("Projektnummer", "PRJ-42"),
            b2bTermValues = listOf(FreeTextChipTerm("BT-18", "Innenauftrag", "Innenauftrag") to "IA-1"),
        )

        assertEquals(3, candidates.size)
        assertEquals(listOf("Leistungszeitraum", "Projektnummer", "Innenauftrag"), candidates.map { it.label })
    }

    // ═══ b2bFreeHeaderChipTerms — the generic-source filter itself ═══

    private fun field(term: B2bTerm, definition: String): B2bProfileFieldDto =
        B2bProfileFieldDto(term = term, businessDefinition = definition)

    /** Stand-in resolver that makes the resolved key visible, so a test can tell a short label
     *  from the profile field's long `businessDefinition`. */
    private val trKey: (String) -> String = { key -> "short<$key>" }

    @Test
    fun `invoice- and recipient-placed fields are taken as-is, already FREE and HEADER-level`() {
        val result = b2bFreeHeaderChipTerms(
            invoiceHeaderFields = listOf(field(B2bTerm.BT_18, "Invoiced object identifier")),
            sellerHeaderFields = emptyList(),
            recipientHeaderFields = listOf(
                field(B2bTerm.BT_56, "Buyer contact point (name of the contact person or department at the buyer)"),
            ),
            tr = trKey,
        )

        assertEquals(
            setOf(
                FreeTextChipTerm(
                    "BT-18", "short<invoiceDetail.freeText.chips.term.18>", "short<invoiceDetail.freeText.chips.termName.18>",
                ),
                FreeTextChipTerm(
                    "BT-56", "short<invoiceDetail.freeText.chips.term.56>", "short<invoiceDetail.freeText.chips.termName.56>",
                ),
            ),
            result.toSet(),
        )
    }

    @Test
    fun `a SELLER_PARTY-level seller field is excluded — only HEADER-level seller fields count`() {
        // BT-56 is HEADER-level (buyer contact), used here only as a stand-in FREE term to prove
        // the level filter; the real regression this guards is a mandatory SELLER_PARTY field
        // (level != HEADER) never producing a chip via the seller-fields path.
        val headerLevelSellerField = field(B2bTerm.BT_19, "Kostenstelle")

        val result = b2bFreeHeaderChipTerms(
            invoiceHeaderFields = emptyList(),
            sellerHeaderFields = listOf(headerLevelSellerField),
            recipientHeaderFields = emptyList(),
            tr = trKey,
        )

        assertEquals(
            listOf(
                FreeTextChipTerm(
                    "BT-19", "short<invoiceDetail.freeText.chips.term.19>", "short<invoiceDetail.freeText.chips.termName.19>",
                ),
            ),
            result,
        )
    }

    @Test
    fun `a seller field with no term at all is excluded, no crash`() {
        val fieldWithoutTerm = B2bProfileFieldDto(businessDefinition = "no term set")

        val result = b2bFreeHeaderChipTerms(
            invoiceHeaderFields = emptyList(),
            sellerHeaderFields = listOf(fieldWithoutTerm),
            recipientHeaderFields = emptyList(),
            tr = trKey,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a BOUND term slipped into the seller list is excluded — only FREE terms count`() {
        val boundField = field(B2bTerm.BT_11, "Projektnummer") // BOUND, projectNumber

        val result = b2bFreeHeaderChipTerms(
            invoiceHeaderFields = emptyList(),
            sellerHeaderFields = listOf(boundField),
            recipientHeaderFields = emptyList(),
            tr = trKey,
        )

        assertTrue(result.isEmpty())
    }

    // ═══ every FREE/HEADER catalog term has a short label and a full name in both bundles ═══

    private fun bundle(resource: String): java.util.Properties =
        java.util.Properties().apply {
            checkNotNull(FreeTextChipsCandidatesTest::class.java.classLoader.getResourceAsStream(resource)) { resource }
                .reader(Charsets.UTF_8).use { load(it) }
        }

    @Test
    fun `completeness guard - every FREE HEADER term has a short label and a full name in both bundles`() {
        val terms = B2bTerm.entries.filter { it.binding == B2bTermBinding.FREE && it.level == B2bTermLevel.HEADER }
        assertTrue(terms.isNotEmpty())
        for (resource in listOf("i18n/messages.properties", "i18n/messages_de.properties")) {
            val props = bundle(resource)
            for (term in terms) {
                val key = freeTextChipTermLabelKey(term)
                val label = props.getProperty(key)
                assertTrue(!label.isNullOrBlank(), "$resource defines no label for ${term.officialCode} ($key)")
                assertTrue(label.length <= 14, "$resource $key is longer than 14 characters: $label")
                val nameKey = freeTextChipTermNameKey(term)
                val fullName = props.getProperty(nameKey)
                assertTrue(!fullName.isNullOrBlank(), "$resource defines no full name for ${term.officialCode} ($nameKey)")
                listOf(key to label, nameKey to fullName).forEach { (k, text) ->
                    listOf("BT", "ZUGFeRD", "EN 16931").forEach {
                        assertTrue(!text.contains(it), "$resource $k must not contain '$it': $text")
                    }
                }
            }
        }
    }

    @Test
    fun `the chip label key is derived from the numeric term code`() {
        assertEquals("invoiceDetail.freeText.chips.term.18", freeTextChipTermLabelKey(B2bTerm.BT_18))
        assertEquals("invoiceDetail.freeText.chips.termName.18", freeTextChipTermNameKey(B2bTerm.BT_18))
    }
}
