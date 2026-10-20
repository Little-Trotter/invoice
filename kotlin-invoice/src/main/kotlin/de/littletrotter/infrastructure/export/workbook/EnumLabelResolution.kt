package de.littletrotter.infrastructure.export.workbook

/**
 * Shared by [InvoiceHeaderSheetWriter] and [InvoicePositionSheetWriter]: resolves an enum value to
 * its caller-supplied i18n label, falling back to the enum's own [Enum.name] when the supplied
 * label map has no entry for it. A missing map entry is a caller bug in an otherwise-working label
 * set — that should never be worse than an unmapped-but-visible placeholder, let alone fail the
 * whole export.
 */
internal fun <T : Enum<T>> Map<T, String>.labelFor(key: T): String = this[key] ?: key.name
