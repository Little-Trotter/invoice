# base-invoice.pdf

A single-item, single-tax-rate invoice rendered with the Typst engine from synthetic data only.
Stands in for the base PDF in unit tests whose subject is the export pipeline around the render
step (FacturX job routing, BR-FR-CTC wiring, render warm-up), not the render step itself — those
tests do not have the layout engine on their classpath.

Regenerate by rendering any synthetic `InvoiceDto` through the current `BasePdfRendererPort`
implementation and overwriting this file; nothing in the tests that consume it depends on the
exact page content, only on it being a valid, non-empty PDF with a real content stream (so
PDF/A-3 output-intent and color-space checks have something to operate on).
