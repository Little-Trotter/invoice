package de.littletrotter.application.reference

import de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.domain.reference.B2bFieldOwnerType
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Implements [ManageB2bFieldValueUseCase] on top of [B2bFieldValueService]. The guards are the
 * invoicing/master-data rights of the screens that write these values, not reference-data edit.
 */
@ApplicationScoped
class ManageB2bFieldValue @Inject constructor(
    private val fieldValueService: B2bFieldValueService,
) : ManageB2bFieldValueUseCase {

    // One method serves two contexts (seller values from the master-data views, invoice/item
    // values from invoice editing), so either matching permission grants access.
    @RequiresPermission(AppPermission.INVOICE_FULL_EDIT, AppPermission.MASTER_DATA_EDIT)
    override fun saveB2bFieldValues(ownerType: B2bFieldOwnerType, ownerId: Long, values: Map<String, String?>) =
        fieldValueService.saveFieldValues(ownerType, ownerId, values)

    // Called from the customer form, a master-data screen.
    @RequiresPermission(AppPermission.MASTER_DATA_EDIT)
    override fun saveB2bRecipientFieldValues(recipientId: Long, profileId: Long, values: Map<String, String?>) =
        fieldValueService.saveRecipientFieldValues(recipientId, profileId, values)

    // Mutates an invoice item, so the invoice-editing permission applies.
    @RequiresPermission(AppPermission.INVOICE_FULL_EDIT)
    override fun copyB2bArticleTemplateToInvoiceItem(articleId: Long, invoiceItemId: Long, invoiceId: Long) =
        fieldValueService.copyLineTemplateValuesToInvoiceItem(articleId, invoiceItemId, invoiceId)
}
