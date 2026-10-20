package de.littletrotter.xpdf.impl

import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.shared.DocumentStore
import de.littletrotter.shared.InvoiceDatesDto
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.InvoiceReferencesDto
import de.littletrotter.shared.InvoiceTotalsDto
import de.littletrotter.shared.Money
import de.littletrotter.shared.PartyDto
import de.littletrotter.shared.ReceiptDto
import de.littletrotter.xpdf.testsupport.FixtureBaseRenderer
import de.littletrotter.xpdf.testsupport.PdfExportServiceImplTestFactory
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.system.exitProcess
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The child-process side of [KositXpdfConcurrentRenderMemoryIT]: a plain `main`,
 * not a JUnit test, because it is meant to run in its OWN, `-Xmx`-constrained JVM (that IT forks
 * one via [ProcessBuilder]), not inside surefire's own forked JVM whose heap the module's pom.xml
 * (or the IDE's run configuration) controls independently of `docker-compose.yml`'s own
 * `LT_XPDF_MEMORY_LIMIT`/`JOB_WORKER_THREADS` formula.
 *
 * Renders `args[0]` distinct invoices concurrently through the real export pipeline
 * ([PdfExportServiceImpl] — the same class [de.littletrotter.xpdf.infrastructure.messaging
 * .JobConsumer] and [RenderWarmup] use for a real job), sharing ONE [EmbeddedKositValidator]
 * instance across every render thread — proving [DefaultCheck]'s own documented thread-safety
 * contract under genuine concurrent load, not merely citing it (see [EmbeddedKositValidator]'s own
 * KDoc for that contract's exact wording). Exits `0` only if every render completed without
 * throwing; a JVM-level OOM kills this process outright via `-XX:+ExitOnOutOfMemoryError` (passed
 * by the forking IT, matching `docker-compose.yml`'s own kotlin-xpdf `JAVA_TOOL_OPTIONS`) before
 * this `main` could ever return normally — either way, the forking IT observes a non-zero exit.
 */
fun main(args: Array<String>) {
    val threadCount = args.getOrNull(0)?.toIntOrNull() ?: error("usage: <threadCount>")

    val store = object : DocumentStore {
        private val written = linkedMapOf<String, ByteArray>()
        override fun put(objectKey: String, data: ByteArray, contentType: String) { written[objectKey] = data }
        override fun get(objectKey: String): ByteArray = written.getValue(objectKey)
        override fun exists(objectKey: String): Boolean = written.containsKey(objectKey)
        override fun enableImmutability(objectKey: String) {}
        override fun delete(objectKey: String) { written.remove(objectKey) }
    }
    val reportRenderer: BasePdfRendererPort = FixtureBaseRenderer()
    val basePdfStage = BasePdfStage(reportRenderer, PdfA3MinimalCheck(), ReceiptAnnexBuilder(store), ExportStageTimers(SimpleMeterRegistry()))
    // ONE shared EmbeddedKositValidator, ONE shared PdfExportServiceImpl — exactly the
    // @ApplicationScoped singleton shape production CDI wiring gives every kotlin-xpdf worker
    // thread; this harness must reproduce that sharing, not give each thread its own instance,
    // or it would not actually exercise DefaultCheck's cross-thread reuse contract at all.
    val service = PdfExportServiceImplTestFactory.create(
        XmlExportServiceImpl(store),
        reportRenderer,
        store,
        EmbeddedKositValidator().apply { init() },
        basePdfStage,
    )

    // Every invoice carries the maximum receipt count the per-thread render budget is sized for,
    // so the heap check covers a combined document, not just the bare invoice.
    val receiptPdf = singlePagePdf()
    val receipts = (1..RECEIPTS_PER_INVOICE).map { n ->
        store.put("receipts/$n.pdf", receiptPdf, "application/pdf")
        ReceiptDto("beleg-$n.pdf", "receipts/$n.pdf")
    }
    val invoices = (1..threadCount).map { harnessInvoice(it).copy(receipts = receipts) }
    val pool = Executors.newFixedThreadPool(threadCount)
    try {
        val futures = invoices.mapIndexed { idx, invoice ->
            pool.submit { service.performExport(invoice, "memory-harness-$idx") }
        }
        futures.forEach { it.get(90, TimeUnit.SECONDS) }
    } catch (t: Throwable) {
        System.err.println("Concurrent render memory harness failed: ${t.message}")
        t.printStackTrace()
        exitProcess(1)
    } finally {
        pool.shutdownNow()
    }
    exitProcess(0)
}

/** One distinct, minimal-but-real invoice per concurrent render — same two-tax-rate/reverse-charge
 *  independent shape [RenderWarmup]'s own `dummyInvoice` builds, parameterized only by [index] so
 *  every concurrent render is a genuinely separate object graph, not N threads sharing one. */
private fun harnessInvoice(index: Int): InvoiceDto {
    val sender = PartyDto(
        name = "Memory-Harness GmbH",
        street = "Musterweg $index",
        postalCode = "54550",
        city = "Daun",
        country = "DE",
        email = "harness@example.invalid",
        vatId = "DE123456789",
        taxNumber = "123/456/78901",
        iban = "DE02120300000000202051",
        bankName = "Beispielbank",
        managingDirector = "M. Muster",
        registerCourt = "Koblenz",
        commercialRegisterNumber = "HRB 00000",
        courtOfJurisdiction = "Daun",
    )
    val recipient = PartyDto(
        name = "Harness Kunde $index AG",
        street = "Kundenstraße $index",
        postalCode = "10115",
        city = "Berlin",
        country = "DE",
        customerNumber = "K-$index",
    )
    val item = InvoiceItemDto(
        positionId = "1",
        description = "Harness-Position $index",
        quantity = BigDecimal("1"),
        unitLabel = "Stück",
        unitPriceNet = Money(BigDecimal("100.00")),
        taxRatePercent = BigDecimal("19.0"),
        netAmount = Money(BigDecimal("100.00")),
        vatAmount = Money(BigDecimal("19.00")),
    )
    return InvoiceDto(
        invoiceNumber = InvoiceNumberDto("HARNESS-$index"),
        senderProfile = sender,
        recipient = recipient,
        items = listOf(item),
        dates = InvoiceDatesDto(
            invoiceDate = "2026-01-01",
            servicePeriodFrom = "2026-01-01",
            servicePeriodTo = "2026-01-31",
            dueDate = "2026-02-01",
            paymentTermsDays = 30,
        ),
        references = InvoiceReferencesDto(buyerReference = "Leitweg-$index"),
        totals = InvoiceTotalsDto(
            netAmount = Money(BigDecimal("100.00")),
            vatAmount = Money(BigDecimal("19.00")),
            grossAmount = Money(BigDecimal("119.00")),
        ),
    )
}

private const val RECEIPTS_PER_INVOICE = 19

private fun singlePagePdf(): ByteArray =
    PDDocument().use { doc ->
        doc.addPage(PDPage())
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }
