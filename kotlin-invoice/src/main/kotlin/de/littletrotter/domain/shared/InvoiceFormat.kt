package de.littletrotter.domain.shared

/**
 * The legal invoice *format*: which mandatory-content/text-block set a PDF carries
 * (e.g. German Rechtsform notices vs. a more neutral, EN-16931-oriented layout).
 * Lives in `domain.shared` because both the invoice and the masterdata sub-context legitimately
 * speak this language — PO-Entscheid 3a.
 * Deliberately distinct from two other, orthogonal decisions an invoice already carries:
 *
 * - [de.littletrotter.domain.invoice.Invoice.documentLanguage] (R3) — *translation*, follows the
 *   recipient's country, resolved once at creation and never re-derived. "Reverse Charge never
 *   appears in this chain — it's a tax case, not a language" (R3's own KDoc) applies equally
 *   here: [InvoiceFormat] is not a language either.
 * - `resolveExportProfileId()` (R8/ExportProfile) — the **XML** export profile (XRechnung vs.
 *   EN16931), driven only by which VAT ids exist. [InvoiceFormat] governs the **PDF**
 *   presentation; the two may be set independently.
 *
 * Only meaningful — and only changeable — when
 * [de.littletrotter.domain.invoice.Invoice.sellerVatRegistrationCountry] is non-null
 * **or** [de.littletrotter.domain.invoice.Invoice.reverseCharge] is `true` (R-CB, see
 * [de.littletrotter.domain.invoice.isInvoiceFormatEditable]); losing both conditions forces this
 * back to [GERMAN] (`InvoiceLifecycleService.updateHeader`), so [INTERNATIONAL] can never survive
 * a state where choosing it wouldn't make sense.
 */
enum class InvoiceFormat { GERMAN, INTERNATIONAL }
