package de.littletrotter.application.invoice

import de.littletrotter.application.dto.B2bFindingDto
import de.littletrotter.application.dto.CompletenessCheckDto
import de.littletrotter.application.dto.PartySnapshotDiffDto
import de.littletrotter.application.dto.SnapshotFieldChangeDto
import de.littletrotter.domain.compliance.ComplianceCheck
import de.littletrotter.domain.compliance.ComplianceFinding
import de.littletrotter.domain.compliance.ComplianceSeverity
import de.littletrotter.domain.compliance.FindingTarget
import de.littletrotter.domain.invoice.PartySnapshotFieldDiff
import de.littletrotter.domain.invoice.PartySnapshotRefreshPreview
import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The detail view's read models are built from the domain findings here and nowhere else, so
 *  every field the UI renders is pinned on its own. */
class InvoiceDetailFindingMappingTest {

    private fun finding(
        businessTerms: List<B2bTerm> = listOf(B2bTerm.BT_19),
        target: FindingTarget = FindingTarget.Header,
        args: List<Any?> = emptyList(),
    ) = ComplianceFinding(
        ruleId = "B2B.MANDATORY_FIELD",
        severity = ComplianceSeverity.ERROR,
        messageKey = "compliance.b2b.mandatoryFieldMissing",
        businessTerms = businessTerms,
        target = target,
        args = args,
    )

    @Test
    fun `a header finding carries its term codes and definition but no position`() {
        val dto = finding(args = listOf("BT-19", "Kostenstelle")).toB2bFindingDto()

        assertEquals(B2bFindingDto("B2B.MANDATORY_FIELD", listOf("BT-19"), "Kostenstelle", null), dto)
    }

    @Test
    fun `a line finding carries the business position id`() {
        val dto = finding(target = FindingTarget.Position("7"), args = listOf("BT-19", "Kostenstelle")).toB2bFindingDto()

        assertEquals("7", dto.positionId)
    }

    @Test
    fun `a finding without a definition argument leaves the definition empty`() {
        assertEquals(null, finding(args = listOf("BT-19")).toB2bFindingDto().definition)
    }

    @Test
    fun `a non-text definition argument is not taken as definition`() {
        assertEquals(null, finding(args = listOf("BT-19", 42)).toB2bFindingDto().definition)
    }

    @Test
    fun `a finding without business terms maps to an empty code list`() {
        assertEquals(emptyList<String>(), finding(businessTerms = emptyList()).toB2bFindingDto().businessTermCodes)
    }

    @Test
    fun `a completeness check keeps its rule id and satisfaction`() {
        val check = ComplianceCheck(
            ruleId = "CORE.INVOICE_NUMBER",
            severity = ComplianceSeverity.ERROR,
            messageKey = "msg",
            isSatisfied = false,
        )

        assertEquals(CompletenessCheckDto("CORE.INVOICE_NUMBER", false), check.toCompletenessCheckDto())
    }

    @Test
    fun `an empty snapshot preview maps to a diff without changes`() {
        val diff = PartySnapshotRefreshPreview(emptyList(), emptyList()).toDiffDto()

        assertEquals(PartySnapshotDiffDto(emptyList(), emptyList()), diff)
        assertFalse(diff.hasChanges)
    }

    @Test
    fun `a buyer-only change still counts as a change`() {
        val diff = PartySnapshotRefreshPreview(
            sellerChanges = emptyList(),
            buyerChanges = listOf(PartySnapshotFieldDiff("Leitweg-ID (BT-10)", "991-1", null)),
        ).toDiffDto()

        assertEquals(listOf(SnapshotFieldChangeDto("Leitweg-ID (BT-10)", "991-1", null)), diff.buyerChanges)
        assertTrue(diff.hasChanges)
    }
}
