package de.littletrotter.domain.invoice

import jakarta.persistence.Embeddable
import java.time.LocalDate

/**
 * W7 — the BT reference fields grouped into one value object: BT-10 ([buyerReference]), BT-13
 * ([purchaseOrderReference]), BT-15 ([orderReference] + its date), BT-17 ([offerReference] + its
 * date), BT-11 ([projectNumber]), plus the app-level [costCenter]. Purely a structural
 * grouping on [Invoice] — every field keeps its exact pre-existing column name via
 * `@AttributeOverride` at the embedding site (`Invoice.references`), so this is a zero-schema-change
 * refactor. See `Invoice.purchaseOrderReference`'s own historical KDoc (moved to
 * [purchaseOrderReference] here) for the BT-10/BT-13/BT-15 disambiguation table.
 *
 * A plain class with body-level `var`s, not a constructor-parameter `data class` — same reason as
 * [de.littletrotter.domain.masterdata.PartyIdentifier]: no `kotlin-noarg` plugin here, so Hibernate
 * needs the implicit no-arg constructor a `data class` does not produce at the bytecode level.
 */
@Embeddable
class InvoiceReferences {

    /**
     * BT-10 — the buyer's reference (`ram:BuyerReference`). For a public-sector buyer this carries
     * the Leitweg-ID, i.e. a routing address, **not** an order number. Mandatory on every German
     * invoice (BR-DE-15 / PEPPOL-EN16931-R008); [ensureBuyerReferenceForFinalization] enforces it
     * before `SENT`, falling back to [de.littletrotter.domain.masterdata.Recipient.routingId].
     *
     * One of three reference terms that are easy to confuse — see [purchaseOrderReference].
     */
    var buyerReference: String? = null

    /**
     * BT-13 — the buyer's **purchase order reference**
     * (`ram:BuyerOrderReferencedDocument/ram:IssuerAssignedID`), optional per EN 16931.
     *
     * Named after EN 16931's own term ("Purchase order reference") precisely so it cannot be
     * mistaken for either neighbour. All three live on this value object and mean different things:
     *
     * | Field                     | Term  | CII element                          | Whose number?          |
     * |---------------------------|-------|--------------------------------------|------------------------|
     * | [buyerReference]          | BT-10 | `ram:BuyerReference`                 | buyer's reference / Leitweg-ID |
     * | [purchaseOrderReference]  | BT-13 | `ram:BuyerOrderReferencedDocument`   | the **buyer's** order number   |
     * | [orderReference]          | BT-15 | `ram:SellerOrderReferencedDocument`  | the **seller's** order number  |
     */
    var purchaseOrderReference: String? = null

    /** BT-15 seller reference/order number — neither [buyerReference] (BT-10) nor
     *  [purchaseOrderReference] (BT-13); see the table in that field's KDoc. */
    var orderReference: String? = null

    var orderReferenceDate: LocalDate? = null

    /** BT-17 Tender or lot reference (Offer/Quote number) */
    var offerReference: String? = null

    var offerReferenceDate: LocalDate? = null

    /**
     * BT-11 — Project reference (`ram:SpecifiedProcuringProject/ram:ID`). Resolved the
     * former open clarification point about this field's overlap with the EN 16931 catalog: this
     * *is* BT-11, not an app-level-only value. `BOUND` in the B2B catalog (`bound_property =
     * 'projectNumber'`), so a profile can only mark the term mandatory/conditional, never adds a
     * second input field. Nullable like the other references; the XML export skips the element
     * when null/blank.
     */
    var projectNumber: String? = null

    /** Cost-center at invoice level. */
    var costCenter: String? = null

    /**
     * Adopts every reference term from [other] in one named step: BT-10 and BT-13
     * travel as two separate fields since their split, and a credit note (the one caller,
     * [de.littletrotter.application.invoice.InvoiceStornoService]) must carry the original's
     * references unchanged so the recipient books it under the same Vorgang. One method instead
     * of a hand-maintained field list — a new reference term added here is copied automatically.
     */
    fun copyFrom(other: InvoiceReferences) {
        buyerReference = other.buyerReference
        purchaseOrderReference = other.purchaseOrderReference
        orderReference = other.orderReference
        orderReferenceDate = other.orderReferenceDate
        offerReference = other.offerReference
        offerReferenceDate = other.offerReferenceDate
        projectNumber = other.projectNumber
        costCenter = other.costCenter
    }
}
