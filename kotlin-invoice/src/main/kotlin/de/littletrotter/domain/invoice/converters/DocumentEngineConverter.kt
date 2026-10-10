package de.littletrotter.domain.invoice.converters

import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.shared.converters.OrdinalEnumConverter
import jakarta.persistence.Converter

/**
 * Ordinal storage for [Invoice.documentEngine][de.littletrotter.domain.invoice
 * .Invoice.documentEngine], consistent with every other enum on [de.littletrotter.domain.invoice
 * .Invoice] ([InvoiceStatusConverter], [de.littletrotter.domain.shared.converters.InvoiceFormatConverter], [InvoiceTypeCodeConverter]).
 *
 * This is deliberately *not* the `VARCHAR('MUSTANG'/'FACTURX')` shape the task doc §5.2
 * proposes for its own `GenerationJob.engine` column — that column belongs to a different entity
 * and, once built, is the actual NATS/vendor-facing wire value (string, never ordinal, per §5.1/
 * §6.3). `Invoice.documentEngine` is a separate, new field this task introduces (the anwender's
 * per-invoice engine choice, read by [de.littletrotter.domain.compliance.ArdennesXRules]); it
 * follows this file's own established local convention instead.
 */
@Converter(autoApply = false)
class DocumentEngineConverter : OrdinalEnumConverter<DocumentEngine>(DocumentEngine.entries)
