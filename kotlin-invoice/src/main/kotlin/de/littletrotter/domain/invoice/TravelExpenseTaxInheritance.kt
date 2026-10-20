package de.littletrotter.domain.invoice

import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.shared.catalogue.InvoiceItemCategory

/** A business trip ([InvoiceItemCategory.TRIP], § 10 UStG Nebenleistung) is a
 *  non-independent ancillary supply to the main supply and therefore never carries its own tax
 *  rate: it takes whichever rule the invoice's own main-service item(s) already use
 *  (implementation §4). This is also the concrete guard against the mixed AE/S situation
 *  TAX-03/R5 forbids — a trip picking its own rate independently of the invoice's Hauptleistung
 *  is exactly how that mix could happen. Before the TRIP-category merge this set also held the now-deleted
 *  TRAVEL_EXPENSE/EXPENSE/PER_DIEM categories; a business trip's hotel/taxi/mileage
 *  lines and per-diem days are now children of one TRIP position, not separate categories, so a
 *  single-element set remains only for the callers below that still reason in terms of "which
 *  categories never pick their own rate". Not `private`: [de.littletrotter.application
 *  .invoice.InvoiceLifecycleService.setReverseChargeDecision] needs the same split to restore
 *  each category's own rule correctly on an OFF conversion, rather than re-deriving this set. */
val TRAVEL_LIKE_CATEGORIES = setOf(
    InvoiceItemCategory.TRIP,
)

/** R2 — the tax rule a trip position on this invoice must inherit: the first non-travel-like
 *  item's live [de.littletrotter.domain.invoice.InvoiceItem.taxRateRef]. `null` when the invoice
 *  has no main-service item yet (e.g. a trip is being added first) — callers must treat that as a
 *  validation error, never as "no tax rate needed". */
fun Invoice.mainServiceTaxRate(): TaxRate? =
    itemList.firstOrNull { it.category !in TRAVEL_LIKE_CATEGORIES }?.taxRateRef
