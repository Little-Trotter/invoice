# kotlin-xpdf — PDF Template & Layout Constraints

- Typst is this product's only layout engine (ADR-030, 2026-09-27); all PDF rendering uses the
  Typst templates at `src/main/resources/typst/`: `standard-invoice.typ` (invoices) and
  `standard-quote.typ` (offers, derived from the invoice template; no payment block).
- **Table Layout**: Table pagination is handled by the Typst template with repeated headers across
  pages, parametrized by `TypstInvoiceData` from `InvoiceReportContextBuilder`.
- **Deterministic Geometry Measurement**: Never guess coordinates or visual layout. Use `PdfLayoutInspector` (`kotlin-xpdf/src/main/kotlin/de/littletrotter/xpdf/PdfLayoutInspector.kt`) to measure actual PDFBox bounding boxes and coordinates.
