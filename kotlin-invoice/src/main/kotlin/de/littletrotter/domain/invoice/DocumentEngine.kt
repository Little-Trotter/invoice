package de.littletrotter.domain.invoice

/**
 * Which generation engine renders this
 * invoice's document: the existing Mustang pipeline (`kotlin-xpdf`, ZUGFeRD/XRechnung) or the new
 * Factur-X engine (an isolated Python/API container, reachable only over NATS).
 *
 * Persisted as a plain `VARCHAR` (see [de.littletrotter.domain.invoice.converters
 * .DocumentEngineConverter]) — never an ordinal, matching the task doc §5.2's explicit
 * `engine VARCHAR(20) NOT NULL DEFAULT 'MUSTANG'` column shape, and never as a UI label: German
 * labels for either value come from i18n, not from the enum name.
 *
 * [MUSTANG] stays the default everywhere. Only when [Invoice.documentEngine] is [FACTURX] does
 * the Ardennes-X SIRET gate ([de.littletrotter.domain.compliance.ArdennesXRules]) have anything to
 * say — a Mustang invoice to the very same French customer without a SIRET finalizes exactly as
 * before T8 (Testfall T8-05).
 *
 * What this enum intentionally does *not* yet carry: the NATS routing, the `GenerationJob`
 * persistence of the actually-dispatched engine, and the print-dialog selector
 * (`InvoiceExportPanel`) that would let an anwender switch it away from [MUSTANG] in the first
 * place — the task doc §5/6 and T8's Vorgehen Punkt 5 describe that wiring, and it is
 * deliberately left for the follow-up slice that also builds the Factur-X container/worker (see
 * the task doc, Fortschrittsabschnitt).
 */
enum class DocumentEngine {
    MUSTANG,
    FACTURX,
}
