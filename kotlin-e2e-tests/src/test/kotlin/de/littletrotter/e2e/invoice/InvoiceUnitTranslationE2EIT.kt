package de.littletrotter.e2e.invoice

import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.TripCommand
import de.littletrotter.application.invoice.TripExpenseCommand
import de.littletrotter.application.invoice.TripService
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.Money
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.edit

/**
 * Unit labels across all invoice position categories, through committed PostgreSQL roundtrips.
 * Fixture names and amounts are derived from the public `datagen --seed 42` catalogue fixture
 * (`catalogue-datagen.tsv`); no external or existing-instance data is used.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class InvoiceUnitTranslationE2EIT {
    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var tripService: TripService

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    private data class Parties(val senderId: Long, val recipientId: Long, val taxRateId: Long)

    private data class DemoRow(val description: String, val amount: String)

    private fun demoRow(category: InvoiceItemCategory): DemoRow =
        javaClass.getResourceAsStream("/catalogue-datagen.tsv")!!.bufferedReader().useLines { lines ->
            lines.filterNot { it.startsWith("#") }
                .map { it.split('\t') }
                .first { it[1].toInt() == category.code }
                .let { DemoRow(it[0], it[3]) }
        }

    /** No recipient address on purpose: a missing country/locale exercises the production DE default. */
    private fun parties(tag: String): Parties = QuarkusTransaction.requiringNew().call {
        val sender = aSenderProfile {
            name = "$tag UNIT-E2E Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@unit-e2e.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE129273398")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        sender.addAddress(
            Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true),
        )
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-UNIT-E2E-100")
            name = "$tag UNIT-E2E Empfänger AG"
            email = Email.ofNullable("ap@unit-e2e.example")
            vatId = VatId("DE136695976")
        }
        em.persist(sender)
        em.persist(recipient)
        val taxRate = TaxRate().edit {
            code = "${tag}_UNIT_E2E_STD19"
            category = "S"
            name = "USt 19 %"
            rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE
            validFrom = LocalDate.of(2024, 1, 1)
            isSelectable = true
            isActive = true
        }
        em.persist(taxRate)
        em.flush()
        Parties(sender.id!!, recipient.id!!, taxRate.id!!)
    }

    private fun createFourCategoryInvoice(tag: String): Long {
        val parties = parties(tag)
        val invoiceId = QuarkusTransaction.requiringNew().call {
            lifecycleService.create(parties.senderId, parties.recipientId).id!!
        }
        val specs = listOf(
            Triple(InvoiceItemCategory.PRODUCT, InvoiceItemUnit.CUBIC_METER, "3"),
            Triple(InvoiceItemCategory.UNIT_PRICE, InvoiceItemUnit.SQUARE_METER, "12.5"),
            Triple(InvoiceItemCategory.SERVICE, InvoiceItemUnit.FLAT_RATE, "1"),
        )
        specs.forEach { (category, unit, quantity) ->
            val demo = demoRow(category)
            QuarkusTransaction.requiringNew().run {
                itemMutationService.addItem(
                    invoiceId,
                    InvoiceItemCommand(
                        category = category,
                        description = "${demo.description} ${unit.name}",
                        quantity = BigDecimal(quantity),
                        unit = unit,
                        unitPriceNet = Money.of(demo.amount),
                        taxRateId = parties.taxRateId,
                    ),
                )
            }
        }
        QuarkusTransaction.requiringNew().run {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "${demoRow(InvoiceItemCategory.TRIP).description} FLAT_RATE",
                    expenses = listOf(
                        TripExpenseCommand(
                            kind = TripExpenseKind.TRAVEL_TIME,
                            description = "Synthetic datagen travel time",
                            quantity = BigDecimal("2"),
                            unit = InvoiceItemUnit.HOUR,
                            unitPriceNet = Money.of("120.00"),
                        ),
                    ),
                ),
            )
        }
        return invoiceId
    }

    private fun dto(invoiceId: Long): InvoiceDto = QuarkusTransaction.requiringNew().call {
        InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(em.find(Invoice::class.java, invoiceId)!!)
    }

    private fun rawEnumNames(dto: InvoiceDto): Set<String> = buildSet {
        dto.items.mapNotNullTo(this) { it.unitLabel }
        dto.items.mapNotNull { it.trip }.flatMap { it.expenses }.mapNotNullTo(this) { it.unitLabel }
    }.intersect(InvoiceItemUnit.entries.map { it.name }.toSet())

    @Test
    fun `missing recipient locale defaults to German and all category units survive reload translated`() {
        val invoiceId = createFourCategoryInvoice("UDE")

        val persistedUnits = QuarkusTransaction.requiringNew().call {
            em.find(Invoice::class.java, invoiceId)!!.let { invoice ->
                assertEquals("de", invoice.documentLanguage.code)
                invoice.itemList.associate { it.category to it.unit }
            }
        }
        assertEquals(InvoiceItemUnit.CUBIC_METER, persistedUnits[InvoiceItemCategory.PRODUCT])
        assertEquals(InvoiceItemUnit.SQUARE_METER, persistedUnits[InvoiceItemCategory.UNIT_PRICE])
        assertEquals(InvoiceItemUnit.FLAT_RATE, persistedUnits[InvoiceItemCategory.SERVICE])
        assertEquals(InvoiceItemUnit.FLAT_RATE, persistedUnits[InvoiceItemCategory.TRIP])

        val dto = dto(invoiceId)
        val labels = dto.items.associate { it.category.name to it.unitLabel }
        assertEquals("m³", labels[InvoiceItemCategory.PRODUCT.name])
        assertEquals("m²", labels[InvoiceItemCategory.UNIT_PRICE.name])
        assertEquals("Pauschale", labels[InvoiceItemCategory.SERVICE.name])
        assertEquals("Pauschale", labels[InvoiceItemCategory.TRIP.name])
        assertEquals("Stunden", dto.items.single { it.trip != null }.trip!!.expenses.single().unitLabel)
        assertTrue(rawEnumNames(dto).isEmpty(), "human-facing DTO labels must never expose enum names")
    }

    @Test
    fun `explicit English document language translates compound flat-rate and travel-time units`() {
        val invoiceId = createFourCategoryInvoice("UEN")
        QuarkusTransaction.requiringNew().run {
            em.find(Invoice::class.java, invoiceId)!!.edit { documentLanguage = "en" }
        }

        val dto = dto(invoiceId)
        assertEquals(
            setOf("m³", "m²", "flat rate"),
            dto.items.filter { it.category.name != InvoiceItemCategory.TRIP.name }.mapNotNull { it.unitLabel }.toSet(),
        )
        assertEquals("flat rate", dto.items.single { it.category.name == InvoiceItemCategory.TRIP.name }.unitLabel)
        assertEquals("hours", dto.items.single { it.trip != null }.trip!!.expenses.single().unitLabel)
        assertFalse(dto.items.any { it.unitLabel.orEmpty().contains('_') })
        assertTrue(rawEnumNames(dto).isEmpty(), "explicit EN must never fall back to enum names")
    }
}
