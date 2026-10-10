package de.littletrotter.domain.reference

import de.littletrotter.shared.b2b.B2bTermLevel

/** Where a [B2bProfileField] appears. Constrained against the catalog
 *  term's [B2bTermLevel] in [B2bProfileField.assertPlacementCompatibleWithLevel]. Declaration
 *  order is deliberate: [B2bFieldEditorPanel][de.littletrotter.ui.view.referencedata
 *  .B2bFieldEditorPanel]'s placement `ComboBox` renders `entries` in this exact order (
 *  decision 3 — Rechnung first as the common case, Anbieter last as the seller-master-data
 *  special case).
 *
 *  A fourth value, `BOTH` (Anbieter+Rechnung), existed until PO review 2026-08-31: it predated
 *  [RECIPIENT] and was never reconsidered once Kunde became a real placement. Zero seed/real
 *  profile field ever used it (the one real seller-side field, BT-84 IBAN, is plain [SELLER]),
 *  so it was removed rather than relabeled — a combined placement can be reintroduced if a real
 *  need for one ever shows up. */
enum class B2bPlacement {
    /** Invoice form only. */
    INVOICE,

    /** Customer (Kunde) form only: a per-recipient value maintained on
     *  [de.littletrotter.domain.masterdata.Recipient] via
     *  `recipient_b2b_field_value`, flowing into that recipient's invoices as a customer-specific
     *  default (resolution order: invoice value > recipient value > profile default value). */
    RECIPIENT,

    /** Seller party (Anbieter) form only. */
    SELLER,
}
