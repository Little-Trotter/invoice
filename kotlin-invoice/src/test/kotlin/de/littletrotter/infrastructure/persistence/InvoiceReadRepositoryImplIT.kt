package de.littletrotter.infrastructure.persistence

import de.littletrotter.domain.invoice.addTripExpense
import de.littletrotter.domain.invoice.addPerDiemEntry
import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.invoice.port.outbound.InvoiceReadRepository
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoicePaymentTermSnapshot
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.reference.TravelExpenseEntry
import de.littletrotter.domain.value.Bic
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.DiscountPercent
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.aReceipt
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSellerVatRegistration
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.aTripExpense
import de.littletrotter.fixture.anArticle
import de.littletrotter.fixture.edit
import de.littletrotter.shared.Money
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.country.Country
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class InvoiceReadRepositoryImplIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var invoiceReadRepository: InvoiceReadRepository

    @Test
    @Transactional
    fun `findInvoiceDetailDto returns receipt ids and travel expense details for invoice view`() {
        val sender = aSenderProfile {
            name = "Sender GmbH"
            // Unique per test class — "SG-" alone collided with InvoiceDetailWriteBackIT
            // (uq_sender_profile_invoice_prefix is table-wide).
            invoicePrefix = "RR-SG-"
            taxNumber = TaxNumber("11/616/35840")
            vatId = VatId("DE123456789")
            email = de.littletrotter.domain.value.Email.ofNullable("sender@example.com")
            phone = "+49 30 1234567"
            commercialRegisterNumber = "HRB 233129"
            courtOfJurisdiction = "Berlin"
            registerCourt = "Amtsgericht Berlin (Charlottenburg)"
            iban = de.littletrotter.domain.value.Iban.ofNullable("DE81100500003274602390")
            managingDirector = "Arthur Dent"
            bankName = "Berliner Sparkasse"
            bic = Bic("BELADEBEXXX")
        }
        val recipient = aRecipient {
            customerNumber = CustomerNumber("KD-100")
            name = "Acme GmbH"
            vatId = VatId("DE987654321")
            email = de.littletrotter.domain.value.Email.ofNullable("acme@example.com")
            phone = "+49 30 7654321"
            contactPerson = "Ford Prefect"
            routingId = "04011000-1234512345-06"
        }
        val paymentTerm = aPaymentTerm {
            changeName(em.find(de.littletrotter.domain.reference.Language::class.java, 1)!!, "14 Tage")
            dueInDays = 14
        }
        val taxRate = TaxRate().edit {
            code = "IT_READREPO_DE_19" // NOT NULL since V904
            category = "S" // chk_tax_rate_selectable_needs_category (V904)
            name = "USt 19"
            // @Transactional IT tests in this suite commit rather than roll back (shared DB
            // across the module's full IT run, see PartyIdentifierMigrationIT's own comment on
            // this) — code/category/source only need to satisfy this row's own NOT NULL/CHECK
            // constraints, and isSelectable stays false so this fixture row does not leak into
            // TaxRuleMigrationIT's "selectable rules" count elsewhere in the same run.
            code = "ITRRDI_FIXTURE_DE_19"
            category = "S"
            isSelectable = false
            rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE
            validFrom = LocalDate.of(2024, 1, 1)
            isDefault = false
        }
        val perDiemRate = PerDiemRate().edit {
            source = "Testdaten InvoiceReadRepositoryImplIT" // chk_per_diem_rate_active_needs_source (V905)
            // Fixture row, not a selectable reference rate: is_active = false keeps it out of
            // selection AND out of TravelRateMigrationIT's assertions ("exactly one active
            // domestic row").
            isActive = false
            country = Country.DE
            region = "Berlin"
            fullDayRate = Money.of("28.00")
            arrivalDepartureRate = Money.of("14.00")
            inlandRules = true
            isDefault = false
            source = "BMF-Schreiben (Test-Fixture)"
            // Referenced directly by object below (not looked up by an is_active filter), so
            // this stays false to avoid leaking into TravelRateMigrationIT's "exactly one active
            // domestic per_diem_rate row" count elsewhere in the same shared-DB IT run (see the
            // taxRate fixture above for the same reasoning).
            isActive = false
            validFrom = LocalDate.of(2024, 1, 1)
        }
        val receipt = aReceipt {
            fileName = "hotel.pdf"
            description = "Hotel Berlin"
            objectKey = "receipt-pool/hotel.pdf"
            sizeBytes = 2048
            // Checksums are globally unique (uq_receipt_sha256_checksum, V002) —
            // fixtures must not share a literal value across test classes.
            sha256Checksum = java.util.UUID.randomUUID().toString().replace("-", "").repeat(2)
            uploadedAt = OffsetDateTime.now()
            documentDate = LocalDate.of(2026, 3, 14)
        }

        em.persist(sender)
        em.persist(recipient)
        em.persist(paymentTerm)
        em.persist(taxRate)
        em.persist(perDiemRate)
        em.persist(receipt)

        val invoice = Invoice().edit {
            invoiceNumber = InvoiceNumber("IT-2026-04-00001")
            senderProfile = sender
            this.recipient = recipient
            this.paymentTerm = paymentTerm
            invoiceDate = LocalDate.of(2026, 4, 5)
            dueDate = LocalDate.of(2026, 4, 19)
            paymentTermsDays = 14
            freeText = "Bitte zeitnah begleichen"
        }

        val item = InvoiceItem().edit {
            positionId = "1"
            category = InvoiceItemCategory.TRIP
            description = "Max Mustermann Berlin Verpflegung"
            quantity = BigDecimal.ONE
            unit = InvoiceItemUnit.FLAT_RATE
            unitPriceNet = Money(BigDecimal("28.00"))
            taxRateRef = taxRate
            sortOrder = 0
            calculateAmounts()
            receipts = receipts + receipt
        }
        val travelExpense = TravelExpenseEntry().edit {
            shortName = "DE Berlin 2026-04-05"
            invoiceItem = item
            country = Country.DE
            description = "Team-Reise Berlin"
            participant = "Berlin"
            travelDate = LocalDate.of(2026, 4, 5)
            isArrivalDeparture = true
            breakfastDeduction = true
            lunchDeduction = false
            dinnerDeduction = false
            fullDayRateSnapshot = Money.of("28.00")
            arrivalDepartureRateSnapshot = Money.of("14.00")
            calculatedAmount = Money.of("8.40")
            employerContribution = Money.ZERO
            this.perDiemRate = perDiemRate
        }
        item.addPerDiemEntry(travelExpense)
        invoice.addItem(item)
        invoice.recalculateTotals()

        em.persist(invoice)
        em.flush()
        em.clear()

        val dto = invoiceReadRepository.findInvoiceDetailDto(invoice.id!!)

        assertNotNull(dto)
        assertEquals("IT-2026-04-00001", dto?.invoiceNumber)
        assertEquals(1, dto?.items?.size)
        val dtoItem = dto!!.items.single()
        assertEquals(InvoiceItemCategory.TRIP.name, dtoItem.category)
        assertEquals(1, dtoItem.receiptsCount)
        assertEquals(listOf(receipt.id!!), dtoItem.receiptIds)
        assertEquals(1, dtoItem.trip?.perDiemDays?.size)
        val dtoTravel = dtoItem.trip!!.perDiemDays.single()
        assertEquals("DE", dtoTravel.countryAlpha2)
        assertEquals("Berlin", dtoTravel.participant)
        assertEquals("Team-Reise Berlin", dtoTravel.description)
        assertEquals(0, BigDecimal("28.00").compareTo(dtoTravel.fullDayRateSnapshot.amount))
        assertEquals(0, BigDecimal("14.00").compareTo(dtoTravel.arrivalDepartureRateSnapshot.amount))
        assertTrue(dtoTravel.calculatedAmount!!.amount.compareTo(BigDecimal("8.40")) == 0)
        assertEquals(perDiemRate.id, dtoTravel.perDiemRateId)

        // task-mustang-kmu-leitweg-v2 — the JPQL header projection in InvoiceReadRepositoryImpl
        // is positional (header[n]); removing `sp.routingId` shifted every following index by
        // one. This is the regression test the removal's own KDoc pointed out was missing: it
        // pins down every sender and recipient field, including all the fields that sit AFTER
        // the removed slot (commercialRegisterNumber onward for the sender, routingId for the
        // recipient), so a future re-indexing mistake fails here instead of shipping silently.
        val dtoSender = dto?.sender
        assertNotNull(dtoSender)
        assertEquals(sender.id, dtoSender?.id)
        assertEquals("Sender GmbH", dtoSender?.name)
        assertEquals("11/616/35840", dtoSender?.taxNumber)
        assertEquals("DE123456789", dtoSender?.vatId)
        assertEquals("sender@example.com", dtoSender?.email)
        assertEquals("+49 30 1234567", dtoSender?.phone)
        assertEquals("HRB 233129", dtoSender?.commercialRegisterNumber)
        assertEquals("Berlin", dtoSender?.courtOfJurisdiction)
        assertEquals("Amtsgericht Berlin (Charlottenburg)", dtoSender?.registerCourt)
        assertEquals("DE81100500003274602390", dtoSender?.iban)
        assertEquals("Arthur Dent", dtoSender?.managingDirector)
        assertEquals("Berliner Sparkasse", dtoSender?.bankName)
        assertEquals("BELADEBEXXX", dtoSender?.bic)
        // Sender carries no Leitweg-ID (BT-10 identifies the buyer, never the seller) — the
        // field was removed from SenderProfile entirely; PartyDto.routingId stays shared with
        // the recipient projection below but is never populated for the sender side anymore.
        assertNull(dtoSender?.routingId)

        val dtoRecipient = dto?.recipient
        assertNotNull(dtoRecipient)
        assertEquals(recipient.id, dtoRecipient?.id)
        assertEquals("Acme GmbH", dtoRecipient?.name)
        assertEquals("KD-100", dtoRecipient?.customerNumber)
        assertEquals("DE987654321", dtoRecipient?.vatId)
        assertEquals("acme@example.com", dtoRecipient?.email)
        assertEquals("+49 30 7654321", dtoRecipient?.phone)
        assertEquals("Ford Prefect", dtoRecipient?.contactPerson)
        assertEquals("04011000-1234512345-06", dtoRecipient?.routingId)
    }

    // ── Real-Postgres row-shape coverage for the null-vs-value branches of the
    //    header/item/travel/expense/job mapping (`InvoiceReadRepositoryImpl`'s own KDoc: real,
    //    currently-unproven assumptions about what Postgres/Hibernate returns for each column). ──

    @Test
    @Transactional
    fun `findInvoiceDetailDto reports null sender, recipient, billing and delivery address, and payment term id for a bare draft`() {
        val invoice = Invoice().edit {
            invoiceDate = LocalDate.of(2026, 4, 10)
        }
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val dto = invoiceReadRepository.findInvoiceDetailDto(invoiceId)

        assertNotNull(dto)
        assertNull(dto?.sender)
        assertNull(dto?.recipient)
        assertNull(dto?.billingAddress)
        assertNull(dto?.deliveryAddress)
        assertNull(dto?.paymentTermId)
        assertNull(dto?.paymentTermName)
    }

    @Test
    @Transactional
    fun `findInvoiceDetailDto reports the seller's additional VAT registration countries`() {
        val sender = aSenderProfile {
            name = "VAT Sender GmbH"
            invoicePrefix = "RR2-"
        }
        em.persist(sender)
        val registration = aSellerVatRegistration {
            senderProfile = sender
            // Curated country set enforced by chk_seller_vat_registration_country: FR/BE/NL/LU only.
            country = Country.FR
            vatId = VatId("FR12345678901")
        }
        em.persist(registration)
        val invoice = Invoice().edit {
            senderProfile = sender
            invoiceDate = LocalDate.of(2026, 4, 10)
        }
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val dto = invoiceReadRepository.findInvoiceDetailDto(invoiceId)

        assertEquals(listOf("FR"), dto?.sender?.additionalVatRegistrationCountries)
    }

    @Test
    @Transactional
    fun `findInvoiceDetailDto reports the recipient's own b2b profile id and the invoice's seller VAT registration country`() {
        val recipient = aRecipient {
            customerNumber = CustomerNumber("KD-200")
            name = "B2B Kunde AG"
        }
        val profile = B2bProfile().edit { name = "B2B Profil RR2" }
        em.persist(profile)
        recipient.edit { b2bProfile = profile }
        em.persist(recipient)
        val invoice = Invoice().edit {
            this.recipient = recipient
            invoiceDate = LocalDate.of(2026, 4, 10)
            // Curated country set enforced by chk_invoice_seller_vat_registration_country: FR/BE/NL/LU only.
            sellerVatRegistrationCountry = Country.FR
        }
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val dto = invoiceReadRepository.findInvoiceDetailDto(invoiceId)

        assertEquals(profile.id, dto?.recipient?.b2bProfileId)
        assertEquals("FR", dto?.sellerVatRegistrationCountry)
    }

    @Test
    @Transactional
    fun `findInvoiceDetailDto reports the billing and delivery address when both are set`() {
        val recipient = aRecipient {
            customerNumber = CustomerNumber("KD-201")
            name = "Adress Kunde AG"
        }
        em.persist(recipient)
        val billing = Address.forRecipient(recipient, Address.AddressType.BILLING, "Rechnungsweg 1", "10115", "Berlin", Country.DE)
        val delivery = Address.forRecipient(recipient, Address.AddressType.DELIVERY, "Lieferweg 2", "50667", "Koeln", Country.DE)
        em.persist(billing)
        em.persist(delivery)
        val invoice = Invoice().edit {
            this.recipient = recipient
            billingAddress = billing
            deliveryAddress = delivery
            invoiceDate = LocalDate.of(2026, 4, 10)
        }
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val dto = invoiceReadRepository.findInvoiceDetailDto(invoiceId)

        assertEquals("DE", dto?.billingAddress?.countryAlpha2)
        assertEquals("Rechnungsweg 1", dto?.billingAddress?.street)
        assertEquals("DE", dto?.deliveryAddress?.countryAlpha2)
        assertEquals("Lieferweg 2", dto?.deliveryAddress?.street)
    }

    @Test
    @Transactional
    fun `findInvoiceDetailDto reports the bound tax rate id and rate, and the bound article id for an item with an article`() {
        val sender = aSenderProfile {
            name = "Artikel Sender GmbH"
            invoicePrefix = "RR3-"
        }
        val taxRate = TaxRate().edit {
            code = "IT_RR3_DE_19"
            category = "S"
            name = "USt 19"
            isSelectable = false
            rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE
            validFrom = LocalDate.of(2024, 1, 1)
            isDefault = false
        }
        val article = anArticle {
            name = "Wartungsvertrag"
            category = InvoiceItemCategory.SERVICE
            defaultUnit = InvoiceItemUnit.FLAT_RATE
            defaultUnitPriceNet = Money(BigDecimal("50.00"))
            articleNumber = de.littletrotter.domain.value.ArticleNumber("ART-RR3-1")
        }
        em.persist(sender)
        em.persist(taxRate)
        em.persist(article)

        val invoice = Invoice().edit {
            senderProfile = sender
            invoiceDate = LocalDate.of(2026, 4, 10)
        }
        val withoutArticle = InvoiceItem().edit {
            positionId = "1"
            category = InvoiceItemCategory.SERVICE
            description = "Ohne Artikel"
            quantity = BigDecimal.ONE
            unit = InvoiceItemUnit.FLAT_RATE
            unitPriceNet = Money(BigDecimal("10.00"))
            taxRateRef = taxRate
            sortOrder = 0
        }
        val withArticle = InvoiceItem().edit {
            positionId = "2"
            category = InvoiceItemCategory.SERVICE
            description = "Mit Artikel"
            quantity = BigDecimal.ONE
            unit = InvoiceItemUnit.FLAT_RATE
            unitPriceNet = Money(BigDecimal("50.00"))
            taxRateRef = taxRate
            articleRef = article
            sortOrder = 1
        }
        invoice.addItem(withoutArticle)
        invoice.addItem(withArticle)
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val dto = invoiceReadRepository.findInvoiceDetailDto(invoiceId)

        val items = dto!!.items.associateBy { it.positionId }
        assertEquals(taxRate.id, items.getValue("1").taxRateRefId)
        assertEquals(0, taxRate.rate.value.compareTo(items.getValue("1").taxRate))
        assertEquals(article.id, items.getValue("2").articleRefId)
    }

    @Test
    @Transactional
    fun `findInvoiceDetailDto reports a null perDiemRateId for a travel entry without a bound per-diem rate`() {
        val sender = aSenderProfile {
            name = "Travel Sender GmbH"
            invoicePrefix = "RR4-"
        }
        val taxRate = TaxRate().edit {
            code = "IT_RR4_DE_19"
            category = "S"
            name = "USt 19"
            isSelectable = false
            rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE
            validFrom = LocalDate.of(2024, 1, 1)
            isDefault = false
        }
        val perDiemRate = PerDiemRate().edit {
            source = "IT RR4 Fixture"
            isActive = false
            country = Country.DE
            region = "Berlin"
            fullDayRate = Money.of("28.00")
            arrivalDepartureRate = Money.of("14.00")
            inlandRules = true
            isDefault = false
            validFrom = LocalDate.of(2024, 1, 1)
        }
        em.persist(sender)
        em.persist(taxRate)
        em.persist(perDiemRate)

        val invoice = Invoice().edit {
            senderProfile = sender
            invoiceDate = LocalDate.of(2026, 4, 10)
        }
        val item = InvoiceItem().edit {
            positionId = "1"
            category = InvoiceItemCategory.TRIP
            description = "Reise mit gemischten Tagessaetzen"
            quantity = BigDecimal.ONE
            unit = InvoiceItemUnit.FLAT_RATE
            unitPriceNet = Money(BigDecimal("14.00"))
            taxRateRef = taxRate
            sortOrder = 0
            calculateAmounts()
        }
        val withRate = TravelExpenseEntry().edit {
            shortName = "RR4 mit Rate"
            invoiceItem = item
            country = Country.DE
            description = "Mit Rate"
            participant = "A"
            travelDate = LocalDate.of(2026, 4, 10)
            fullDayRateSnapshot = Money.of("28.00")
            arrivalDepartureRateSnapshot = Money.of("14.00")
            calculatedAmount = Money.of("28.00")
            employerContribution = Money.ZERO
            this.perDiemRate = perDiemRate
        }
        val withoutRate = TravelExpenseEntry().edit {
            shortName = "RR4 ohne Rate"
            invoiceItem = item
            country = Country.DE
            description = "Ohne Rate"
            participant = "B"
            travelDate = LocalDate.of(2026, 4, 11)
            fullDayRateSnapshot = Money.ZERO
            arrivalDepartureRateSnapshot = Money.ZERO
            calculatedAmount = Money.ZERO
            employerContribution = Money.ZERO
        }
        item.addPerDiemEntry(withRate)
        item.addPerDiemEntry(withoutRate)
        invoice.addItem(item)
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val dto = invoiceReadRepository.findInvoiceDetailDto(invoiceId)

        val days = dto!!.items.single().trip!!.perDiemDays.associateBy { it.participant }
        assertEquals(perDiemRate.id, days.getValue("A").perDiemRateId)
        assertNull(days.getValue("B").perDiemRateId)
    }

    @Test
    @Transactional
    fun `findInvoiceDetailDto expands a TRIP item's trip expenses, with a receipt id only when one is attached`() {
        val sender = aSenderProfile {
            name = "Trip Sender GmbH"
            invoicePrefix = "RR5-"
        }
        val taxRate = TaxRate().edit {
            code = "IT_RR5_DE_19"
            category = "S"
            name = "USt 19"
            isSelectable = false
            rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE
            validFrom = LocalDate.of(2024, 1, 1)
            isDefault = false
        }
        val receipt = aReceipt {
            fileName = "taxi.pdf"
            description = "Taxi"
            objectKey = "receipt-pool/rr5-taxi.pdf"
            sha256Checksum = java.util.UUID.randomUUID().toString().replace("-", "").repeat(2)
            documentDate = LocalDate.of(2026, 4, 10)
        }
        em.persist(sender)
        em.persist(taxRate)
        em.persist(receipt)

        val invoice = Invoice().edit {
            senderProfile = sender
            invoiceDate = LocalDate.of(2026, 4, 10)
        }
        val item = InvoiceItem().edit {
            positionId = "1"
            category = InvoiceItemCategory.TRIP
            description = "Dienstreise"
            quantity = BigDecimal.ONE
            unit = InvoiceItemUnit.FLAT_RATE
            unitPriceNet = Money.ZERO
            taxRateRef = taxRate
            sortOrder = 0
        }
        val taxi = aTripExpense(
            invoiceItem = item,
            kind = TripExpenseKind.TRANSPORT,
            description = "Taxifahrt",
            travelDate = LocalDate.of(2026, 4, 10),
            quantity = BigDecimal.ONE,
            unit = InvoiceItemUnit.PIECE,
            unitPriceNet = Money.of("20.00"),
            receipt = receipt,
            sortOrder = 1,
        ).also { it.calculateNetAmount() }
        val mileage = aTripExpense(
            invoiceItem = item,
            kind = TripExpenseKind.MILEAGE,
            description = "Kilometerpauschale",
            quantity = BigDecimal("50"),
            unit = InvoiceItemUnit.PIECE,
            unitPriceNet = Money.of("0.30"),
            sortOrder = 2,
        ).also { it.calculateNetAmount() }
        item.addTripExpense(taxi)
        item.addTripExpense(mileage)
        item.calculateAmounts()
        invoice.addItem(item)
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val dto = invoiceReadRepository.findInvoiceDetailDto(invoiceId)

        val trip = dto!!.items.single().trip!!
        assertEquals(2, trip.expenses.size)
        val expensesByKind = trip.expenses.associateBy { it.kind }
        val taxiRow = expensesByKind.getValue(TripExpenseKind.TRANSPORT.name)
        assertNotNull(taxiRow.receiptId)
        assertEquals("Taxifahrt", taxiRow.description)
        assertEquals(0, BigDecimal("20.00").compareTo(taxiRow.unitPriceNet.amount))
        val mileageRow = expensesByKind.getValue(TripExpenseKind.MILEAGE.name)
        assertNull(mileageRow.receiptId)
        assertEquals(0, BigDecimal("50").compareTo(mileageRow.quantity))
    }

    @Test
    @Transactional
    fun `findInvoiceDetailDto reports a null resultRefs and kositReport for a generation job that has neither`() {
        val invoice = Invoice().edit {
            invoiceDate = LocalDate.of(2026, 4, 10)
        }
        em.persist(invoice)
        em.flush()
        val job = aGenerationJob {
            this.invoice = invoice
            jobType = "PDF"
            status = GenerationJobStatus.PENDING
        }
        em.persist(job)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val dto = invoiceReadRepository.findInvoiceDetailDto(invoiceId)

        assertNotNull(dto?.latestGenerationJob)
        assertNull(dto?.latestGenerationJob?.resultRefs)
        assertNull(dto?.latestGenerationJob?.kositReport)
        assertEquals("PENDING", dto?.latestGenerationJob?.status)
    }

    @Test
    @Transactional
    fun `findInvoiceDetailDto fills the cash discount from a live payment term's discount rate and day-count`() {
        val sender = aSenderProfile {
            name = "Skonto Sender GmbH"
            invoicePrefix = "RR-CD-"
        }
        val taxRate = TaxRate().edit {
            code = "IT_RR_CASHDISC_DE_19"
            category = "S"
            name = "USt 19"
            isSelectable = false
            rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE
            validFrom = LocalDate.of(2024, 1, 1)
            isDefault = false
        }
        val paymentTerm = aPaymentTerm {
            changeName(em.find(de.littletrotter.domain.reference.Language::class.java, 1)!!, "14 Tage 2% Skonto")
            dueInDays = 14
            discountPercent = DiscountPercent(BigDecimal("2"))
            discountDays = 7
        }
        em.persist(sender)
        em.persist(taxRate)
        em.persist(paymentTerm)

        val invoice = Invoice().edit {
            senderProfile = sender
            this.paymentTerm = paymentTerm
            invoiceDate = LocalDate.of(2026, 5, 1)
        }
        val item = InvoiceItem().edit {
            positionId = "1"
            category = InvoiceItemCategory.SERVICE
            description = "Leistung"
            quantity = BigDecimal.ONE
            unit = InvoiceItemUnit.FLAT_RATE
            unitPriceNet = Money(BigDecimal("100.00"))
            taxRateRef = taxRate
            sortOrder = 0
        }
        invoice.addItem(item)
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val dto = invoiceReadRepository.findInvoiceDetailDto(invoiceId)

        val discount = dto?.cashDiscount
        assertNotNull(discount)
        assertEquals(0, BigDecimal("2").compareTo(discount!!.percent))
        assertEquals(7, discount.days)
        assertEquals("2026-05-08", discount.deadline)
        assertEquals(0, BigDecimal("2.38").compareTo(discount.amount.amount))
        assertEquals(0, BigDecimal("116.62").compareTo(discount.payableAmount.amount))
    }

    @Test
    @Transactional
    fun `findInvoiceDetailDto fills the cash discount from the frozen payment-term snapshot, not the live term`() {
        val sender = aSenderProfile {
            name = "Skonto Snapshot Sender GmbH"
            invoicePrefix = "RR-CDS-"
        }
        val taxRate = TaxRate().edit {
            code = "IT_RR_CASHDISCSNAP_DE_19"
            category = "S"
            name = "USt 19"
            isSelectable = false
            rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE
            validFrom = LocalDate.of(2024, 1, 1)
            isDefault = false
        }
        // The live term's discount is deliberately different from the frozen snapshot's — the
        // snapshot must win, same rule as Invoice.cashDiscount().
        val paymentTerm = aPaymentTerm {
            changeName(em.find(de.littletrotter.domain.reference.Language::class.java, 1)!!, "30 Tage 5% Skonto")
            dueInDays = 30
            discountPercent = DiscountPercent(BigDecimal("5"))
            discountDays = 30
        }
        em.persist(sender)
        em.persist(taxRate)
        em.persist(paymentTerm)

        val invoice = Invoice().edit {
            senderProfile = sender
            this.paymentTerm = paymentTerm
            paymentTermSnapshot = InvoicePaymentTermSnapshot.of(
                aPaymentTerm(
                    name = "14 Tage 2% Skonto",
                    dueInDays = 14,
                    discountPercent = DiscountPercent(BigDecimal("2")),
                    discountDays = 7,
                ),
                "de",
            )
            invoiceDate = LocalDate.of(2026, 5, 1)
        }
        val item = InvoiceItem().edit {
            positionId = "1"
            category = InvoiceItemCategory.SERVICE
            description = "Leistung"
            quantity = BigDecimal.ONE
            unit = InvoiceItemUnit.FLAT_RATE
            unitPriceNet = Money(BigDecimal("100.00"))
            taxRateRef = taxRate
            sortOrder = 0
        }
        invoice.addItem(item)
        invoice.recalculateTotals()
        em.persist(invoice)
        em.flush()
        val invoiceId = invoice.id!!
        em.clear()

        val dto = invoiceReadRepository.findInvoiceDetailDto(invoiceId)

        val discount = dto?.cashDiscount
        assertNotNull(discount)
        assertEquals(0, BigDecimal("2").compareTo(discount!!.percent))
        assertEquals(7, discount.days)
        assertEquals("2026-05-08", discount.deadline)
    }
}
