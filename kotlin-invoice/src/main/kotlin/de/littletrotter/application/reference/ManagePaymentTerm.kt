package de.littletrotter.application.reference

import de.littletrotter.application.dto.PaymentTermDto
import de.littletrotter.application.reference.port.inbound.ManagePaymentTermUseCase
import de.littletrotter.application.security.AppPermission
import de.littletrotter.application.security.RequiresPermission
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.repository.LanguageRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/** Implements [ManagePaymentTermUseCase] on top of [PaymentTermService]; every write needs reference-data edit rights. */
@ApplicationScoped
class ManagePaymentTerm @Inject constructor(
    private val paymentTermService: PaymentTermService,
    private val languageRepository: LanguageRepository,
) : ManagePaymentTermUseCase {

    private val rows = CatalogueWrites(paymentTermService::findPaymentTermById, PaymentTerm::version) { PaymentTerm() }

    /**
     * The per-language name handling the catalogue tab's editor used to do directly on the
     * loaded entity ([PaymentTerm.setName], one call per rendered language) moves here: the tab
     * only ever hands over the rendered languages' current field values, keyed by code, so this
     * resolves each code against the active [de.littletrotter.domain.reference.Language] rows and
     * writes exactly the same create/update/delete behaviour [PaymentTerm.setName] already owns.
     * A code this catalogue does not recognize (should not occur; defensive) is silently skipped.
     */
    // L27: one transaction spans load, name edit and save, so the row and its lazy nameGroup/entries
    // stay managed; a detached load + merge used to fail with a row-count OptimisticLockException.
    @Transactional
    @RequiresPermission(AppPermission.REFERENCE_DATA_EDIT)
    override fun savePaymentTerm(term: PaymentTermDto): PaymentTermDto {
        // Lock and demote first: loading/mutating the managed row below makes later queries
        // auto-flush isDefault = true, which must never happen before the lock is held.
        if (term.isDefault) paymentTermService.claimDefaultSlot(term.id)
        val target = rows.rowFor(term.id, term.version)
        term.applyTo(target)
        term.namesByLanguageCode.forEach { (code, value) ->
            languageRepository.findByCode(code)?.let { language -> target.setName(language, value) }
        }
        return paymentTermService.savePaymentTerm(target).toDto()
    }

    @RequiresPermission(AppPermission.REFERENCE_DATA_EDIT)
    override fun deletePaymentTerm(id: Long) = paymentTermService.deletePaymentTerm(id)
}
