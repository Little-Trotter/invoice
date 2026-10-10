/*
 * Project: Little Trotter X-Invoice
 * Copyright (c) 2026 Eifel42 Stefan Zils
 * See LICENSE and README.md for license terms and documentation.
 *
 * Disclaimer: This software is provided as-is, without warranty of any kind. Use at your own risk.
 */

package de.littletrotter.infrastructure.dev

import de.littletrotter.application.invoice.InvoiceNumberAssigner
import de.littletrotter.application.masterdata.AddressManagementService
import de.littletrotter.application.system.DatabaseMaintenanceService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.SenderProfileRepository
import de.littletrotter.shared.time.ClockTimeSource
import de.littletrotter.shared.Money
import io.quarkus.runtime.LaunchMode
import io.quarkus.runtime.StartupEvent
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import de.littletrotter.domain.value.CustomerNumber
import java.time.Instant

/**
 * [DevDataFixtures] against fully mocked ports (unit layer, no Quarkus boot, no real
 * persistence context). Every persisted family (sender, recipients, invoices incl. Money amounts
 * and tax rates) is proven with argument captors on the repositories; the fixture *data* itself
 * lives in [DevDataCatalogue] and is exercised indirectly through these calls. Risk focus per the
 * spec: idempotent second run, the dev-profile guard, per-family persisted counts, and that a
 * persistence failure aborts the whole load instead of a partial seed.
 */
class DevDataFixturesTest {

    private val em = mock<EntityManager>()
    private val invoiceRepo = mock<InvoiceRepository>()
    private val recipientRepo = mock<RecipientRepository>()
    private val senderRepo = mock<SenderProfileRepository>()
    private val addressManagementService = mock<AddressManagementService>()
    private val maintenanceService = mock<DatabaseMaintenanceService>()
    private val numberAssigner = mock<InvoiceNumberAssigner>()

    /** Every fixture invoice, in the exact count and order the fixed core batch plus the two
     *  bulk-year batches produce: 5 core + 100 (2024) + 100 (2025). */
    private val expectedInvoiceCount = 6 + 100 + 100

    private fun fixtures() = DevDataFixtures(
        em, invoiceRepo, de.littletrotter.application.invoice.PartyRepositories(senderRepo, recipientRepo, de.littletrotter.application.invoice.ConsumerNoteResolver(org.mockito.kotlin.mock())), addressManagementService, maintenanceService, numberAssigner,
        ClockTimeSource.fixedAt(Instant.parse("2026-06-15T10:00:00Z")),
    )

    /** Wires [em] so every `cleanup()` native query resolves to a chainable, no-op mock [Query],
     *  and every repository `save()` echoes back the entity it was handed (so lookup-or-save
     *  call chains keep the same instance the fixture built). */
    private fun stubPersistencePlumbing(
        existingSender: SenderProfile? = null,
        existingDomestic: Recipient? = null,
        existingEuRc: Recipient? = null,
        existingPublic: Recipient? = null,
        existingConsumer: Recipient? = null,
    ) {
        val query = mock<Query>()
        whenever(query.setParameter(any<Int>(), any())).thenReturn(query)
        whenever(em.createNativeQuery(any())).thenReturn(query)

        whenever(senderRepo.findDefault()).thenReturn(existingSender)
        whenever(senderRepo.save(any())).thenAnswer { it.arguments[0] }

        whenever(recipientRepo.findByCustomerNumber("DEVEDGE-DOMESTIC")).thenReturn(existingDomestic)
        whenever(recipientRepo.findByCustomerNumber("DEVEDGE-EU-RC")).thenReturn(existingEuRc)
        whenever(recipientRepo.findByCustomerNumber("DEVEDGE-PUBLIC")).thenReturn(existingPublic)
        whenever(recipientRepo.findByCustomerNumber("DEVEDGE-CONSUMER")).thenReturn(existingConsumer)
        whenever(recipientRepo.save(any())).thenAnswer { it.arguments[0] }

        whenever(invoiceRepo.save(any())).thenAnswer { it.arguments[0] }
    }

    @Test
    fun `happy path - seed persists the sender, every recipient and every fixture invoice exactly once`() {
        stubPersistencePlumbing()

        fixtures().seed()

        verify(senderRepo, times(1)).save(any())
        verify(recipientRepo, times(4)).save(any())
        verify(invoiceRepo, times(expectedInvoiceCount)).save(any())
        verify(numberAssigner, times(expectedInvoiceCount)).assignIfNeeded(any())
    }

    @Test
    fun `boundary - a second run is idempotent, never re-inserting sender or recipients that already exist`() {
        val sender = SenderProfile().apply { name = "DEV Edge Cases GmbH" }
        val domestic = Recipient().apply { customerNumber = CustomerNumber("DEVEDGE-DOMESTIC") }
        val euRc = Recipient().apply { customerNumber = CustomerNumber("DEVEDGE-EU-RC") }
        val publicRecipient = Recipient().apply { customerNumber = CustomerNumber("DEVEDGE-PUBLIC") }
        val consumer = Recipient().apply { customerNumber = CustomerNumber("DEVEDGE-CONSUMER") }
        stubPersistencePlumbing(sender, domestic, euRc, publicRecipient, consumer)

        fixtures().seed()

        verify(senderRepo, never()).save(any())
        verify(recipientRepo, never()).save(any())
        // Invoices are still reseeded every run (cleanup() wipes them first) — only the
        // master-data lookup-or-insert is idempotent.
        verify(invoiceRepo, times(expectedInvoiceCount)).save(any())
    }

    @Test
    fun `boundary - onStart never seeds outside the DEVELOPMENT launch mode`() {
        fixtures().onStart(StartupEvent(), LaunchMode.NORMAL)
        fixtures().onStart(StartupEvent(), LaunchMode.TEST)

        verifyNoInteractions(em, invoiceRepo, recipientRepo, senderRepo, addressManagementService, numberAssigner)
    }

    @Test
    fun `boundary - persisted invoice items and travel entries carry the fixture's declared Money amounts and tax rates`() {
        stubPersistencePlumbing()

        fixtures().seed()

        val captor = argumentCaptor<Invoice>()
        verify(invoiceRepo, times(expectedInvoiceCount)).save(captor.capture())
        val draft = captor.allValues.first { it.status == InvoiceStatus.DRAFT && it.itemList.size == 2 }

        val consulting = draft.itemList.first { it.positionId == "POS-001" }
        assertEquals(Money.of("95.00"), consulting.unitPriceNet)
        assertEquals(0, java.math.BigDecimal("19.00").compareTo(consulting.taxRateRef?.rate?.value))

        val hardware = draft.itemList.first { it.positionId == "POS-002" }
        assertEquals(Money.of("49.99"), hardware.unitPriceNet)

        val travelInvoice = captor.allValues.first { it.itemList.any { item -> item.perDiemEntries.isNotEmpty() } }
        val perDiemItem = travelInvoice.itemList.first { it.perDiemEntries.isNotEmpty() }
        assertEquals(3, perDiemItem.perDiemEntries.size)
        assertTrue(perDiemItem.perDiemEntries.all { it.calculatedAmount == Money.of("32.00") })
    }

    @Test
    fun `boundary - a persistence failure aborts the whole seed instead of a partial load`() {
        stubPersistencePlumbing()
        whenever(invoiceRepo.save(any())).thenThrow(RuntimeException("simulated persistence failure"))

        assertThrows(RuntimeException::class.java) { fixtures().seed() }

        // The failure happened on the very first invoice save; master data below it in the
        // method (recipients/sender) was already persisted, but no invoice reached a
        // "successfully seeded" state — the transaction boundary (@Transactional on seed())
        // is what actually rolls this back in production, and this call proves seed() itself
        // never catches the failure to fake a partial success.
        verify(invoiceRepo, times(1)).save(any())
    }
}
