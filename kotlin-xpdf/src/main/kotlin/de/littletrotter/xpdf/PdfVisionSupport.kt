package de.littletrotter.xpdf

import de.littletrotter.shared.InvoiceDto

/**
 * Prompt handling for optional visual PDF spot-check (Approach B).
 * Prompt template is externalized so it can be edited without recompiling.
 */
internal object PdfVisionSupport {

    fun buildInspectionPrompt(invoice: InvoiceDto, layoutReport: String = ""): String {
        return promptTemplate
            .replace("{{layoutReport}}", layoutReport.ifBlank { "(keine deterministische Messung übergeben)" })
            .replace("{{invoiceNumber}}", invoice.invoiceNumber.value)
    }

    private val promptTemplate: String by lazy { loadTemplate(PROMPT_RESOURCE) }

    /** Reads the prompt template [resource] from the classpath; a missing resource is a packaging defect. */
    internal fun loadTemplate(resource: String): String {
        val stream = javaClass.getResourceAsStream(resource)
            ?: error("Prompt template $resource not found on classpath")
        return stream.bufferedReader().use { it.readText() }
    }

    private const val PROMPT_RESOURCE = "/prompts/pdf-vision-inspection-prompt.md"
}
