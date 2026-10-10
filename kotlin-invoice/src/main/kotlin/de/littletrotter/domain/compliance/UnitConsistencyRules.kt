package de.littletrotter.domain.compliance

import java.time.LocalDate
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.shared.catalogue.InvoiceItemUnit
import de.littletrotter.shared.catalogue.InvoiceItemUnitDomain
import de.littletrotter.shared.b2b.B2bTerm
import java.math.BigDecimal

/**
 * A line item reduced to what [UnitConsistencyRules] needs. JPA-free, see [LinePeriodInput] for
 * why.
 *
 * @param unitCode the *transmitted* BT-130 code. On the entity it's derived from [unit] and
 *   can't diverge — on the wire (`InvoiceItemDto.unitCode`) and in a legacy message it can, which
 *   is exactly what `LT-UNIT-01` checks for.
 */
data class LineUnitInput(
    val positionId: String,
    /** BT-153. */
    val itemName: String,
    /** BT-154. */
    val itemDescription: String? = null,
    /** BT-129. */
    val quantity: BigDecimal,
    /** BT-130 — as transmitted. */
    val unitCode: String?,
    val unit: InvoiceItemUnit,
)

/**
 * Value-range rules per unit, not text matching: checks whether the quantity (BT-129) fits the
 * unit it's recorded in (BT-130), and whether that unit is coded correctly. Whether the item
 * name (BT-153) contains a word like "hours" is deliberately *not* checked — that's
 * wording-dependent and would false-flag any English or abbreviated name.
 *
 * | Rule | Meaning | Severity |
 * |---|---|---|
 * | `LT-UNIT-01` | BT-130 is the unit's UN/ECE Rec 20 code (`HUR`, `DAY`, `H87`, `MTK`, `C62`, …) | ERROR |
 * | `LT-UNIT-02` | unit `EURO` ⇒ BT-129 is exactly 1 | ERROR |
 * | `LT-UNIT-03` | countable unit (piece, flat rate, callout) ⇒ BT-129 is a whole number | ERROR |
 * | `LT-UNIT-04` | BT-129 > 0 | ERROR |
 * | `LT-UNIT-05` | BT-153 is not blank (EN 16931 BR-25) | ERROR |
 * | `LT-UNIT-06` | BT-154, if set, is not word-for-word identical to BT-153 | WARNING |
 * | `LT-UNIT-07` | BT-153 doesn't look like a bare article number or contract reference | WARNING |
 * | `LT-UNIT-08` | quantity/unit are plausible for the description (no blanket piece/flat-rate use when the text itself names a quantity token like "30 h") | WARNING |
 *
 * `LT-UNIT-02`: for unit "Euro" the amount already *is* the unit price. A quantity other than 1
 * would multiply a money amount by a money amount, so the line total would come out in euro²,
 * not euro — that's also why this unit carries no Rec-20 code of its own but `C62` ("one"), see
 * [InvoiceItemUnit].
 *
 * `LT-UNIT-04` and negative lines: a discount or credit line has a **negative unit price** and a
 * positive quantity — the sign lives in the price, not the quantity (see the B1 comment in
 * [de.littletrotter.domain.invoice.InvoiceItem.validate]). This rule therefore never flags such a
 * line; it only flags a negative or zero *quantity*, which isn't a valid measure.
 */
object UnitConsistencyRules {

    const val UNIT_CODE_MISMATCH = "LT-UNIT-01"
    const val CURRENCY_UNIT_QUANTITY_NOT_ONE = "LT-UNIT-02"
    const val COUNTABLE_UNIT_FRACTIONAL_QUANTITY = "LT-UNIT-03"
    const val QUANTITY_NOT_POSITIVE = "LT-UNIT-04"
    const val ITEM_NAME_BLANK = "LT-UNIT-05"
    const val ITEM_DESCRIPTION_DUPLICATES_NAME = "LT-UNIT-06"
    const val ITEM_NAME_LOOKS_LIKE_BARE_CODE = "LT-UNIT-07"
    const val QUANTITY_LIKE_TOKEN_IN_DESCRIPTION = "LT-UNIT-08"

    /** Units where only a whole-number quantity makes sense: counted things, not measurements. */
    private val COUNTABLE_DOMAINS = setOf(InvoiceItemUnitDomain.COUNT)

    /**
     * D-R2(a) — a trimmed BT-153 that is *entirely* a short, digit-bearing, upper-case-only code
     * (no lower-case word at all): `"243538"`, `"ART-4711"`, `"KST/4711"`. Real German item names
     * always carry at least one lower-case letter, so this never fires on a genuine description —
     * only on the article-number-instead-of-a-name mistake the directive calls out.
     */
    private val BARE_ARTICLE_CODE = Regex("^[A-Z0-9][A-Z0-9/-]*$")

    /**
     * D-R2(a) — the *entire* BT-153 is nothing but a contract/order reference ("lt. Vertrag …",
     * "laut Wartungsvertrag XYZ12345"), optionally followed by a single reference token (the
     * contract number). A description that names the actual service *and* mentions the contract
     * (the directive's own corrected example, "Wartung der Heizungsanlage (lt. Wartungsvertrag
     * XYZ12345)") never matches this — the anchor `^` requires the reference to be the whole
     * value, not a trailing remark.
     */
    private val BARE_CONTRACT_REFERENCE = Regex("(?i)^(lt\\.?|laut)\\s+\\S*vertrag\\S*(\\s+\\S+)?$")

    /** D-R2(b) — a quantity mentioned inside the free text itself ("30 h", "12 km", "45 qm"),
     *  the tell-tale sign of the "pauschale Stück-Nutzung" mistake the directive's own "Reinigung
     *  Außenanlage 30 h" example demonstrates: the real measure is hiding in the text instead of
     *  in BT-129/BT-130. */
    private val QUANTITY_LIKE_TOKEN = Regex("\\d+\\s*(h|Std|km|m²|qm)(?!\\p{L})")

    /** D-R2(b) — units for which "1" is the correct, deliberate quantity (a genuine lump sum or a
     *  single countable item); a quantity-like token in the text under one of these is the
     *  plausibility mismatch, not a false positive on a real single-piece/lump-sum position. */
    private val LUMP_SUM_LIKE_UNITS = setOf(InvoiceItemUnit.PIECE, InvoiceItemUnit.FLAT_RATE)

    fun evaluate(lines: List<LineUnitInput>): List<ComplianceFinding> =
        lines.flatMap { evaluateLine(it) }

    fun evaluateLine(line: LineUnitInput): List<ComplianceFinding> = buildList {
        checkUnitCodeMismatch(line, this)
        checkQuantityNotPositive(line, this)
        checkCurrencyUnitQuantity(line, this)
        checkCountableUnitFractionalQuantity(line, this)
        checkItemNameBlank(line, this)
        checkItemDescriptionDuplicatesName(line, this)
        checkItemNameLooksLikeBareCode(line, this)
        checkQuantityLikeTokenInDescription(line, this)
    }

    private fun checkUnitCodeMismatch(line: LineUnitInput, findings: MutableList<ComplianceFinding>) {
        val expectedCode = line.unit.uneceCode
        if (line.unitCode != expectedCode) {
            findings.add(
                finding(
                    line, FindingDefinition(UNIT_CODE_MISMATCH, ComplianceSeverity.ERROR, "compliance.unit.unitCodeMismatch"),
                    listOf(B2bTerm.BT_130),
                    listOf(line.unitCode, expectedCode, line.unit.name),
                ),
            )
        }
    }

    private fun checkQuantityNotPositive(line: LineUnitInput, findings: MutableList<ComplianceFinding>) {
        if (line.quantity.signum() <= 0) {
            findings.add(
                finding(
                    line, FindingDefinition(QUANTITY_NOT_POSITIVE, ComplianceSeverity.ERROR, "compliance.unit.quantityNotPositive"),
                    listOf(B2bTerm.BT_129),
                    listOf(line.quantity, line.unit.name),
                ),
            )
        }
    }

    private fun checkCurrencyUnitQuantity(line: LineUnitInput, findings: MutableList<ComplianceFinding>) {
        if (line.unit == InvoiceItemUnit.EURO && line.quantity.compareTo(BigDecimal.ONE) != 0) {
            findings.add(
                finding(
                    line, FindingDefinition(CURRENCY_UNIT_QUANTITY_NOT_ONE, ComplianceSeverity.ERROR, "compliance.unit.currencyQuantityNotOne"),
                    listOf(B2bTerm.BT_129, B2bTerm.BT_130),
                    listOf(line.quantity),
                ),
            )
        }
    }

    private fun checkCountableUnitFractionalQuantity(line: LineUnitInput, findings: MutableList<ComplianceFinding>) {
        if (line.unit.domain in COUNTABLE_DOMAINS && !line.quantity.isWholeNumber()) {
            findings.add(
                finding(
                    line, FindingDefinition(COUNTABLE_UNIT_FRACTIONAL_QUANTITY, ComplianceSeverity.ERROR, "compliance.unit.countableQuantityFractional"),
                    listOf(B2bTerm.BT_129, B2bTerm.BT_130),
                    listOf(line.quantity, line.unit.name),
                ),
            )
        }
    }

    private fun checkItemNameBlank(line: LineUnitInput, findings: MutableList<ComplianceFinding>) {
        if (line.itemName.isBlank()) {
            findings.add(
                finding(
                    line, FindingDefinition(ITEM_NAME_BLANK, ComplianceSeverity.ERROR, "compliance.unit.itemNameBlank"),
                    listOf(B2bTerm.BT_153), emptyList(),
                ),
            )
        }
    }

    private fun checkItemDescriptionDuplicatesName(line: LineUnitInput, findings: MutableList<ComplianceFinding>) {
        val description = line.itemDescription
        if (description != null && description.trim().equals(line.itemName.trim(), ignoreCase = true)) {
            findings.add(
                finding(
                    line, FindingDefinition(ITEM_DESCRIPTION_DUPLICATES_NAME, ComplianceSeverity.WARNING, "compliance.unit.itemDescriptionDuplicatesName"),
                    listOf(B2bTerm.BT_153, B2bTerm.BT_154),
                    emptyList(),
                ),
            )
        }
    }

    private fun checkItemNameLooksLikeBareCode(line: LineUnitInput, findings: MutableList<ComplianceFinding>) {
        val trimmedName = line.itemName.trim()
        if (trimmedName.isNotEmpty() && (BARE_ARTICLE_CODE.matches(trimmedName) || BARE_CONTRACT_REFERENCE.matches(trimmedName))) {
            findings.add(
                finding(
                    line, FindingDefinition(ITEM_NAME_LOOKS_LIKE_BARE_CODE, ComplianceSeverity.WARNING, "compliance.unit.itemNameLooksLikeBareCode"),
                    listOf(B2bTerm.BT_153, B2bTerm.BT_155),
                    listOf(line.itemName),
                ),
            )
        }
    }

    private fun checkQuantityLikeTokenInDescription(line: LineUnitInput, findings: MutableList<ComplianceFinding>) {
        if (line.unit in LUMP_SUM_LIKE_UNITS && line.quantity.compareTo(BigDecimal.ONE) == 0) {
            val text = listOfNotNull(line.itemName, line.itemDescription).joinToString(" ")
            if (QUANTITY_LIKE_TOKEN.containsMatchIn(text)) {
                findings.add(
                    finding(
                        line, FindingDefinition(QUANTITY_LIKE_TOKEN_IN_DESCRIPTION, ComplianceSeverity.WARNING, "compliance.unit.quantityLikeTokenInDescription"),
                        listOf(B2bTerm.BT_129, B2bTerm.BT_130, B2bTerm.BT_153),
                        listOf(line.itemName, line.unit.name),
                    ),
                )
            }
        }
    }

    /** [ruleId]/[severity]/[messageKey] bundled so [finding] takes one parameter for them
     *  instead of three (LongParameterList) — every call site above only ever varies
     *  them together, alongside the [line]-derived target and the [businessTerms]/[args]
     *  evidence. */
    private data class FindingDefinition(
        val ruleId: String,
        val severity: ComplianceSeverity,
        val messageKey: String,
    )

    private fun finding(
        line: LineUnitInput,
        definition: FindingDefinition,
        businessTerms: List<B2bTerm>,
        args: List<Any?>,
    ) = ComplianceFinding(
        ruleId = definition.ruleId,
        severity = definition.severity,
        messageKey = definition.messageKey,
        businessTerms = businessTerms,
        target = FindingTarget.Position(line.positionId),
        args = args,
    )

    /** `2`, `2.0`, and `2.0000` are whole numbers, `2.5` is not — the stored scale must not
     *  affect the answer. */
    private fun BigDecimal.isWholeNumber(): Boolean = stripTrailingZeros().scale() <= 0
}

fun Invoice.unitInputs(): List<LineUnitInput> = itemList.map { item ->
    LineUnitInput(
        positionId = item.positionId,
        itemName = item.description,
        itemDescription = item.itemDescription,
        quantity = item.quantity,
        unitCode = item.unitCode,
        unit = item.unit,
    )
}

fun Invoice.evaluateUnitConsistency(): List<ComplianceFinding> =
    UnitConsistencyRules.evaluate(unitInputs())

/** Same rationale as [PeriodConsistencyRuleSet]. */
object UnitConsistencyRuleSet : ComplianceRuleSet {
    override val id = "UNIT"
    override val levels = setOf(ComplianceLevel.INVOICE)

    override fun evaluate(subject: ComplianceSubject, today: LocalDate): List<ComplianceFinding> {
        val invoice = (subject as? ComplianceSubject.OfInvoice)?.invoice ?: return emptyList()
        return UnitConsistencyRules.evaluate(invoice.unitInputs())
    }
}
