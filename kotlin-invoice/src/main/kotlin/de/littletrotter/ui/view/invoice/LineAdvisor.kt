package de.littletrotter.ui.view.invoice

import de.littletrotter.shared.catalogue.InvoiceItemUnit
import java.math.BigDecimal

/**
 * One non-blocking data-quality hint about a single invoice line. Purely advisory:
 * never thrown, never persisted, never consulted by [de.littletrotter.domain.invoice.Invoice
 * .validate] or the finalization gate. [code] identifies which check fired (stable, for tests and
 * for a caller that wants to deduplicate); [messageKey] is the i18n key the UI resolves to German
 * text.
 */
data class LineAdvice(
    val code: String,
    val messageKey: String,
)

/**
 * The two soft advisors, kept as small pure functions in
 * the UI layer (not the domain): both are heuristics over free text, not business rules, and both
 * must stay strictly advisory (Definition of Done: "never blocks finalization"). See
 * the Requirement-Discounter gap report's own analysis of what Nekar actually rejects.
 *
 * Deliberately its own file, separate from every item dialog: another in-flight change
 * touches the item dialogs' article-selection listener, and this advisor is wired in
 * next to that listener, not into it — keeping this logic in one small, dialog-independent file
 * keeps the merge surface tiny.
 *
 * Both checks are pattern-matched against the **trimmed, full** description string with
 * `Regex.matches`/`containsMatchIn` — never a substring heuristic — precisely so a description
 * that merely *contains* a contract number somewhere alongside real content (the corrected
 * example, "Wartung der Heizungsanlage (lt. Wartungsvertrag XYZ12345)") does not fire. Conservative
 * on purpose (task Definition of Done: "prefer misses over noise"); the pattern matrix below is
 * exactly the set of cases proven by `LineAdvisorTest` — extend both together.
 */
object LineAdvisor {

    /** BT-153 is only a bare article-style code/number — belongs in BT-155, not as the name. */
    const val BT153_BARE_CODE = "BT153_BARE_CODE"

    /** BT-153 is only a reference clause ("lt./gemäß/siehe <Vertrag/Auftrag>"), no actual
     *  description of what was delivered. */
    const val BT153_REFERENCE_ONLY = "BT153_REFERENCE_ONLY"

    /** The description names a quantity+unit (e.g. "30 h") while the line itself is billed as
     *  a single Stück/Pauschale — the quantity likely belongs in BT-129/BT-130 instead. */
    const val QUANTITY_UNIT_MISMATCH = "QUANTITY_UNIT_MISMATCH"

    // Whole-string only (never a substring): "4711", "12345".
    private val bareDigitsPattern = Regex("^\\d{2,}$")

    private val bareShortCodePattern = Regex("^[A-Za-z]{1,4}-?\\d{2,8}$")

    // Anchored at start to avoid matching when connector appears after actual description.
    private val referenceOnlyPattern = Regex(
        "^(Wartung|Leistung(en)?)?\\s*(lt\\.|gemäß|siehe)\\s+\\S.*$",
        RegexOption.IGNORE_CASE,
    )

    // Detects quantity+unit patterns (e.g. "30 h" billed as single unit).
    private val quantityUnitPattern = Regex(
        "\\d+\\s*(h|Std\\.?|Stunden|km|m²|qm|kg|t)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val singleUnitBillingUnits = setOf(InvoiceItemUnit.PIECE, InvoiceItemUnit.FLAT_RATE)

    fun advise(description: String, unit: InvoiceItemUnit, quantity: BigDecimal): List<LineAdvice> = buildList {
        val trimmed = description.trim()
        if (trimmed.isNotEmpty()) {
            if (bareDigitsPattern.matches(trimmed) || bareShortCodePattern.matches(trimmed)) {
                add(LineAdvice(BT153_BARE_CODE, "invoiceDetail.advisor.bt153.bareCode"))
            } else if (referenceOnlyPattern.matches(trimmed)) {
                add(LineAdvice(BT153_REFERENCE_ONLY, "invoiceDetail.advisor.bt153.referenceOnly"))
            }
        }

        val isSingleUnitLine = unit in singleUnitBillingUnits && quantity.compareTo(BigDecimal.ONE) == 0
        if (isSingleUnitLine && quantityUnitPattern.containsMatchIn(description)) {
            add(LineAdvice(QUANTITY_UNIT_MISMATCH, "invoiceDetail.advisor.quantityUnitMismatch"))
        }
    }
}
