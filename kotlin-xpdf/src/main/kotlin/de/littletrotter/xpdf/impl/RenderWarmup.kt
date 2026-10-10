package de.littletrotter.xpdf.impl

import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.shared.InvoiceDatesDto
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.InvoiceItemDto
import de.littletrotter.shared.InvoiceNumberDto
import de.littletrotter.shared.InvoiceReferencesDto
import de.littletrotter.shared.InvoiceTotalsDto
import de.littletrotter.shared.KositValidatorPort
import de.littletrotter.shared.Money
import de.littletrotter.shared.PartyDto
import de.littletrotter.shared.time.TimeSource
import io.quarkus.runtime.Startup
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.mustangproject.ZUGFeRD.ZUGFeRDExporterFromA3
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Duration

private const val WARMUP_AMOUNT = "100.00"

/**
 * Runs one synthetic invoice through the full export pipeline at startup so the first real
 * job does not pay the one-time costs.
 *
 * Measured on the running stack: the first job after a container start took 1.81 s, every
 * job after it 0.16 s. That factor is not steady-state rendering — it is JIT compilation, the
 * first Typst CLI start, Saxon warming up inside KoSIT, and Mustang's PDFBox initialization
 * including its font cache. Someone pays those
 * ~1.6 s once after every deploy; this bean makes it be nobody.
 *
 * Deliberately asynchronous on a daemon thread: startup stays fast, and a job arriving in
 * the first seconds simply contends on the same locks and waits rather than being blocked
 * by a startup ordering constraint. Nothing here touches NATS or the database, and the
 * rendered bytes are discarded — best-effort like everything else in this class.
 *
 * Since [EmbeddedKositValidator] is no longer `@Startup`/eager itself, this
 * bean's own async `warmUp()` call to [kositValidator]`.validate(...)` is now what actually
 * triggers the expensive KoSIT scenario compile close to container start, WITHOUT it blocking
 * [de.littletrotter.xpdf.infrastructure.messaging.JobConsumer]'s own `@Startup` NATS pull-consumer
 * subscription — the two `@Startup` beans' CDI construction never invokes a method on either's own
 * injected [KositValidatorPort] synchronously, so JobConsumer becomes NATS-reachable independent of
 * whether/when this thread's compile finishes (or crashes). That is exactly the fix for the
 * 2026-09-18 stress finding's crash loop: the compile peak no longer has to succeed before a
 * restarting worker can become reachable again.
 */
@Startup
@ApplicationScoped
class RenderWarmup @Inject constructor(
    private val xmlExportService: XmlExportServiceImpl,
    private val reportRenderer: BasePdfRendererPort,
    private val kositValidator: KositValidatorPort,
    // The one injected source of "now" — see de.littletrotter.xpdf.TimeSourceProducer. Only
    // used here to measure how long the warm-up took (elapsed duration), never as a calendar
    // date or a stored timestamp.
    private val time: TimeSource,
    // `@param:` pins this to the constructor parameter only; Kotlin 2.4's default
    // annotation-use-site-target policy would otherwise also copy `@ConfigProperty` onto the
    // backing final field, which Qodana's CDI inspector misreads as an illegal final-field
    // injection even though CDI never reads the field (no `@Inject` there). The `= true` default
    // is a deliberate test seam (direct-construction call sites that don't care about the flag)
    // and is unrelated to injection — CDI always resolves the real config value at runtime
    // regardless of this default.
    @param:ConfigProperty(name = "xpdf.warmup.enabled", defaultValue = "true")
    private val enabled: Boolean = true,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun scheduleWarmUp() {
        if (!enabled) {
            log.info("Render warm-up disabled (xpdf.warmup.enabled=false)")
            return
        }
        Thread(::warmUp, "render-warmup").apply { isDaemon = true }.start()
    }

    /**
     * Mirrors the step order of `PdfExportServiceImpl.performExport` minus every side effect,
     * so the paths warmed here are the paths a real job takes. Runs once per representative
     * shape (see [dummyInvoices]) — PDFBox/font-cache/ICC init only happens on the first pass;
     * the second shape additionally exercises the tax-breakdown layout branch of the template.
     *
     * Best-effort throughout: a failing warm-up costs the first job its old latency, nothing
     * more, and must never keep the service from coming up — hence [Throwable] rather than
     * [Exception], and one shape failing does not stop the next.
     */
    private fun warmUp() {
        val started = time.instant()
        var ok = 0
        for (invoice in dummyInvoices()) {
            try {
                val xml = xmlExportService.generate(invoice)
                kositValidator.validate(String(xml, Charsets.UTF_8))
                val pdf = reportRenderer.render(invoice)

                // The expensive part on the first pass: PDFBox startup, font cache, ICC
                // profile, PDF/A-3 conversion. Cheap on every pass after.
                ZUGFeRDExporterFromA3().use { exporter ->
                    exporter.load(pdf)
                    exporter.setProfile(xmlExportService.resolveProfile(invoice))
                    exporter.setXML(xml)
                    exporter.export(ByteArrayOutputStream())
                }
                ok++
            } catch (t: Throwable) {
                log.warn("Render warm-up failed for invoice '{}'", invoice.invoiceNumber.value, t)
            }
        }
        log.info(
            "Render warm-up completed ({}/{} shapes) in {} ms",
            ok, dummyInvoices().size, Duration.between(started, time.instant()).toMillis(),
        )
    }

    /**
     * Synthetic invoices covering the shapes that actually recur in production (checked
     * against the `invoice`/`invoice_item` tables): single vs. two tax rates, ~60/40 split,
     * which is the field that changes the rendered layout the most (a tax breakdown adds rows
     * to the totals block). The sender profile in production is a single fixed
     * row (headline + footer note + IBAN + bank all present), so that part of the shape needs
     * no variation here. `order_reference` varies too but in <5% of invoices — not worth a
     * third pass. `reverseCharge` only swaps label text, not row counts, so it is not a
     * separate shape either.
     *
     * An invoice whose shape isn't covered here still renders correctly, it just meets a
     * cold code path once.
     */
    private fun dummyInvoices(): List<InvoiceDto> =
        listOf(
            dummyInvoice(taxRates = listOf(BigDecimal("19.0"))),
            dummyInvoice(taxRates = listOf(BigDecimal("19.0"), BigDecimal("7.0"))),
        )

    private fun dummyInvoice(taxRates: List<BigDecimal>): InvoiceDto {
        val items = dummyItems(taxRates)
        val net = items.sumOf { it.netAmount.amount }
        val vat = items.sumOf { it.vatAmount.amount }
        return InvoiceDto(
            // Distinct number per shape only so warm-up runs are told apart in logs/traces —
            // nothing is persisted or looked up by it.
            invoiceNumber = InvoiceNumberDto("WARMUP-${taxRates.size}RATE"),
            senderProfile = dummySender(),
            recipient = dummyRecipient(),
            items = items,
            dates = dummyDates(),
            references = dummyReferences(),
            totals = InvoiceTotalsDto(
                netAmount = Money(net),
                vatAmount = Money(vat),
                grossAmount = Money(net.add(vat)),
            ),
        )
    }

    private fun dummySender(): PartyDto = PartyDto(
        name = "Warm-up GmbH",
        street = "Musterweg 1",
        postalCode = "54550",
        city = "Daun",
        country = "DE",
        email = "warmup@example.invalid",
        phone = "+49 6592 000000",
        vatId = "DE123456789",
        taxNumber = "123/456/78901",
        iban = "DE02120300000000202051",
        bic = "BYLADEM1001",
        bankName = "Beispielbank",
        managingDirector = "M. Muster",
        registerCourt = "Koblenz",
        commercialRegisterNumber = "HRB 00000",
        courtOfJurisdiction = "Daun",
        website = "https://www.warmup.example.invalid/",
        registeredSeat = "Daun",
    )

    private fun dummyRecipient(): PartyDto = PartyDto(
        name = "Warm-up Kunde AG",
        street = "Kundenstraße 7",
        postalCode = "10115",
        city = "Berlin",
        country = "DE",
        customerNumber = "K-0000",
    )

    /** One item per tax rate — matches how the production tax-rate-count query grouped
     *  invoices (distinct rates per invoice), and gives buildTaxRows() enough rows to
     *  reproduce the multi-rate breakdown block. */
    private fun dummyItems(taxRates: List<BigDecimal>): List<InvoiceItemDto> =
        taxRates.mapIndexed { idx, rate ->
            InvoiceItemDto(
                positionId = (idx + 1).toString(),
                description = "Warm-up-Position ${idx + 1}",
                quantity = BigDecimal("1"),
                unitLabel = "Stück",
                unitPriceNet = Money(BigDecimal(WARMUP_AMOUNT)),
                taxRatePercent = rate,
                netAmount = Money(BigDecimal(WARMUP_AMOUNT)),
                vatAmount = Money(BigDecimal(WARMUP_AMOUNT)).amount.multiply(rate).divide(BigDecimal("100"))
                    .let { Money(it) },
            )
        }

    private fun dummyDates(): InvoiceDatesDto = InvoiceDatesDto(
        invoiceDate = "2026-01-01",
        servicePeriodFrom = "2026-01-01",
        servicePeriodTo = "2026-01-31",
        dueDate = "2026-02-01",
        paymentTermsDays = 30,
    )

    private fun dummyReferences(): InvoiceReferencesDto = InvoiceReferencesDto(
        buyerReference = "Leitweg-000000000",
        offerReference = "ANG-0000",
        offerDate = "2025-12-01",
        // order_reference is present on <5% of production invoices — the dominant shape
        // (~96%) omits it, so the warm-up invoice does too (see dummyInvoices() doc).
    )
}
