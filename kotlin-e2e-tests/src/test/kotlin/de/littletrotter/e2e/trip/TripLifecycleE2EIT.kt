package de.littletrotter.e2e.trip

import de.littletrotter.e2e.receipts.receiptUploadService
import de.littletrotter.domain.invoice.replaceReceipts
import de.littletrotter.application.export.port.outbound.TripWorkbookExportPort
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.InvoiceReverseChargeService
import de.littletrotter.application.invoice.InvoiceCancellationService
import de.littletrotter.application.invoice.PerDiemEntryCommand
import de.littletrotter.application.invoice.TripCommand
import de.littletrotter.application.invoice.TripExpenseCommand
import de.littletrotter.application.invoice.TripService
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.shared.tax.VatIdVerificationStatus
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.Currency
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.Money
import de.littletrotter.shared.wording.TermResolver
import de.littletrotter.shared.wording.TermSubstitution
import de.littletrotter.shared.wording.WordingProfile
import de.littletrotter.xpdf.testsupport.TypstContainerRenderer
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.sql.DriverManager
import java.sql.SQLException
import java.time.LocalDate
import java.util.Properties
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.edit

/**
 * The business-trip lifecycle (TL-1..TL-7) end to end against real Postgres: a `TRIP`
 * position built through the real [TripService], its totals/tax resolution/position numbering
 * proven against the real [InvoiceLifecycleService]/[InvoiceCancellationService], the issued-invoice freeze guard
 * proven through a genuine second connection (never the app's own `lt_app` session), and deletion
 * proven to cascade children while leaving the receipt pool untouched.
 *
 * Fixture idiom follows [de.littletrotter.e2e.invoice.InvoiceGenerationLockE2EIT]/
 * [de.littletrotter.e2e.invoice.RecalculationAbortE2EIT]: parties+tax rate built directly via
 * [EntityManager], trip content built exclusively through [TripService.addTrip] (never a hand-built
 * [de.littletrotter.domain.invoice.TripExpense] row) so the real command validation/tax-resolution/
 * amount-derivation logic runs for real.
 *
 * Every read of a managed [Invoice]'s lazy associations (`items`, `tripExpenses`,
 * `perDiemEntries`, `receipts`) happens inside [withInvoice], its own fresh
 * `QuarkusTransaction.requiringNew()` — the entity `em.find` alone returns is detached the
 * moment that call block ends, and touching a lazy collection afterward throws
 * `LazyInitializationException` (caught live against real Postgres while building this suite).
 *
 * A `TRIP` position's own `trip_expense`/`travel_expense_entry` rows can never be DELETEd again
 * once the owning invoice is issued (`trg_trip_expense_before_write`) — the fixtures that
 * reach `ISSUED`/`CANCELLED` with a trip in place are therefore deliberately excluded from
 * [createdInvoiceIds] right after that transition and left in the shared, never-cleaned database,
 * the same convention `TripExpenseIT`'s own class KDoc documents.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class TripLifecycleE2EIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var lifecycleService: InvoiceLifecycleService

    @Inject
    lateinit var itemMutationService: InvoiceItemMutationService

    @Inject
    lateinit var reverseChargeService: InvoiceReverseChargeService

    @Inject
    lateinit var tripService: TripService

    @Inject
    lateinit var cancellationService: InvoiceCancellationService

    @Inject
    lateinit var featureSettingsGate: FeatureSettingsGate

    @Inject
    lateinit var receiptRepository: ReceiptRepository

    @Inject
    lateinit var invoiceRepository: InvoiceRepository

    @Inject
    lateinit var documentStore: DocumentStore

    @Inject
    lateinit var tripWorkbookExportPort: TripWorkbookExportPort

    private val createdInvoiceIds = mutableListOf<Long>()

    private fun germanBundleTranslate(): (String) -> String {
        val bundle = Properties().also { props ->
            javaClass.classLoader.getResourceAsStream("i18n/messages_de.properties")!!.use {
                props.load(it.reader(Charsets.UTF_8))
            }
        }
        return { key ->
            val raw = bundle.getProperty(key) ?: key
            TermSubstitution.apply(raw) { term ->
                TermResolver.resolve(WordingProfile.DEFAULT, java.util.Locale.GERMAN, term)
            }
        }
    }

    @AfterEach
    fun cleanup() {
        // Reset first and unconditionally -- a removal failure below (an issued trip fixture this
        // test forgot to exclude) must never leave a shared, never-cleaned-between-classes feature
        // flag flipped for whichever test runs next.
        forcePerDiemUiEnabled()
        // Each id gets its own transaction, tolerated individually: one undeletable fixture must
        // never roll back the removal of every other id in the same batch.
        createdInvoiceIds.forEach { id ->
            runCatching { QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, id)?.let(em::remove) } }
        }
        createdInvoiceIds.clear()
    }

    /**
     * Forces `feature_setting.enabled = true` for `PER_DIEM_UI` via a native UPDATE, bypassing
     * [FeatureSettingsGate.setEnabled]'s own JPA `@Version` optimistic-lock check entirely.
     *
     * [FeatureSettingsGate.setEnabled] itself threw `OptimisticLockException` here against real
     * Postgres, reproducibly (caught live building this suite, `TL-7`'s own restore step) -- two
     * back-to-back `setEnabled` calls in the same test method (`false` then `true`) raced on the
     * SAME row's version, most likely through [de.littletrotter.application.shared
     * .ShortLivedSettingsCache]'s own short-TTL caching of the loaded entity/its version across
     * those calls. A native UPDATE carries no `@Version` predicate at all, so it can never hit
     * that check; [FeatureSettingsGate.invalidate] still clears the read-side cache afterward so
     * the very next [FeatureSettingsGate.isEnabled] call sees the reset value.
     */
    private fun forcePerDiemUiEnabled() {
        runCatching {
            QuarkusTransaction.requiringNew().run {
                em.createNativeQuery("UPDATE feature_setting SET enabled = true WHERE key = '${FeatureFlag.PER_DIEM_UI.key}'").executeUpdate()
            }
        }
        featureSettingsGate.invalidate()
    }

    /** A genuinely valid, minimal one-page PDF (PDFBox-built, same idiom
     *  [de.littletrotter.e2e.receipts.ReceiptBudgetE2EIT.pdf] uses) carrying [marker] in its own
     *  text -- never a hand-typed byte literal: [de.littletrotter.application.invoice
     *  .ReceiptUploadService.upload] content-addresses receipts by SHA-256
     *  (`uq_receipt_sha256_checksum`), so two fixtures sharing identical bytes would silently
     *  dedupe onto the SAME row. The receipt's raw bytes are also what a Mustang export would
     *  carry as the BG-24 `AttachmentBinaryObject` Base64 payload were this suite's own exports
     *  (`exportPdf`) ever given a real [de.littletrotter.shared.DocumentStore] on their
     *  `XmlExportServiceImpl` — they are not, since this suite's own assertions never check
     *  embedding, only receipt links surviving a cancellation's DB-level full copy. */
    private fun receiptPdfBytes(marker: String): ByteArray {
        org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
            val page = org.apache.pdfbox.pdmodel.PDPage()
            doc.addPage(page)
            org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("E2E trip receipt marker=$marker")
                cs.endText()
            }
            val out = java.io.ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    /** Runs [block] against a freshly loaded, still-attached [Invoice] inside its own transaction
     *  — the only safe way to touch a lazy association (`items`, `tripExpenses`, `receipts`, …). */
    private fun <T> withInvoice(invoiceId: Long, block: (Invoice) -> T): T =
        QuarkusTransaction.requiringNew().call { block(em.find(Invoice::class.java, invoiceId)!!) }

    // ─── Fixture helpers (same idiom as InvoiceGenerationLockE2EIT/RecalculationAbortE2EIT) ─────

    private data class Parties(val senderId: Long, val recipientId: Long, val taxRateId: Long)

    private fun buildParties(tag: String, itemTaxRate: String = "19.00"): Parties = QuarkusTransaction.requiringNew().call {
        val sender = aSenderProfile {
            phone = "+49 30 1234567"
            name = "$tag Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Empfänger AG"
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("DE222222222")
            routingId = "PO-$tag-STANDING"
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Kundenallee 2", "20095", "Hamburg", Country.DE, true))
        em.persist(sender)
        em.persist(recipient)

        val taxRate = TaxRate().edit {
            code = "${tag}_ITEM"; category = "S"; name = "USt $itemTaxRate %"; rate = TaxRatePercent(BigDecimal(itemTaxRate))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
        }
        em.persist(taxRate)
        em.flush()
        Parties(sender.id!!, recipient.id!!, taxRate.id!!)
    }

    /** Same shape as [de.littletrotter.e2e.einvoice.EInvoiceGenerationE2EIT]'s own reverse-charge
     *  parties: an FR business recipient with a VERIFIED VAT-id qualifies for the real
     *  `EU_B2B_RC_SERVICE` seeded rule [InvoiceLifecycleService.setReverseChargeDecision] resolves. */
    private fun buildReverseChargeParties(tag: String): Parties = QuarkusTransaction.requiringNew().call {
        val sender = aSenderProfile {
            phone = "+49 30 1234567"
            name = "$tag Sender GmbH"
            invoicePrefix = "$tag-"
            registerCourt = "AG Musterstadt"
            email = Email.ofNullable("billing@$tag.example")
            taxNumber = TaxNumber("12/345/67890")
            vatId = VatId("DE111111111")
            iban = Iban.ofNullable("DE89370400440532013000")
        }
        sender.addAddress(Address.forSender(sender, Address.AddressType.BILLING, "Anbieterweg 1", "10115", "Berlin", Country.DE, true))
        val recipient = aRecipient {
            customerNumber = CustomerNumber("$tag-100")
            name = "$tag Conseil S.A.R.L."
            email = Email.ofNullable("ap@$tag.example")
            vatId = VatId("FRXX123456789")
            customerType = CustomerType.BUSINESS
            vatIdVerificationStatus = VatIdVerificationStatus.VERIFIED
            routingId = "PO-$tag-STANDING"
        }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Rue Exemple 1", "75001", "Paris", Country.FR, true))
        em.persist(sender)
        em.persist(recipient)

        val taxRate = TaxRate().edit {
            code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
        }
        em.persist(taxRate)
        em.flush()
        Parties(sender.id!!, recipient.id!!, taxRate.id!!)
    }

    private fun newInvoiceWithServiceItem(tag: String, parties: Parties, serviceNet: String = "200.00"): Long {
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        createdInvoiceIds += invoiceId
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE,
                    description = "$tag Beratung",
                    quantity = BigDecimal("2"),
                    unit = InvoiceItemUnit.HOUR,
                    unitPriceNet = Money.of(serviceNet) / BigDecimal("2"),
                    taxRateId = parties.taxRateId,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = E2eBusinessDay.today() } }
        return invoiceId
    }

    private fun mapToDto(invoiceId: Long): InvoiceDto = QuarkusTransaction.requiringNew().call {
        val invoice = em.find(Invoice::class.java, invoiceId)!!
        InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(invoice)
    }

    /** Marks [invoiceId] as never to be cascade-removed by [cleanup] — the freeze trigger
     *  `trg_trip_expense_before_write` rejects a DELETE of an issued invoice's `trip_expense` rows,
     *  which a plain `em.remove(invoice)` would otherwise attempt via cascade/orphan removal. */
    private fun excludeFromCleanup(invoiceId: Long) {
        createdInvoiceIds.remove(invoiceId)
    }

    // ─── TL-1 — happy path: one service + one trip (2 expenses, 2 per-diem days) ────────────────

    @Test
    fun `TL-1 happy - trip uses the DE reference independently of a reduced service rate`() {
        val tag = "TL1"
        val parties = buildParties(tag, itemTaxRate = "7.00")
        val perDiemRateId = QuarkusTransaction.requiringNew().call {
            val rate = PerDiemRate().edit {
                country = Country.DE; fullDayRate = Money.of("28.00"); arrivalDepartureRate = Money.of("14.00")
                inlandRules = true; isActive = true; source = "E2E TL-1"; validFrom = LocalDate.of(2024, 1, 1)
            }
            em.persist(rate); em.flush(); rate.id!!
        }
        val invoiceId = newInvoiceWithServiceItem(tag, parties, serviceNet = "200.00")

        QuarkusTransaction.requiringNew().run {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "$tag Dienstreise Hamburg",
                    expenses = listOf(
                        TripExpenseCommand(kind = TripExpenseKind.HOTEL, description = "Hotel", quantity = BigDecimal("1"), unit = InvoiceItemUnit.DAY, unitPriceNet = Money.of("90.00")),
                        TripExpenseCommand(kind = TripExpenseKind.OTHER, description = "Taxi", quantity = BigDecimal("1"), unit = InvoiceItemUnit.FLAT_RATE, unitPriceNet = Money.of("20.00")),
                    ),
                    perDiemDays = listOf(
                        PerDiemEntryCommand(date = LocalDate.of(2026, 2, 8), isArrivalDeparture = false, breakfast = false, lunch = false, dinner = false, description = "Team-Reise Verpflegung", participant = "Kundentermin", employerContribution = Money.ZERO, perDiemRateId = perDiemRateId),
                        PerDiemEntryCommand(date = LocalDate.of(2026, 2, 9), isArrivalDeparture = true, breakfast = false, lunch = false, dinner = false, description = "Team-Reise Verpflegung", participant = "Kundentermin", employerContribution = Money.ZERO, perDiemRateId = perDiemRateId),
                    ),
                ),
            )
        }

        withInvoice(invoiceId) { invoice ->
            val tripItem = invoice.itemList.single { it.category == InvoiceItemCategory.TRIP }
            val serviceItem = invoice.itemList.single { it.category == InvoiceItemCategory.SERVICE }

            assertEquals("2", tripItem.positionId, "positions stay flat and sequential")
            assertEquals(0, BigDecimal("7.00").compareTo(serviceItem.taxRateSnapshot!!.value))
            assertEquals("DE_STANDARD_19", tripItem.taxRuleCode)
            assertEquals(0, BigDecimal("19.00").compareTo(tripItem.taxRateSnapshot!!.value))
            // 90.00 (hotel) + 20.00 (taxi) + 28.00 + 14.00 (per-diem days) = 152.00
            assertEquals(0, Money.of("152.00").amount.compareTo(tripItem.netAmount.amount), "trip net = expenses + per-diem days")
            assertEquals(
                0, Money.of("352.00").amount.compareTo(invoice.totals.netAmount.amount),
                "invoice net = service (200.00) + trip (152.00)",
            )
        }

        val dto = mapToDto(invoiceId)
        val tripDto = dto.items.single { it.positionId == "2" }.trip
        assertNotNull(tripDto, "the read DTO must carry the trip detail")
        assertEquals(2, tripDto!!.expenses.size)
        assertEquals(2, tripDto.perDiemDays.size)
    }

    // ─── TL-2 — fixed price overrides the sum of parts, calculatedNet keeps the true sum ────────

    @Test
    fun `TL-2 fixed price - invoice net uses the fixed price, calculatedNet keeps the sum of parts`() {
        val tag = "TL2"
        val parties = buildParties(tag)
        val invoiceId = newInvoiceWithServiceItem(tag, parties)

        QuarkusTransaction.requiringNew().run {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "$tag Workshop-Pauschale",
                    fixedNetPrice = Money.of("500.00"),
                    expenses = listOf(
                        TripExpenseCommand(kind = TripExpenseKind.MILEAGE, description = "120 km", quantity = BigDecimal("120"), unit = InvoiceItemUnit.KILOMETER, unitPriceNet = Money.of("0.30")),
                    ),
                ),
            )
        }

        withInvoice(invoiceId) { invoice ->
            val tripItem = invoice.itemList.single { it.category == InvoiceItemCategory.TRIP }
            assertEquals(0, Money.of("500.00").amount.compareTo(tripItem.netAmount.amount), "the printed net is the fixed price")
            assertEquals(0, Money.of("36.00").amount.compareTo(tripItem.calculatedTripNet().amount), "calculatedTripNet keeps the true sum of parts, independent of the fixed price")
        }

        val dto = mapToDto(invoiceId)
        val tripDto = dto.items.single { it.category == InvoiceItemCategory.TRIP }.trip!!
        assertEquals(0, BigDecimal("500.00").compareTo(tripDto.fixedNetPrice!!.amount))
        assertEquals(0, BigDecimal("36.00").compareTo(tripDto.calculatedNet.amount))
    }

    // ─── TL-3 — reverse charge flip rewrites the trip's country reference and back ──────────────

    @Test
    fun `TL-3 reverse charge flip - the trip uses AE on and the DE reference off`() {
        val tag = "TL3"
        val parties = buildReverseChargeParties(tag)
        val invoiceId = QuarkusTransaction.requiringNew().call {
            lifecycleService.create(parties.senderId, parties.recipientId, reverseChargeDecision = false).id!!
        }
        createdInvoiceIds += invoiceId
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE, description = "$tag Beratung",
                    quantity = BigDecimal("5"), unit = InvoiceItemUnit.HOUR, unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = E2eBusinessDay.today() } }
        QuarkusTransaction.requiringNew().run {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "$tag Dienstreise Paris",
                    expenses = listOf(TripExpenseCommand(kind = TripExpenseKind.HOTEL, description = "Hotel", quantity = BigDecimal("1"), unit = InvoiceItemUnit.DAY, unitPriceNet = Money.of("100.00"))),
                ),
            )
        }

        val beforeRate = withInvoice(invoiceId) { it.itemList.single { i -> i.category == InvoiceItemCategory.TRIP }.taxRateSnapshot!!.value }
        assertEquals(0, BigDecimal("19.00").compareTo(beforeRate), "an explicit No starts on the DE reference")

        QuarkusTransaction.requiringNew().run {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            reverseChargeService.setReverseChargeDecision(invoiceId, invoice.version, true)
        }
        val (afterOnCategory, afterOnRate) = withInvoice(invoiceId) {
            val t = it.itemList.single { i -> i.category == InvoiceItemCategory.TRIP }
            t.taxCategory?.code to t.taxRateSnapshot!!.value
        }
        assertEquals("AE", afterOnCategory, "reverse charge on: the trip's own category flips to AE")
        assertEquals(0, BigDecimal.ZERO.compareTo(afterOnRate), "AE carries a 0 percent rate")

        QuarkusTransaction.requiringNew().run {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            reverseChargeService.setReverseChargeDecision(invoiceId, invoice.version, false)
        }
        val (afterOffTripCode, afterOffTripRate) = withInvoice(invoiceId) { invoice ->
            val trip = invoice.itemList.single { i -> i.category == InvoiceItemCategory.TRIP }
            trip.taxRuleCode to trip.taxRateSnapshot!!.value
        }
        assertEquals("DE_STANDARD_19", afterOffTripCode)
        assertEquals(0, BigDecimal("19.00").compareTo(afterOffTripRate))
    }

    @Test
    fun `INV-F reverse-charge trip without a main position persists AE`() {
        val parties = buildReverseChargeParties("INVFRC")
        val invoiceId = QuarkusTransaction.requiringNew().call {
            lifecycleService.create(parties.senderId, parties.recipientId, reverseChargeDecision = true).id!!
        }
        createdInvoiceIds += invoiceId

        QuarkusTransaction.requiringNew().run {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "INV-F reine RC-Dienstreise",
                    expenses = listOf(
                        TripExpenseCommand(
                            kind = TripExpenseKind.HOTEL,
                            description = "Hotel",
                            quantity = BigDecimal.ONE,
                            unit = InvoiceItemUnit.DAY,
                            unitPriceNet = Money.of("100.00"),
                        ),
                    ),
                ),
            )
        }

        withInvoice(invoiceId) { invoice ->
            assertTrue(invoice.reverseCharge)
            val trip = invoice.itemList.single()
            assertEquals(InvoiceItemCategory.TRIP, trip.category)
            assertEquals("AE", trip.taxCategory?.code)
            assertEquals(0, BigDecimal.ZERO.compareTo(trip.taxRateSnapshot!!.value))
        }
    }

    @Test
    fun `INV-F fixed zero stays explicit and uses the DE reference without a main position`() {
        val parties = buildParties("INVF0")
        val invoiceId = QuarkusTransaction.requiringNew().call {
            lifecycleService.create(parties.senderId, parties.recipientId).id!!
        }
        createdInvoiceIds += invoiceId

        QuarkusTransaction.requiringNew().run {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "INV-F Nullpreis-Pauschale",
                    fixedNetPrice = Money.ZERO,
                    expenses = listOf(
                        TripExpenseCommand(
                            kind = TripExpenseKind.HOTEL,
                            description = "Interne Hotelkosten",
                            quantity = BigDecimal.ONE,
                            unit = InvoiceItemUnit.DAY,
                            unitPriceNet = Money.of("80.00"),
                        ),
                    ),
                ),
            )
        }

        withInvoice(invoiceId) { invoice ->
            val trip = invoice.itemList.single()
            assertEquals(0, BigDecimal.ZERO.compareTo(trip.tripFixedNetPrice!!.amount))
            assertEquals(0, BigDecimal.ZERO.compareTo(trip.netAmount.amount))
            assertEquals(0, BigDecimal("80.00").compareTo(trip.calculatedTripNet().amount))
            assertEquals("DE_STANDARD_19", trip.taxRuleCode)
        }
    }

    @Test
    fun `INV-G decimal travel time distinguishes missing and explicit zero rates with optional text`() {
        val parties = buildParties("INVG")
        val invoiceId = QuarkusTransaction.requiringNew().call {
            lifecycleService.create(parties.senderId, parties.recipientId).id!!
        }
        createdInvoiceIds += invoiceId

        QuarkusTransaction.requiringNew().run {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "INV-G Reisezeit",
                    expenses = listOf(
                        TripExpenseCommand(
                            kind = TripExpenseKind.TRAVEL_TIME,
                            description = "Anfahrt zum Kunden",
                            quantity = BigDecimal("1.5"),
                            unit = InvoiceItemUnit.HOUR,
                            unitPriceNet = Money.of("80.00"),
                            rateProvided = true,
                        ),
                        TripExpenseCommand(
                            kind = TripExpenseKind.TRAVEL_TIME,
                            description = "",
                            quantity = BigDecimal("0.5"),
                            unit = InvoiceItemUnit.HOUR,
                            unitPriceNet = Money.ZERO,
                            rateProvided = false,
                        ),
                        TripExpenseCommand(
                            kind = TripExpenseKind.TRAVEL_TIME,
                            description = "",
                            quantity = BigDecimal("0.25"),
                            unit = InvoiceItemUnit.HOUR,
                            unitPriceNet = Money.ZERO,
                            rateProvided = true,
                        ),
                    ),
                ),
            )
        }

        withInvoice(invoiceId) { invoice ->
            val expenses = invoice.itemList.single().tripExpenseList.sortedBy { it.sortOrder }
            assertEquals(0, BigDecimal("120.00").compareTo(expenses[0].netAmount.amount))
            assertEquals("Anfahrt zum Kunden", expenses[0].description)
            assertFalse(expenses[1].rateProvided)
            assertEquals(de.littletrotter.domain.invoice.TripExpense.TRAVEL_TIME_KULANZ_TEXT, expenses[1].description)
            assertTrue(expenses[2].rateProvided)
            assertTrue(expenses[2].description.isBlank(), "an explicit zero rate must not force the goodwill text")
        }
    }

    // ─── TL-4 — send freezes the trip: the freeze guard rejects an UPDATE via a genuine second connection ──

    @Test
    fun `TL-4 send freezes the trip - RS-11 rejects an UPDATE on trip_expense through the bootstrap connection, journal rows exist`() {
        val tag = "TL4"
        val parties = buildParties(tag)
        val invoiceId = newInvoiceWithServiceItem(tag, parties)
        val expenseId = QuarkusTransaction.requiringNew().call {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "$tag Dienstreise Köln",
                    expenses = listOf(TripExpenseCommand(kind = TripExpenseKind.HOTEL, description = "Hotel", quantity = BigDecimal("1"), unit = InvoiceItemUnit.DAY, unitPriceNet = Money.of("90.00"))),
                ),
            )
            em.find(Invoice::class.java, invoiceId)!!.itemList.single { it.category == InvoiceItemCategory.TRIP }.tripExpenseList.single().id!!
        }

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        excludeFromCleanup(invoiceId)
        val status = withInvoice(invoiceId) { it.status }
        assertEquals(InvoiceStatus.ISSUED, status)

        // The bootstrap/admin connection -- never the app's own lt_app session (PostgresTestResource
        // sets both as system properties precisely so a test can reach the DB as a second, distinct
        // role/connection). Proves the guard is a genuine DB-level trigger, not merely an lt_app
        // grant the application connection happens to lack.
        val jdbcUrl = System.getProperty("quarkus.datasource.jdbc.url")
        val adminUser = System.getProperty("test.postgres.admin-username")
        val adminPassword = System.getProperty("test.postgres.admin-password")
        assertNotNull(jdbcUrl); assertNotNull(adminUser); assertNotNull(adminPassword)

        DriverManager.getConnection(jdbcUrl, adminUser, adminPassword).use { conn ->
            conn.createStatement().use { st ->
                val ex = assertThrows(SQLException::class.java) {
                    st.executeUpdate("UPDATE trip_expense SET description = 'nachtraeglich geaendert' WHERE id = $expenseId")
                }
                assertTrue(ex.message?.contains("issued") == true, "expected the RS-11 freeze guard's own message, got: ${ex.message}")
            }
        }

        val stillOriginal = QuarkusTransaction.requiringNew().call<String> {
            em.createNativeQuery("SELECT description FROM trip_expense WHERE id = $expenseId").singleResult as String
        }
        assertEquals("Hotel", stillOriginal, "the rejected UPDATE must not have changed the row")

        // TRIP_EXPENSE_CREATED = 9 (chk_invoice_audit_journal_event / InvoiceAuditEvent ordinal).
        val journalCount = QuarkusTransaction.requiringNew().call<Long> {
            (
                em.createNativeQuery(
                    "SELECT COUNT(*) FROM invoice_audit_journal WHERE invoice_id = $invoiceId AND event = 9",
                ).singleResult as Number
                ).toLong()
        }
        assertTrue(journalCount >= 1L, "expected at least one TRIP_EXPENSE_CREATED journal row for invoice #$invoiceId")
    }

    // ─── TL-5 — cancellation pair: negated fixed price/unit prices, receipt links on both, archive bytes unchanged ──

    /** Exports [dto] and returns the PDF key the export itself reported (per-attempt) with its bytes. */
    private fun exportPdfWithKey(dto: InvoiceDto, jobId: String): Pair<String, ByteArray> {
        val service = PdfExportServiceImplTestFactory.create(
            xmlExportService = XmlExportServiceImpl(null),
            reportRenderer = TypstContainerRenderer(),
            documentStore = documentStore,
            kositValidator = object : KositValidatorPort {
                override fun validate(xml: String, timeoutMs: Long) = KositValidationResult(status = KositOutcome.VALID)
            },
        )
        val result = service.performExport(dto, jobId)
        return result.objectKey to documentStore.get(result.objectKey)
    }

    @Test
    fun `TL-5 cancellation pair - fixed-price trip copied with negated fixed price and unit prices, receipt links on both, original archive bytes unchanged`() {
        val tag = "TL5"
        val parties = buildParties(tag)
        val invoiceId = newInvoiceWithServiceItem(tag, parties)

        val receiptId = receiptUploadService(receiptRepository, documentStore, de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")))
            .upload("$tag-beleg.pdf", "E2E $tag Beleg", receiptPdfBytes(tag), E2eBusinessDay.today()).id!!

        val tripPositionId = QuarkusTransaction.requiringNew().call {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "$tag Workshop-Pauschale",
                    fixedNetPrice = Money.of("500.00"),
                    expenses = listOf(TripExpenseCommand(kind = TripExpenseKind.HOTEL, description = "Hotel", quantity = BigDecimal("1"), unit = InvoiceItemUnit.DAY, unitPriceNet = Money.of("90.00"))),
                ),
            )
            em.find(Invoice::class.java, invoiceId)!!.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId
        }
        QuarkusTransaction.requiringNew().run {
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            val item = invoice.itemList.single { it.category == InvoiceItemCategory.TRIP }
            val receipt = em.find(de.littletrotter.domain.masterdata.Receipt::class.java, receiptId)!!
            item.replaceReceipts(item.receiptList + receipt)
            // Invoice-level attachment is a separate collection from the item-level one --
            // both must be populated here for this test to actually exercise
            // InvoiceCancellationService.createCreditNote's own "receipt links on both levels" copy.
            invoice.replaceReceipts(invoice.receiptList + receipt)
        }

        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        excludeFromCleanup(invoiceId)

        val firstDto = mapToDto(invoiceId)
        val (objectKey, firstBytes) = exportPdfWithKey(firstDto, "$tag-first")

        val creditNoteId = QuarkusTransaction.requiringNew().call { cancellationService.createCreditNote(invoiceId, "E2E TL-5 Storno") }.id!!
        // The credit note is born CANCELLED with its own trip_expense rows -- equally undeletable
        // once issued, so it is likewise left in the shared, never-cleaned database.

        withInvoice(creditNoteId) { creditNote ->
            val creditTrip = creditNote.itemList.single { it.category == InvoiceItemCategory.TRIP }
            assertEquals(tripPositionId, creditTrip.positionId, "the credit note keeps the original's position id")
            assertEquals(0, BigDecimal("-500.00").compareTo(creditTrip.tripFixedNetPrice!!.amount), "the fixed price is negated")
            assertEquals(0, BigDecimal("-90.00").compareTo(creditTrip.tripExpenseList.single().unitPriceNet.amount), "the expense unit price is negated")
            assertTrue(creditTrip.receiptList.any { it.id == receiptId }, "the credit note's trip line carries the same receipt link")
            assertTrue(creditNote.receiptList.any { it.id == receiptId }, "the credit note also carries the invoice-level receipt link")
        }

        withInvoice(invoiceId) { original ->
            assertEquals(InvoiceStatus.CANCELLED, original.status)
            val originalTrip = original.itemList.single { it.category == InvoiceItemCategory.TRIP }
            assertEquals(0, BigDecimal("500.00").compareTo(originalTrip.tripFixedNetPrice!!.amount), "the original itself is left untouched")
            assertTrue(originalTrip.receiptList.any { it.id == receiptId }, "the original keeps its own receipt link too")
        }

        // Archive-bytes pattern (InvoiceGenerationLockE2EIT): nothing re-exports the original,
        // so its archived PDF bytes are unchanged.
        assertArrayEquals(firstBytes, documentStore.get(objectKey), "the original's archived PDF must stay byte-identical after storno")
    }

    /** README border case 4 for a trip priced from its parts: the cancellation copy nets exactly the
     *  negative of the original although per-diem days are never negated themselves. */
    @Test
    fun `TL-5 cancellation of a per-diem trip without a fixed price carries the exact negative net`() {
        val tag = "TL5B"
        val parties = buildParties(tag)
        val invoiceId = newInvoiceWithServiceItem(tag, parties)
        val perDiemRateId = QuarkusTransaction.requiringNew().call {
            val rate = PerDiemRate().edit {
                country = Country.DE; fullDayRate = Money.of("28.00"); arrivalDepartureRate = Money.of("14.00")
                inlandRules = true; isActive = true; source = "E2E TL-5B"; validFrom = LocalDate.of(2024, 1, 1)
            }
            em.persist(rate); em.flush(); rate.id!!
        }
        QuarkusTransaction.requiringNew().run {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "$tag Dienstreise mit Verpflegung",
                    expenses = listOf(TripExpenseCommand(kind = TripExpenseKind.MILEAGE, description = "120 km", quantity = BigDecimal("120"), unit = InvoiceItemUnit.KILOMETER, unitPriceNet = Money.of("0.30"))),
                    perDiemDays = listOf(
                        PerDiemEntryCommand(date = LocalDate.of(2026, 2, 8), isArrivalDeparture = false, breakfast = false, lunch = false, dinner = false, description = "Team-Reise Verpflegung", participant = "Kundentermin", employerContribution = Money.ZERO, perDiemRateId = perDiemRateId),
                    ),
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        excludeFromCleanup(invoiceId)

        val originalNet = withInvoice(invoiceId) { it.itemList.single { i -> i.category == InvoiceItemCategory.TRIP }.netAmount.amount }
        // 120 km x 0.30 = 36.00 (expense) + 28.00 (per-diem day) = 64.00.
        assertEquals(0, BigDecimal("64.00").compareTo(originalNet))

        val creditNoteId = QuarkusTransaction.requiringNew().call { cancellationService.createCreditNote(invoiceId, "E2E TL-5B Storno") }.id!!

        val creditNet = withInvoice(creditNoteId) { it.itemList.single { i -> i.category == InvoiceItemCategory.TRIP }.netAmount.amount }
        // 36.00 expense + 28.00 per-diem day = 64.00 -> the cancellation copy nets exactly -64.00: the
        // expense lines are negated, the per-diem day stays positive and is subtracted instead.
        assertEquals(0, BigDecimal("-64.00").compareTo(creditNet))
    }

    // ─── TL-6 — deleting the trip cascades children and links; receipts stay in the pool ────────

    @Test
    fun `TL-6 delete trip - children and receipt links are gone, the receipt itself remains in the pool, totals update`() {
        val tag = "TL6"
        val parties = buildParties(tag)
        val invoiceId = newInvoiceWithServiceItem(tag, parties)

        val receiptId = receiptUploadService(receiptRepository, documentStore, de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")))
            .upload("$tag-beleg.pdf", "E2E $tag Beleg", receiptPdfBytes(tag), E2eBusinessDay.today()).id!!

        val (tripItemId, expenseId, positionId) = QuarkusTransaction.requiringNew().call<Triple<Long, Long, String>> {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "$tag Dienstreise München",
                    expenses = listOf(TripExpenseCommand(kind = TripExpenseKind.OTHER, description = "Taxi", quantity = BigDecimal("1"), unit = InvoiceItemUnit.FLAT_RATE, unitPriceNet = Money.of("30.00"))),
                ),
            )
            val invoice = em.find(Invoice::class.java, invoiceId)!!
            val item = invoice.itemList.single { it.category == InvoiceItemCategory.TRIP }
            val receipt = em.find(de.littletrotter.domain.masterdata.Receipt::class.java, receiptId)!!
            item.replaceReceipts(item.receiptList + receipt)
            Triple(item.id!!, item.tripExpenseList.single().id!!, item.positionId)
        }
        val netBeforeDelete = withInvoice(invoiceId) { it.totals.netAmount }

        QuarkusTransaction.requiringNew().run { itemMutationService.deleteItemByPosition(invoiceId, positionId) }

        val expenseCount = QuarkusTransaction.requiringNew().call<Long> {
            (em.createNativeQuery("SELECT COUNT(*) FROM trip_expense WHERE id = $expenseId").singleResult as Number).toLong()
        }
        assertEquals(0L, expenseCount, "the trip's expense row must be gone")
        val junctionCount = QuarkusTransaction.requiringNew().call<Long> {
            (em.createNativeQuery("SELECT COUNT(*) FROM invoice_item_receipt WHERE invoice_item_id = $tripItemId").singleResult as Number).toLong()
        }
        assertEquals(0L, junctionCount, "the item-level receipt link must be gone")
        val stillInPool = QuarkusTransaction.requiringNew().call { receiptRepository.findById(receiptId) }
        assertNotNull(stillInPool, "the receipt itself must remain in the pool")

        withInvoice(invoiceId) { invoiceAfter ->
            assertTrue(invoiceAfter.itemList.none { it.category == InvoiceItemCategory.TRIP }, "the trip position itself is gone")
            assertTrue(invoiceAfter.totals.netAmount.amount < netBeforeDelete.amount, "totals must go down once the trip is removed")
        }
    }

    // ─── TL-7 — per-diem flag off rejects new days, still accepts a trip without days ───────────

    @Test
    fun `TL-7 per-diem flag off - addTrip with days is rejected, a trip without days is still accepted`() {
        val tag = "TL7"
        val parties = buildParties(tag)
        val invoiceId = newInvoiceWithServiceItem(tag, parties)
        val perDiemRateId = QuarkusTransaction.requiringNew().call {
            val rate = PerDiemRate().edit {
                country = Country.DE; fullDayRate = Money.of("28.00"); arrivalDepartureRate = Money.of("14.00")
                inlandRules = true; isActive = true; source = "E2E TL-7"; validFrom = LocalDate.of(2024, 1, 1)
            }
            em.persist(rate); em.flush(); rate.id!!
        }

        featureSettingsGate.setEnabled(FeatureFlag.PER_DIEM_UI, false, "e2e-tl7")
        try {
            val ex = assertThrows(InvoiceValidationException::class.java) {
                QuarkusTransaction.requiringNew().run {
                    tripService.addTrip(
                        invoiceId,
                        TripCommand(
                            title = "$tag Dienstreise mit Tagen",
                            perDiemDays = listOf(
                                PerDiemEntryCommand(date = LocalDate.of(2026, 2, 8), isArrivalDeparture = false, breakfast = false, lunch = false, dinner = false, description = "Team-Reise Verpflegung", participant = "Kundentermin", employerContribution = Money.ZERO, perDiemRateId = perDiemRateId),
                            ),
                        ),
                    )
                }
            }
            assertEquals(InvoiceValidationError.PER_DIEM_FEATURE_DISABLED, ex.error)
            withInvoice(invoiceId) { invoice ->
                assertTrue(invoice.itemList.none { it.category == InvoiceItemCategory.TRIP }, "the rejected command must not have created a trip position")
            }

            // A trip without any per-diem days must still be accepted while the flag is off.
            QuarkusTransaction.requiringNew().run {
                tripService.addTrip(
                    invoiceId,
                    TripCommand(
                        title = "$tag Dienstreise ohne Tage",
                        expenses = listOf(TripExpenseCommand(kind = TripExpenseKind.OTHER, description = "Sonstiges", quantity = BigDecimal("1"), unit = InvoiceItemUnit.FLAT_RATE, unitPriceNet = Money.of("10.00"))),
                    ),
                )
            }
            withInvoice(invoiceId) { invoice ->
                val trip = invoice.itemList.single { it.category == InvoiceItemCategory.TRIP }
                assertTrue(trip.perDiemEntryList.isEmpty())
                assertEquals(1, trip.tripExpenseList.size)
            }
        } finally {
            // Restored here, in the same test method's own execution context, rather than relying
            // solely on @AfterEach's own reset -- see forcePerDiemUiEnabled's own KDoc for why this
            // goes around FeatureSettingsGate.setEnabled entirely (its own OptimisticLockException,
            // caught live building this suite).
            forcePerDiemUiEnabled()
        }
    }

    // ─── README border case 2 — existing per-diem days must survive an update after the flag is
    //     switched off ("Abschnitt verborgen, vorhandene Tage bleiben") ─────────────────────────

    /**
     * DEFECT — README border case 2 promises that switching
     * `PER_DIEM_UI` off only hides the per-diem section and never touches days a trip already
     * has. [TripService.applyTripFields] (shared by `addTrip`/`updateTrip`) unconditionally runs
     * `item.perDiemEntries.clear()` and rebuilds solely from `command.perDiemDays`; it has no
     * "leave untouched" branch for an update whose command simply never resubmits the existing
     * days (exactly what a real UI update does once the section is hidden).
     * `TripCommandValidator.validatePerDiemGate` only ever rejects a genuinely NEW day while the
     * flag is off (`!alreadyHasPerDiemDays`) — it has no say over an update that silently omits
     * days the trip already had, so those are deleted here, reproducibly, with no validation error
     * of any kind. This test is written to the README's own promise (existing days preserved), not
     * to this observed behaviour — it is RED against current `kotlin-invoice` code; see this
     * suite's own final report for the evidence (expected 1, observed 0) and the recommended fix
     * (either `updateTrip` must skip the `perDiemEntries` rebuild entirely when `command
     * .perDiemDays` is empty AND the flag is off AND the target already has days, or the UI must
     * always resubmit the existing days even while its own section is hidden).
     */
    @Test
    fun `border case 2 - existing per-diem days must survive an update once the flag is switched off`() {
        val tag = "BC2"
        val parties = buildParties(tag)
        val invoiceId = newInvoiceWithServiceItem(tag, parties)
        val perDiemRateId = QuarkusTransaction.requiringNew().call {
            val rate = PerDiemRate().edit {
                country = Country.DE; fullDayRate = Money.of("28.00"); arrivalDepartureRate = Money.of("14.00")
                inlandRules = true; isActive = true; source = "E2E BC2"; validFrom = LocalDate.of(2024, 1, 1)
            }
            em.persist(rate); em.flush(); rate.id!!
        }
        val positionId = QuarkusTransaction.requiringNew().call {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "$tag Dienstreise mit Verpflegung",
                    expenses = listOf(TripExpenseCommand(kind = TripExpenseKind.OTHER, description = "Taxi", quantity = BigDecimal("1"), unit = InvoiceItemUnit.FLAT_RATE, unitPriceNet = Money.of("20.00"))),
                    perDiemDays = listOf(
                        PerDiemEntryCommand(date = LocalDate.of(2026, 2, 8), isArrivalDeparture = false, breakfast = false, lunch = false, dinner = false, description = "Team-Reise Verpflegung", participant = "Kundentermin", employerContribution = Money.ZERO, perDiemRateId = perDiemRateId),
                    ),
                ),
            )
            em.find(Invoice::class.java, invoiceId)!!.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId
        }
        val daysBefore = withInvoice(invoiceId) { it.itemList.single { i -> i.category == InvoiceItemCategory.TRIP }.perDiemEntryList.size }
        assertEquals(1, daysBefore, "fixture setup: the trip must start with exactly one per-diem day")

        featureSettingsGate.setEnabled(FeatureFlag.PER_DIEM_UI, false, "e2e-bc2")
        try {
            // A real UI update once the per-diem section is hidden: only the expense changes,
            // perDiemDays is never resubmitted -- exactly the shape README border case 2 describes.
            QuarkusTransaction.requiringNew().run {
                tripService.updateTrip(
                    invoiceId, positionId,
                    TripCommand(
                        title = "$tag Dienstreise mit Verpflegung (aktualisiert)",
                        expenses = listOf(TripExpenseCommand(kind = TripExpenseKind.OTHER, description = "Taxi aktualisiert", quantity = BigDecimal("1"), unit = InvoiceItemUnit.FLAT_RATE, unitPriceNet = Money.of("25.00"))),
                    ),
                )
            }

            val daysAfter = withInvoice(invoiceId) { it.itemList.single { i -> i.category == InvoiceItemCategory.TRIP }.perDiemEntryList.size }
            assertEquals(1, daysAfter, "README border case 2: existing per-diem days must survive an update made while the flag is off")
        } finally {
            forcePerDiemUiEnabled()
        }
    }

    // ─── README border case 9 — trip amounts stay in the invoice currency, no FX conversion ──────

    @Test
    fun `border case 9 - trip amounts stay in the invoice currency, no FX conversion is ever applied`() {
        val tag = "BC9"
        val parties = buildParties(tag)
        val invoiceId = newInvoiceWithServiceItem(tag, parties, serviceNet = "200.00")
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { currency = Currency.USD } }

        QuarkusTransaction.requiringNew().run {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "$tag Dienstreise USD",
                    expenses = listOf(
                        TripExpenseCommand(kind = TripExpenseKind.HOTEL, description = "Hotel", quantity = BigDecimal("1"), unit = InvoiceItemUnit.DAY, unitPriceNet = Money.of("90.00")),
                        TripExpenseCommand(kind = TripExpenseKind.OTHER, description = "Taxi", quantity = BigDecimal("1"), unit = InvoiceItemUnit.FLAT_RATE, unitPriceNet = Money.of("20.00")),
                    ),
                ),
            )
        }

        withInvoice(invoiceId) { invoice ->
            assertEquals(Currency.USD, invoice.currency, "fixture setup: the invoice must actually be billed in a non-EUR currency")
            val tripItem = invoice.itemList.single { it.category == InvoiceItemCategory.TRIP }
            // README border case 9: items are entered in the invoice's own currency (Money itself
            // is currency-agnostic, Currency.USD here); the trip's net is exactly the sum of the
            // entered amounts -- no FX factor of any kind is ever applied on top of it.
            assertEquals(0, Money.of("110.00").amount.compareTo(tripItem.netAmount.amount), "90.00 (hotel) + 20.00 (taxi) must reach the invoice total unconverted")
        }
    }

    // ─── README border case 10 — a trip period outside the invoice's own service period ─────────

    @Test
    fun `border case 10 - a trip period outside the invoice service period is accepted and stored as given`() {
        val tag = "BC10"
        val parties = buildParties(tag)
        val invoiceId = newInvoiceWithServiceItem(tag, parties)
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = LocalDate.of(2026, 6, 15) } }

        val tripPeriodFrom = LocalDate.of(2025, 1, 10)
        val tripPeriodTo = LocalDate.of(2025, 1, 12)
        QuarkusTransaction.requiringNew().run {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "$tag Dienstreise weit vor dem Leistungsdatum",
                    periodFrom = tripPeriodFrom, periodTo = tripPeriodTo,
                    expenses = listOf(TripExpenseCommand(kind = TripExpenseKind.HOTEL, description = "Hotel", quantity = BigDecimal("1"), unit = InvoiceItemUnit.DAY, unitPriceNet = Money.of("90.00"))),
                ),
            )
        }

        withInvoice(invoiceId) { invoice ->
            val tripItem = invoice.itemList.single { it.category == InvoiceItemCategory.TRIP }
            // README border case 10: the trip period (BG-26) is stored exactly as given, never
            // clamped or rejected against the invoice's own delivery/service date.
            assertEquals(tripPeriodFrom, tripItem.servicePeriodFrom, "the trip period must be stored exactly as given, well outside the invoice's own service period")
            assertEquals(tripPeriodTo, tripItem.servicePeriodTo)
        }
    }

    // ─── Two non-contiguous per-diem entries: create, save, reload, trip Excel shows both ──

    /** The "mehrere Verpflegungspauschalen" requirement end to end: a 5-day trip with
     *  entries only on day 1 and day 5 (nothing on days 2-4, mirroring the editor's own "two
     *  non-adjacent entries are both valid" case) — the domain/persistence shape already supports
     *  a list of independent day entries, so this proves the real [TripService]/Postgres
     *  round trip and the trip Excel export never collapsed that back down to a contiguous range. */
    @Test
    fun `RK-13 two non-contiguous per-diem entries - create, save, reload, and the trip Excel shows both`() {
        val tag = "RK13"
        val parties = buildParties(tag)
        val perDiemRateId = QuarkusTransaction.requiringNew().call {
            val rate = PerDiemRate().edit {
                country = Country.DE; fullDayRate = Money.of("28.00"); arrivalDepartureRate = Money.of("14.00")
                inlandRules = true; isActive = true; source = "E2E $tag"; validFrom = LocalDate.of(2024, 1, 1)
            }
            em.persist(rate); em.flush(); rate.id!!
        }
        val invoiceId = newInvoiceWithServiceItem(tag, parties)
        val firstDay = LocalDate.of(2026, 3, 1)
        val lastDay = LocalDate.of(2026, 3, 5)

        val positionId = QuarkusTransaction.requiringNew().call {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "$tag Dienstreise mit Lücke",
                    periodFrom = firstDay, periodTo = lastDay,
                    perDiemDays = listOf(
                        PerDiemEntryCommand(date = firstDay, isArrivalDeparture = true, breakfast = false, lunch = false, dinner = false, description = "Team-Reise Verpflegung", participant = "Kundentermin", employerContribution = Money.ZERO, perDiemRateId = perDiemRateId),
                        PerDiemEntryCommand(date = lastDay, isArrivalDeparture = true, breakfast = false, lunch = false, dinner = false, description = "Team-Reise Verpflegung", participant = "Kundentermin", employerContribution = Money.ZERO, perDiemRateId = perDiemRateId),
                    ),
                ),
            )
            em.find(Invoice::class.java, invoiceId)!!.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId
        }

        // Reload — a genuinely fresh read, not the same transaction that wrote it.
        withInvoice(invoiceId) { invoice ->
            val tripItem = invoice.itemList.single { it.category == InvoiceItemCategory.TRIP }
            val dates = tripItem.perDiemEntryList.mapNotNull { it.travelDate }.sorted()
            assertEquals(listOf(firstDay, lastDay), dates, "both non-adjacent entries must persist, nothing on days 2-4")
        }

        val output = ByteArrayOutputStream()
        tripWorkbookExportPort.writeTripWorkbook(invoiceId, positionId, germanBundleTranslate(), output)
        XSSFWorkbook(ByteArrayInputStream(output.toByteArray())).use { wb ->
            val posten = wb.getSheet("Posten")
            assertEquals(2, posten.physicalNumberOfRows, "Posten contains only header and total because this trip has no general expenses")
            val perDiem = wb.getSheet("Verpflegung")
            assertEquals(5, perDiem.physicalNumberOfRows, "header + 2 per-diem rows + total + overview scope")
            val perDiemDates = (1..2).map { r -> perDiem.getRow(r).getCell(2).localDateTimeCellValue.toLocalDate() }
            assertEquals(listOf(firstDay, lastDay), perDiemDates.sorted(), "the dedicated per-diem sheet must carry both non-contiguous entries, not just one")
            assertEquals("2 Tage · Kundentermin", perDiem.getRow(4).getCell(0).stringCellValue)
        }
    }

    // ─── Same-date per-diem entries for two participants, own block description round-trips ──

    /** The "mehrere Teilnehmer am selben Tag" requirement end to end (the confirmed PO
     *  mockup: Frau Müller and Herr Klein both dated 12.03.2026), plus the new block-level
     *  description surviving a real save/reload — the former "one entry per date" restriction is
     *  gone, and the trip Excel export must not break on two rows sharing a date. */
    @Test
    fun `RK-15 two per-diem entries on the same date for different participants - create, save, reload, and the trip Excel shows both`() {
        val tag = "RK15"
        val parties = buildParties(tag)
        val perDiemRateId = QuarkusTransaction.requiringNew().call {
            val rate = PerDiemRate().edit {
                country = Country.DE; fullDayRate = Money.of("28.00"); arrivalDepartureRate = Money.of("14.00")
                inlandRules = true; isActive = true; source = "E2E $tag"; validFrom = LocalDate.of(2024, 1, 1)
            }
            em.persist(rate); em.flush(); rate.id!!
        }
        val invoiceId = newInvoiceWithServiceItem(tag, parties)
        val sharedDate = LocalDate.of(2026, 3, 12)

        val positionId = QuarkusTransaction.requiringNew().call {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "$tag Dienstreise Berlin",
                    periodFrom = sharedDate, periodTo = sharedDate.plusDays(1),
                    perDiemDays = listOf(
                        PerDiemEntryCommand(date = sharedDate, isArrivalDeparture = false, breakfast = false, lunch = false, dinner = false, description = "Team-Reise Berlin", participant = "Frau Müller", employerContribution = Money.ZERO, perDiemRateId = perDiemRateId),
                        PerDiemEntryCommand(date = sharedDate, isArrivalDeparture = false, breakfast = false, lunch = false, dinner = false, description = "Team-Reise Berlin", participant = "Herr Klein", employerContribution = Money.ZERO, perDiemRateId = perDiemRateId),
                    ),
                ),
            )
            em.find(Invoice::class.java, invoiceId)!!.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId
        }

        // Reload — a genuinely fresh read, not the same transaction that wrote it.
        withInvoice(invoiceId) { invoice ->
            val tripItem = invoice.itemList.single { it.category == InvoiceItemCategory.TRIP }
            val entries = tripItem.perDiemEntryList.sortedBy { it.participant }
            assertEquals(2, entries.size, "both entries must persist, not merged or blocked by the shared date")
            assertTrue(entries.all { it.travelDate == sharedDate })
            assertEquals(listOf("Frau Müller", "Herr Klein"), entries.map { it.participant })
            assertTrue(entries.all { it.description == "Team-Reise Berlin" }, "the block description is denormalized identically onto every entry")
        }

        val dto = mapToDto(invoiceId)
        val tripDto = dto.items.single { it.positionId == positionId }.trip
        assertNotNull(tripDto, "the read DTO must still carry the trip detail")
        assertEquals(2, tripDto!!.perDiemDays.size, "the export-side read must not collapse two same-date entries into one")

        val output = ByteArrayOutputStream()
        tripWorkbookExportPort.writeTripWorkbook(invoiceId, positionId, germanBundleTranslate(), output)
        XSSFWorkbook(ByteArrayInputStream(output.toByteArray())).use { wb ->
            val posten = wb.getSheet("Posten")
            assertEquals(2, posten.physicalNumberOfRows, "Posten contains only header and total because this trip has no general expenses")
            val perDiem = wb.getSheet("Verpflegung")
            assertEquals(5, perDiem.physicalNumberOfRows, "header + 2 per-diem rows + total + overview scope")
            val perDiemDates = (1..2).map { r -> perDiem.getRow(r).getCell(2).localDateTimeCellValue.toLocalDate() }
            assertEquals(listOf(sharedDate, sharedDate), perDiemDates, "the dedicated per-diem sheet must not collapse same-date entries")
            val descriptions = (1..2).map { r -> perDiem.getRow(r).getCell(1).stringCellValue }
            assertTrue(descriptions[0].endsWith("Frau Müller"), "each same-date row retains its participant")
            assertTrue(descriptions[1].endsWith("Herr Klein"), "each same-date row retains its participant")
            assertEquals("1 Tag · 2 Teilnehmer", perDiem.getRow(4).getCell(0).stringCellValue)
        }
    }
    @Test
    fun `unnamed per-diem days can be created and appended after saving including duplicate dates`() {
        forcePerDiemUiEnabled()
        val tag = "PDOPT"
        val invoiceId = newInvoiceWithServiceItem(tag, buildParties(tag))
        val rateId = QuarkusTransaction.requiringNew().call {
            val rate = PerDiemRate().edit {
                country = Country.DE; fullDayRate = Money.of("28.00"); arrivalDepartureRate = Money.of("14.00")
                inlandRules = true; isActive = true; source = "E2E $tag"; validFrom = LocalDate.of(2024, 1, 1)
            }
            em.persist(rate); em.flush(); rate.id!!
        }
        val date = LocalDate.of(2026, 3, 12)
        val day = PerDiemEntryCommand(
            date = date, isArrivalDeparture = false, breakfast = false, lunch = false, dinner = false,
            description = "", participant = "", employerContribution = Money.ZERO, perDiemRateId = rateId,
        )
        val command = TripCommand(title = "$tag Dienstreise", periodFrom = date, periodTo = date.plusDays(1), perDiemDays = listOf(day))
        val positionId = QuarkusTransaction.requiringNew().call {
            tripService.addTrip(invoiceId, command)
            em.find(Invoice::class.java, invoiceId)!!.itemList.single { it.category == InvoiceItemCategory.TRIP }.positionId
        }
        withInvoice(invoiceId) { invoice ->
            val trip = invoice.itemList.single { it.positionId == positionId }
            assertEquals(1, trip.perDiemEntryList.size)
            assertEquals(Money.of("28.00"), trip.netAmount)
        }

        QuarkusTransaction.requiringNew().run {
            tripService.updateTrip(invoiceId, positionId, command.copy(perDiemDays = listOf(
                day,
                day.copy(participant = "  ", description = "  ", breakfast = true),
                day.copy(date = date.plusDays(1), isArrivalDeparture = true),
            )))
        }
        withInvoice(invoiceId) { invoice ->
            val trip = invoice.itemList.single { it.positionId == positionId }
            assertEquals(3, trip.perDiemEntryList.size)
            assertEquals(2, trip.perDiemEntryList.count { it.travelDate == date })
            assertTrue(trip.perDiemEntryList.all { it.description.isBlank() && it.participant.isBlank() })
            assertEquals(Money.of("64.40"), trip.netAmount, "28 + (28 - 5.60 breakfast) + 14 arrival")
        }
        val tripDto = mapToDto(invoiceId).items.single { it.positionId == positionId }.trip!!
        assertEquals(3, tripDto.perDiemDays.size, "the read/export model retains every unnamed day")
    }

    // ─── Mileage and travel-time rows round-trip ───────────────────────

    @Test
    fun `II6 trip with mileage and travel-time rows round-trips with kind, unit, and quantity preserved`() {
        val tag = "II6"
        val parties = buildParties(tag)
        val invoiceId = newInvoiceWithServiceItem(tag, parties)

        QuarkusTransaction.requiringNew().run {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "$tag Kundenbesuch",
                    expenses = listOf(
                        TripExpenseCommand(kind = TripExpenseKind.MILEAGE, description = "120 km", quantity = BigDecimal("120"), unit = InvoiceItemUnit.KILOMETER, unitPriceNet = Money.of("0.30")),
                        TripExpenseCommand(kind = TripExpenseKind.TRAVEL_TIME, description = "", quantity = BigDecimal("2"), unit = InvoiceItemUnit.HOUR, unitPriceNet = Money.ZERO),
                    ),
                ),
            )
        }

        withInvoice(invoiceId) { invoice ->
            val tripItem = invoice.itemList.single { it.category == InvoiceItemCategory.TRIP }
            assertEquals(2, tripItem.tripExpenseList.size, "trip must have both mileage and travel-time rows")

            val mileageExpense = tripItem.tripExpenseList.single { it.kind == TripExpenseKind.MILEAGE }
            assertEquals("120 km", mileageExpense.description)
            assertEquals(0, BigDecimal("120").compareTo(mileageExpense.quantity))
            assertEquals(InvoiceItemUnit.KILOMETER, mileageExpense.unit)
            assertEquals(0, Money.of("36.00").amount.compareTo(mileageExpense.netAmount.amount), "120 km * 0.30 = 36.00")

            val travelTimeExpense = tripItem.tripExpenseList.single { it.kind == TripExpenseKind.TRAVEL_TIME }
            assertEquals("", travelTimeExpense.description)
            assertEquals(0, BigDecimal("2").compareTo(travelTimeExpense.quantity))
            assertEquals(InvoiceItemUnit.HOUR, travelTimeExpense.unit)
            assertEquals(0, Money.ZERO.amount.compareTo(travelTimeExpense.netAmount.amount), "travel-time with no rate = 0")
        }

        val tripDto = mapToDto(invoiceId).items.single { it.category == InvoiceItemCategory.TRIP }.trip!!
        assertEquals(2, tripDto.expenses.size, "DTO must preserve both expense rows")
        // TripExpenseExportDto carries kindLabelKey/unitCode, not a raw
        // TripExpenseKind/InvoiceItemUnit — every writer resolves those against its own bundle.
        assertEquals(TripExpenseKind.MILEAGE.labelKey, tripDto.expenses[0].kindLabelKey)
        assertEquals(0, BigDecimal("120").compareTo(tripDto.expenses[0].quantity))
        assertEquals(InvoiceItemUnit.KILOMETER.uneceCode, tripDto.expenses[0].unitCode)
        assertEquals(TripExpenseKind.TRAVEL_TIME.labelKey, tripDto.expenses[1].kindLabelKey)
        assertEquals(0, BigDecimal("2").compareTo(tripDto.expenses[1].quantity))
        assertEquals(InvoiceItemUnit.HOUR.uneceCode, tripDto.expenses[1].unitCode)
    }
}
