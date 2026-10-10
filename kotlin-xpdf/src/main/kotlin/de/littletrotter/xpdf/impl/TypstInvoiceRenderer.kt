package de.littletrotter.xpdf.impl

import com.fasterxml.jackson.module.kotlin.jsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import de.littletrotter.shared.BasePdfRendererPort
import de.littletrotter.shared.InvoiceDto
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Typst adapter of [BasePdfRendererPort]: builds the engine-neutral [InvoiceReportModel], stages
 * the GiroCode SVG when [paymentQr] and the invoice allow one, writes the shipped template's
 * `data.json` ([toTypstData]) and compiles `standard-invoice.typ` through the sandboxed
 * [TypstCompiler]. Only the shipped template ever renders here — layouts are product code, not
 * user uploads.
 *
 * The creation timestamp comes from the injected [TimeSource], so a fixed clock yields
 * byte-identical PDFs for identical invoices. A compile failure is thrown as
 * [TypstRenderException] with the compiler's diagnostics — the pipeline reports a failed job
 * exactly as it does for a missing receipt, never a half-rendered document.
 */
@ApplicationScoped
class TypstInvoiceRenderer @Inject constructor(
    private val compiler: TypstCompiler,
    private val time: TimeSource,
    private val paymentQr: PaymentQrSetting,
) : BasePdfRendererPort {

    private val modelBuilder = InvoiceReportModelBuilder()
    private val mapper = jsonMapper { addModule(kotlinModule()) }

    /** Read once: the template is a classpath resource that cannot change while the worker runs. */
    private val template: String by lazy { bundledTemplate() }

    override fun render(invoice: InvoiceDto): ByteArray =
        when (val result = compiler.compile(compileRequest(invoice))) {
            is TypstCompileResult.Success -> result.pdfBytes
            is TypstCompileResult.Failure -> throw TypstRenderException(result.reason, result.diagnostics)
        }

    /** Template, data and timestamp of one run — what the parity tests hand to the pinned CLI.
     *  The model is built exactly once here, so the GiroCode payload (needs the resolved payment
     *  block) and `data.json` (needs the same model) never disagree about whether a code exists. */
    fun compileRequest(invoice: InvoiceDto): TypstCompileRequest {
        val model = modelBuilder.build(invoice)
        val language = resolveDocumentRenderLanguage(invoice.meta.documentLanguage)
        val svg = paymentQrSvg(invoice, model)
        val qrFile = svg?.let { QR_FILE }
        return TypstCompileRequest(
            templateSource = template,
            dataJson = mapper.writeValueAsString(model.toTypstData(language, qrFile = qrFile)),
            creationTimestamp = time.instant(),
            files = svg?.let { mapOf(QR_FILE to it) } ?: emptyMap(),
        )
    }

    /** The `data.json` this invoice renders from; the layout tests read it, nothing else does. */
    fun dataJson(invoice: InvoiceDto): String = compileRequest(invoice).dataJson

    /**
     * The GiroCode SVG for this invoice, or `null` when no code is printed: the setting is off,
     * the document has no payment block (storno/credit note), or the EPC preconditions in
     * [EpcQrPayload.build] fail (no IBAN, no sender name, non-EUR, amount out of range). Silent
     * omission is deliberate — the code is a deployment-wide convenience, never a placeholder the
     * reader would wonder about.
     */
    private fun paymentQrSvg(invoice: InvoiceDto, model: InvoiceReportModel): String? {
        if (paymentQr == PaymentQrSetting.OFF || !model.payment.hasPaymentBlock) return null
        val payload = EpcQrPayload.build(
            EpcQrInput(
                name = invoice.senderProfile?.name,
                iban = invoice.senderProfile?.iban,
                bic = invoice.senderProfile?.bic,
                amount = invoice.totals.grossAmount,
                currency = invoice.totals.currency,
                remittance = model.payment.invoiceNumber,
            ),
        ) ?: return null
        return QrSvg.render(QrSvg.encode(payload))
    }

    /** `internal` with a defaulted lookup so a test can exercise the "template missing from the
     *  jar" branch without touching the real classpath. */
    internal fun bundledTemplate(
        resourceLookup: (String) -> java.io.InputStream? = { path -> javaClass.getResourceAsStream(path) },
    ): String {
        val resource = resourceLookup(TEMPLATE_RESOURCE)
            ?: throw TypstRenderException("Typst-Vorlage $TEMPLATE_RESOURCE nicht gefunden", "")
        return resource.bufferedReader().use { it.readText() }
    }

    companion object {
        const val TEMPLATE_RESOURCE = "/typst/standard-invoice.typ"

        /** Filename the template's `pay.qrFile` refers to and [TypstCompileRequest.files] stages
         *  the GiroCode SVG under. */
        const val QR_FILE = "qr.svg"
    }
}

/** The Typst engine could not produce the base document; [diagnostics] is the compiler's stderr. */
class TypstRenderException(reason: String, val diagnostics: String) :
    RuntimeException(if (diagnostics.isBlank()) reason else "$reason\n$diagnostics")
