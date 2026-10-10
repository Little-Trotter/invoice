package de.littletrotter.application.invoice

import de.littletrotter.application.dto.B2bFindingDto
import de.littletrotter.application.dto.CompletenessCheckDto
import de.littletrotter.application.dto.PartySnapshotDiffDto
import de.littletrotter.application.dto.SnapshotFieldChangeDto
import de.littletrotter.domain.compliance.ComplianceCheck
import de.littletrotter.domain.compliance.ComplianceFinding
import de.littletrotter.domain.invoice.PartySnapshotFieldDiff
import de.littletrotter.domain.invoice.PartySnapshotRefreshPreview

/** Where a B2B profile finding carries the profile's own wording for the missing field: the
 *  profile rules put the field's term code first and its user-defined definition second. */
private const val B2B_DEFINITION_ARG_INDEX = 1

/** The detail view's read model of a B2B profile finding; a finding without a user-defined
 *  definition leaves [B2bFindingDto.definition] `null` so the label falls back to the term code. */
internal fun ComplianceFinding.toB2bFindingDto(): B2bFindingDto = B2bFindingDto(
    ruleId = ruleId,
    businessTermCodes = businessTerms.map { it.officialCode },
    definition = args.getOrNull(B2B_DEFINITION_ARG_INDEX) as? String,
    positionId = positionId,
)

internal fun ComplianceCheck.toCompletenessCheckDto(): CompletenessCheckDto =
    CompletenessCheckDto(ruleId = ruleId, satisfied = isSatisfied)

internal fun PartySnapshotRefreshPreview.toDiffDto(): PartySnapshotDiffDto = PartySnapshotDiffDto(
    sellerChanges = sellerChanges.map { it.toChangeDto() },
    buyerChanges = buyerChanges.map { it.toChangeDto() },
)

private fun PartySnapshotFieldDiff.toChangeDto(): SnapshotFieldChangeDto =
    SnapshotFieldChangeDto(field = field, oldValue = oldValue, newValue = newValue)
