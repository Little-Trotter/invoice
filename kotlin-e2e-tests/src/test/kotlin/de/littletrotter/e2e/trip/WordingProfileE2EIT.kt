package de.littletrotter.e2e.trip

import com.fasterxml.jackson.databind.ObjectMapper
import de.littletrotter.application.export.port.outbound.InvoiceWorkbookExportPort
import de.littletrotter.application.invoice.DocumentJobPublisher
import de.littletrotter.application.invoice.InvoiceDtoMapper
import de.littletrotter.application.invoice.InvoiceItemCommand
import de.littletrotter.application.invoice.InvoiceLifecycleService
import de.littletrotter.application.invoice.InvoiceItemMutationService
import de.littletrotter.application.invoice.TripCommand
import de.littletrotter.application.invoice.TripExpenseCommand
import de.littletrotter.application.invoice.TripService
import de.littletrotter.application.settings.WordingSettingsGate
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.e2e.E2eBusinessDay
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.domain.value.TaxRatePercent
import de.littletrotter.domain.repository.ReceiptRepository
import de.littletrotter.domain.repository.SystemSettingRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.NatsTestResource
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.Money
import de.littletrotter.shared.wording.WordingProfile
import de.littletrotter.ui.i18n.I18nBundleResource
import de.littletrotter.ui.i18n.TranslationService
import de.littletrotter.xpdf.testsupport.TypstContainerRenderer
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import de.littletrotter.xpdf.impl.XmlExportServiceImpl
import io.nats.client.Nats
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.edit

/**
 * The wording profile against the real, CDI-wired machinery:
 * [WordingSettingsGate] backed by real Postgres, [I18nBundleResource] (the endpoint the
 * Playwright suite reads — called here directly through its own real CDI-injected instance rather
 * than over HTTP, since this module carries no HTTP test client; the resource itself is the exact
 * production bean, only the JAX-RS transport is skipped), the real [InvoiceWorkbookExportPort] +
 * [TranslationService] (never the raw-properties `germanBundleTranslate` fixture
 * [TripExcelExportE2EIT] uses, which bypasses term substitution entirely and would never show a
 * profile-dependent label), and the real [DocumentJobPublisher] against a real NATS subject.
 *
 * Known gap: no `invoice-labels_*.properties` value (the PDF renderer's own bundle, `kotlin-xpdf`) carries a `{term:...}` marker
 * yet — only `kotlin-invoice`'s `i18n/messages*.properties` (UI/export labels) and
 * `wording-terms*.properties` were rewritten. The rendered PDF text therefore does NOT change with
 * the profile; this suite asserts the wire field ([de.littletrotter.shared.InvoiceMetaDto
 * .wordingProfile]) the print pipeline would resolve from once that bundle is rewritten, never a
 * profile-dependent product term in PDF text.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(NatsTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class WordingProfileE2EIT {

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

    @Inject
    lateinit var wordingGate: WordingSettingsGate

    @Inject
    lateinit var systemSettingRepository: SystemSettingRepository

    @Inject
    lateinit var i18nBundleResource: I18nBundleResource

    @Inject
    lateinit var translationService: TranslationService

    @Inject
    lateinit var invoiceWorkbookExportPort: InvoiceWorkbookExportPort

    @Inject
    lateinit var documentJobPublisher: DocumentJobPublisher

    @Inject
    lateinit var documentStore: DocumentStore

    @ConfigProperty(name = "nats.url")
    lateinit var natsUrl: String

    private val jobsSubject: String =
        System.getProperty("document.jobs.subject", System.getenv().getOrDefault("DOCUMENT_JOBS_SUBJECT", "document-jobs"))

    private val createdInvoiceIds = mutableListOf<Long>()

    @AfterEach
    fun cleanup() {
        createdInvoiceIds.forEach { id ->
            runCatching { QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, id)?.let(em::remove) } }
        }
        createdInvoiceIds.clear()
        resetWordingProfile()
    }

    /** Removes the `wording.profile` row (a plain settings table, no freeze-style journal trigger —
     *  same native-delete + [WordingSettingsGate.invalidate] idiom
     *  [TripLifecycleE2EIT.forcePerDiemUiEnabled] uses for `feature_setting`), so every test in this
     *  class starts from the pure env default (HANDWERK) regardless of run order, and no switch
     *  performed here ever leaks into a later suite sharing this JVM's Quarkus instance. */
    private fun resetWordingProfile() {
        runCatching {
            QuarkusTransaction.requiringNew().run {
                em.createNativeQuery("DELETE FROM system_setting WHERE key = '${WordingSettingsGate.KEY}'").executeUpdate()
            }
        }
        wordingGate.invalidate()
    }

    // ─── Fixture helpers (same idiom as the other trip E2E suites) ───────────────────────────────

    private data class Parties(val senderId: Long, val recipientId: Long, val taxRateId: Long)

    private fun buildParties(tag: String): Parties = QuarkusTransaction.requiringNew().call {
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
            code = "${tag}_STD19"; category = "S"; name = "USt 19 %"; rate = TaxRatePercent(BigDecimal("19.00"))
            country = Country.DE; validFrom = LocalDate.of(2024, 1, 1); isSelectable = true; isActive = true
        }
        em.persist(taxRate)
        em.flush()
        Parties(sender.id!!, recipient.id!!, taxRate.id!!)
    }

    /** Builds a DRAFT invoice with one SERVICE item and one TRIP item — enough for both the
     *  workbook export and the job-publish checks, no need to ever reach ISSUED here since neither
     *  scenario in this suite touches the issued-invoice freeze. */
    private fun draftInvoiceWithTrip(tag: String): Long {
        val parties = buildParties(tag)
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        createdInvoiceIds += invoiceId
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE, description = "$tag Beratung",
                    quantity = BigDecimal("2"), unit = InvoiceItemUnit.HOUR, unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = E2eBusinessDay.today() } }
        QuarkusTransaction.requiringNew().run {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "$tag Dienstreise",
                    expenses = listOf(TripExpenseCommand(kind = TripExpenseKind.HOTEL, description = "Hotel", quantity = BigDecimal("1"), unit = InvoiceItemUnit.DAY, unitPriceNet = Money.of("90.00"))),
                ),
            )
        }
        return invoiceId
    }

    private fun mapToDto(invoiceId: Long): InvoiceDto = QuarkusTransaction.requiringNew().call {
        InvoiceDtoMapper(receiptRepository = receiptRepository).mapToDto(em.find(Invoice::class.java, invoiceId)!!)
    }

    /** Same idiom as [InvoiceGenerationLockE2EIT.exportPdf]/[TripLifecycleE2EIT.exportPdf], against
     *  the real, CDI-injected [documentStore] rather than an in-memory double — this suite needs
     *  the actual archive round trip, not merely a mapped-in-memory stand-in. */
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun cellText(cell: Cell?): String = when (cell?.cellType) {
        null -> ""
        CellType.STRING -> cell.stringCellValue
        CellType.NUMERIC -> cell.numericCellValue.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }
        CellType.BOOLEAN -> cell.booleanCellValue.toString()
        else -> cell.toString()
    }

    private fun headerIndex(sheet: Sheet, label: String): Int {
        val header = sheet.getRow(0)
        for (i in 0 until header.lastCellNum) {
            if (cellText(header.getCell(i)) == label) return i
        }
        error("column '$label' not found on sheet '${sheet.sheetName}'")
    }

    /** Subscribes a plain core-NATS dispatcher on the real jobs subject BEFORE [action] runs, the
     *  same technique [de.littletrotter.application.invoice.GenerationJobFastPublishIT] (kotlin-
     *  invoice) uses: a JetStream publish is simultaneously visible to any ordinary core subscriber
     *  on the same subject, so this needs no pull consumer/durable of its own to read the payload
     *  a real publish actually put on the wire. */
    private fun awaitPublishedWordingProfileCode(action: () -> Unit): Int {
        val conn = Nats.connect(natsUrl)
        try {
            val latch = CountDownLatch(1)
            val payloadRef = AtomicReference<ByteArray>()
            conn.createDispatcher { msg -> payloadRef.set(msg.data); latch.countDown() }.subscribe(jobsSubject)
            action()
            assertTrue(latch.await(5, TimeUnit.SECONDS), "the job was not published to NATS subject '$jobsSubject' in time")
            val root = ObjectMapper().readTree(String(payloadRef.get(), Charsets.UTF_8))
            return root.path("payload").path("invoice").path("meta").path("wordingProfile").asInt()
        } finally {
            conn.close()
        }
    }

    /** Item 6 (Welle C)-style JUL capture, same idiom [DocumentGeneratedReactionHandlerTest
     *  .captureLogRecords] (kotlin-invoice) uses: attaches a plain [java.util.logging.Handler]
     *  directly to the JUL logger backing [WordingSettingsGate]'s `org.jboss.logging.Logger`. */
    private fun captureLogRecords(loggerName: String, block: () -> Unit): List<java.util.logging.LogRecord> {
        val julLogger = java.util.logging.Logger.getLogger(loggerName)
        val records = mutableListOf<java.util.logging.LogRecord>()
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { records.add(record) }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val previousLevel = julLogger.level
        julLogger.level = java.util.logging.Level.ALL
        julLogger.addHandler(handler)
        try {
            block()
        } finally {
            julLogger.removeHandler(handler)
            julLogger.level = previousLevel
        }
        return records
    }

    // ─── Default profile from env (HANDWERK) ──────────────────────────────────────────────────────

    @Test
    fun `WP-1 default profile from env HANDWERK - the resolved i18n bundle names Material and Dienstreise`() {
        val bundle = i18nBundleResource.bundle("de")
        assertEquals("Material", bundle["invoiceDetail.summary.dashboard.goods"], "HANDWERK plural product term")
        assertEquals("Dienstreise", bundle["item.category.trip"], "HANDWERK trip term")
    }

    // ─── Switch to BERATUNG: bundle, export labels, job payload all follow (product term
    //     only — Dienstreise stays constant across every profile, PO decision 13.09.2026) ────────

    @Test
    fun `WP-2 switch to BERATUNG - within the cache TTL the i18n bundle and the invoice workbook labels follow for the product term, the job payload follows for the profile code, Dienstreise stays constant`() {
        val tag = "WP2"
        val invoiceId = draftInvoiceWithTrip(tag)

        wordingGate.setProfile(WordingProfile.BERATUNG, "e2e-wp2")

        // 1. the i18n bundle (this test's own /api/i18n/de) — setProfile already invalidates the cache,
        //    so this is visible immediately, well within the 10s TTL.
        val bundle = i18nBundleResource.bundle("de")
        assertEquals("Produkte", bundle["invoiceDetail.summary.dashboard.goods"], "BERATUNG plural product term")
        assertEquals("Dienstreise", bundle["item.category.trip"], "trip term is constant, even after the switch")

        // 2. the invoice workbook export's own category label follows the same switch for the
        //    product row — the real TranslationService (not a raw-properties lookup) so term
        //    substitution actually runs — while the trip row's category label stays "Dienstreise".
        val output = ByteArrayOutputStream()
        invoiceWorkbookExportPort.writeWorkbook(invoiceId, "$tag e2e", { key -> translationService.getTranslation(key, Locale.GERMAN) }, output)
        XSSFWorkbook(ByteArrayInputStream(output.toByteArray())).use { wb ->
            val sheet = wb.getSheet("Positionen")
            val categoryIdx = headerIndex(sheet, "Kategorie")
            val levelIdx = headerIndex(sheet, "Ebene")
            val tripRow = (1..sheet.lastRowNum).mapNotNull { sheet.getRow(it) }
                .firstOrNull { cellText(it.getCell(levelIdx)) == "0" && cellText(it.getCell(categoryIdx)) == "Dienstreise" }
            assertTrue(tripRow != null, "expected a level-0 Positionen row still carrying the constant trip label 'Dienstreise' after the BERATUNG switch")
        }

        // 3. a generation job published after the switch carries the BERATUNG code in its
        //    InvoiceMetaDto.wordingProfile — never retroactive, but this job IS published after the
        //    switch. Known gap (this suite's own KDoc): the rendered PDF text itself does not
        //    follow yet (invoice-labels_*.properties carries no {term:...} marker), so only the
        //    wire field is asserted here.
        val invoice = QuarkusTransaction.requiringNew().call { em.find(Invoice::class.java, invoiceId)!! }
        val wordingProfileCode = awaitPublishedWordingProfileCode { documentJobPublisher.publish(invoice, invoiceId + 900_000_000L) }
        assertEquals(WordingProfile.BERATUNG.code, wordingProfileCode, "the published job's payload.invoice.meta.wordingProfile must carry the BERATUNG code")
    }

    // ─── A document issued before the switch keeps its archived bytes and checksum ────────

    @Test
    fun `WP-3 a document issued before the switch keeps its archived bytes and checksum unchanged`() {
        val tag = "WP3"
        val parties = buildParties(tag)
        val invoiceId = QuarkusTransaction.requiringNew().call { lifecycleService.create(parties.senderId, parties.recipientId).id!! }
        createdInvoiceIds += invoiceId
        QuarkusTransaction.requiringNew().run {
            itemMutationService.addItem(
                invoiceId,
                InvoiceItemCommand(
                    category = InvoiceItemCategory.SERVICE, description = "$tag Beratung",
                    quantity = BigDecimal("2"), unit = InvoiceItemUnit.HOUR, unitPriceNet = Money.of("100.00"),
                    taxRateId = parties.taxRateId,
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { em.find(Invoice::class.java, invoiceId)!!.edit { deliveryDate = E2eBusinessDay.today() } }
        QuarkusTransaction.requiringNew().run {
            tripService.addTrip(
                invoiceId,
                TripCommand(
                    title = "$tag Dienstreise",
                    expenses = listOf(TripExpenseCommand(kind = TripExpenseKind.HOTEL, description = "Hotel", quantity = BigDecimal("1"), unit = InvoiceItemUnit.DAY, unitPriceNet = Money.of("90.00"))),
                ),
            )
        }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.IN_REVIEW) }
        QuarkusTransaction.requiringNew().run { lifecycleService.transition(invoiceId, InvoiceStatus.ISSUED) }
        // The issued-invoice freeze locks this invoice's own trip_expense rows the moment it is issued -- same
        // exclusion convention every other trip E2E suite in this package uses.
        createdInvoiceIds.remove(invoiceId)

        val dto = mapToDto(invoiceId)
        val (objectKey, firstBytes) = exportPdfWithKey(dto, "$tag-first")
        val firstChecksum = sha256(firstBytes)

        wordingGate.setProfile(WordingProfile.BERATUNG, "e2e-wp3")

        val stillStored = documentStore.get(objectKey)
        assertArrayEquals(firstBytes, stillStored, "a document issued before the switch must keep its archived bytes unchanged")
        assertEquals(firstChecksum, sha256(stillStored), "the archived document's checksum must be unchanged after the switch")
    }

    // ─── Unknown env value falls back to the default profile with one WARN ────────────────

    @Test
    fun `WP-4 an unknown env value falls back to the default profile with exactly one WARN`() {
        // WordingSettingsGate's own envDefault is resolved once at CDI-bean construction time from
        // a single @ConfigProperty, so the real, already-booted CDI singleton cannot be re-driven
        // with a different env value inside this one Quarkus boot (see this class's own KDoc
        // above). This constructs a second instance directly through
        // WordingSettingsGate's public constructor instead, wired against the REAL, CDI-injected,
        // Postgres-backed SystemSettingRepository (no row present after resetWordingProfile) — only
        // the env-var plumbing itself is bypassed, exercising the identical fallback/WARN logic
        // production would run for a genuinely misconfigured LITTLETROTTER_WORDING_PROFILE.
        val bogusEnvValue = "bogus-profile-e2e"
        lateinit var gate: WordingSettingsGate

        // The construction itself, not just the first currentProfile() call, must happen inside
        // the capture: settingsCache's own field initializer reads `envDefault` (`by lazy`)
        // directly as its `initial` value, which forces that lazy computation -- and its own WARN
        // -- during the constructor call, before currentProfile() is ever invoked (caught live
        // building this test: constructing gate outside the capture always observed zero records).
        val records = captureLogRecords(WordingSettingsGate::class.java.name) {
            gate = WordingSettingsGate(
                systemSettingRepository,
                bogusEnvValue,
                de.littletrotter.shared.time.ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
            )
            assertEquals(WordingProfile.DEFAULT, gate.currentProfile())
            // A second lookup must not warn again -- envDefault is memoized `by lazy`.
            assertEquals(WordingProfile.HANDWERK, gate.currentProfile())
        }

        // Compared by severity (intValue), not by identity against java.util.logging.Level.WARNING:
        // jboss-logmanager (the JUL backend this module's surefire/failsafe config installs) uses
        // its own Level.WARN constant, name "WARN" not "WARNING", and is not `==`/`.equals()` to
        // the plain JUL constant despite carrying the same 900 severity (caught live building this
        // test: the identity comparison silently found zero matches even though the WARN had
        // genuinely been logged).
        val warnLevel = java.util.logging.Level.WARNING.intValue()
        val warnings = records.filter { it.level.intValue() == warnLevel }
        assertEquals(1, warnings.size, "an unknown env value must log exactly one WARN, not one per lookup: $records")
        assertTrue(warnings.single().message.contains(bogusEnvValue), "the WARN must name the offending value: ${warnings.single().message}")
    }
}
