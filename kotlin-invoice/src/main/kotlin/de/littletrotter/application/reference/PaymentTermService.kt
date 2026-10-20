/*
 * Project: Little Trotter X-Invoice
 * Copyright (c) 2026 Eifel42 Stefan Zils
 * See LICENSE and README.md for license terms and documentation.
 *
 * Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.
 */

package de.littletrotter.application.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.repository.PaymentTermRepository
import de.littletrotter.domain.shared.SortDirection
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * payment-terms slice of the former `ReferenceDataService` (split by Referenzdaten
 * domain, one cohesive service per tab).
 * Application service for [PaymentTerm] reference data.
 */
@ApplicationScoped
class PaymentTermService @Inject constructor(
    private val paymentTermRepository: PaymentTermRepository,
) {
    fun findDefaultPaymentTerm(): PaymentTerm? = paymentTermRepository.findDefault()
    fun findPaymentTermById(id: Long): PaymentTerm? = paymentTermRepository.findById(id)
    fun findAllPaymentTerms(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortField: String = "name",
        sortDir: SortDirection = SortDirection.ASC,
    ): List<PaymentTerm> =
        paymentTermRepository.findAll(pageIndex, pageSize, filterText, sortField, sortDir)
    fun countPaymentTerms(filterText: String? = null): Long = paymentTermRepository.count(filterText)
    fun countInactivePaymentTerms(filterText: String? = null): Long = paymentTermRepository.countInactive(filterText)
    /** Backs the tab's "Verwendung" column and its deactivate confirmation. */
    fun findPaymentTermUsageCounts(): Map<Long, Long> = paymentTermRepository.usageCounts()

    /**
     * Takes the default-switch lock and demotes every other default. Callers that load and mutate
     * a managed term must call this BEFORE touching the entity: any later query auto-flushes a
     * dirty `isDefault = true` row, which would hit the single-default index before the lock is held.
     */
    @Transactional
    fun claimDefaultSlot(keepId: Long?) = paymentTermRepository.releaseDefaultsExcept(keepId)

    @Transactional
    fun savePaymentTerm(term: PaymentTerm): PaymentTerm {
        term.validate()
        if (term.isDefault) {
            claimDefaultSlot(term.id)
        }
        return paymentTermRepository.save(term)
    }

    @Transactional
    fun deletePaymentTerm(id: Long) {
        // invoice.payment_term_id and sender_profile.default_payment_term_id are
        // plain FKs (no ON DELETE clause, i.e. RESTRICT): a hard delete of an in-use term would
        // otherwise surface as a raw constraint-violation exception. Blocked here with a clear
        // German message instead.
        if (paymentTermRepository.isInUse(id)) {
            throw InvalidParameterException(
                "Diese Zahlungsbedingung kann nicht gelöscht werden, weil sie noch verwendet wird " +
                    "(Rechnung oder Anbieter-Standard).",
            )
        }
        paymentTermRepository.delete(id)
    }
}
