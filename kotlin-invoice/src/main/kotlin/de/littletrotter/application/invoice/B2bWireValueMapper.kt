package de.littletrotter.application.invoice

import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.shared.B2bValueDto
import de.littletrotter.shared.b2b.B2bTerm
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * [InvoiceDtoMapper]'s free B2B value resolution, extracted verbatim.
 */
@ApplicationScoped
class B2bWireValueMapper
@Inject
constructor(private val b2bFieldValueService: B2bFieldValueService?) {
    /**
     * Loads the captured FREE B2B values of one owner ([ownerType]/[ownerId]) as a
     * generic [B2bValueDto] list for the wire. Blank values are dropped; a null service or owner id
     * yields an empty list (Community / not-yet-persisted draft) so the wire stays unchanged where
     * no B2B profile is active.
     */
    fun valuesFor(ownerType: B2bFieldOwnerType, ownerId: Long?): List<B2bValueDto> {
        val svc = b2bFieldValueService ?: return emptyList()
        val id = ownerId ?: return emptyList()
        return svc.findFieldValues(ownerType, id)
            .mapNotNull { (bt, v) ->
                val term = B2bTerm.fromOfficialCodeOrNull(bt) ?: return@mapNotNull null
                v?.trim()?.takeIf { it.isNotBlank() }?.let { B2bValueDto(term, it) }
            }
    }

    /** [B2bFieldValueService.resolvedRecipientFieldValues] as a `bt_code`-keyed [B2bValueDto]
     *  map, blank/absent resolutions dropped. */
    fun recipientPlacedValues(inv: Invoice): Map<String, B2bValueDto> {
        val svc = b2bFieldValueService ?: return emptyMap()
        return svc.resolvedRecipientFieldValues(inv)
            .mapNotNull { (bt, v) ->
                val term = B2bTerm.fromOfficialCodeOrNull(bt) ?: return@mapNotNull null
                v?.trim()?.takeIf { it.isNotBlank() }?.let { bt to B2bValueDto(term, it) }
            }
            .toMap()
    }
}
