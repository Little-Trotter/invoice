package de.littletrotter.xpdf.impl

import de.littletrotter.shared.document.DocumentLanguage

/**
 * The "en"-or-"de" normalisation applied to a frozen
 * [de.littletrotter.shared.InvoiceMetaDto.documentLanguage] value before it drives rendering:
 * exactly `"en"` reads as English, everything else (`null`, blank, an unknown code) reads as
 * German. Shared by [InvoiceReportContextBuilder.resolveRenderLanguage] (PDF/report context) and
 * [de.littletrotter.xpdf.impl.mustang.MustangExportModelMapper] (BT-20 on the wire) — one rule,
 * not two.
 */
fun resolveDocumentRenderLanguage(documentLanguage: String?): String = DocumentLanguage.renderCode(documentLanguage)
