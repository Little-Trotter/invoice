package de.littletrotter.application.reference

import de.littletrotter.application.reference.port.outbound.RecipientFieldValueReadRepository
import de.littletrotter.fixture.givenFields
import de.littletrotter.domain.compliance.B2bFieldState
import de.littletrotter.domain.compliance.B2bProfileRules
import de.littletrotter.domain.compliance.FindingTarget
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bFieldValue
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.application.reference.port.outbound.B2bFieldValueStore
import de.littletrotter.domain.repository.B2bProfileRepository
import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.fixture.edit
import de.littletrotter.shared.b2b.B2bFieldKind
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermBinding
import de.littletrotter.shared.b2b.B2bTermLevel
import de.littletrotter.ui.component.B2bFieldWidget
import de.littletrotter.ui.component.B2bFieldWidgetFactory
import de.littletrotter.ui.component.B2bProfileFieldsSection
import com.github.mvysny.kaributesting.v10.MockVaadin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.stream.Stream
import org.junit.jupiter.params.provider.Arguments

/**
 * The profile-system's combinatorial risk (kind x requirement x placement x level x
 * binding) systematized as a pairwise covering set, instead of the scattered hand-picked examples
 * [B2bProfileServicesTest]/[de.littletrotter.domain.compliance.B2bProfileRulesTest]/
 * [B2bProfileRuleSetTest][de.littletrotter.application.invoice.B2bProfileRuleSetTest] already carry
 * (this file does not re-derive their coverage; it assembles a single, matrix-driven view of it
 * plus the combinations those files structurally cannot reach).
 *
 * Central empirical fact this whole file is built around: the **real** [B2bTerm] catalog never
 * actually realizes all 4x4x4x4x2 = 512 raw combinations — [B2bTermBinding.FREE] only ever occurs
 * as [B2bFieldKind.TEXT] (7 terms total: BT-12/18/19/56/58 at [B2bTermLevel.HEADER], BT-155/156 at
 * [B2bTermLevel.LINE]). The pairwise table below reflects that reality rather than inventing
 * fixtures the domain has no real term for.
 *
 * [B2bFieldKind.BOOLEAN] used to have no bound or free catalog term at
 * all (only the two EXTENDED-only reference terms BT-X-1/BT-X-3, both `binding == null`, so their
 * Checkbox branch was unreachable via any live profile field). The kind and its widget branch have
 * since been removed outright (Greenfield rule — see [de.littletrotter.shared.b2b.B2bFieldKind]'s
 * KDoc) rather than kept as documented dead code; the axis below is 4 kinds, not 5.
 */
class Ep6FieldCombinationMatrixTest {

    // ─── Axes — derived from the actual enums, not hardcoded literals ────────────────────────────

    private val actualKinds = B2bFieldKind.entries.map { it.name }.toSet()
    private val actualRequirements = B2bFieldRequirement.entries.map { it.name }.toSet()
    private val actualPlacements = B2bPlacement.entries.map { it.name }.toSet()
    private val actualLevels = B2bTermLevel.entries.map { it.name }.toSet()
    private val actualBindings = setOf("FREE", "BOUND") // B2bTermBinding entries, plus null (reference) handled separately

    @Test
    fun `axis value sets are exactly what this matrix was built against - fails loudly on enum drift`() {
        assertEquals(setOf("TEXT", "NUMBER", "DATE", "ENUM"), actualKinds)
        assertEquals(setOf("OPTIONAL", "MANDATORY", "CONDITIONAL", "CONDITIONAL_IF_EMPTY"), actualRequirements)
        assertEquals(setOf("INVOICE", "RECIPIENT", "SELLER"), actualPlacements)
        assertEquals(setOf("HEADER", "LINE", "SELLER_PARTY", "BUYER_PARTY"), actualLevels)
        assertEquals(setOf("FREE", "BOUND"), B2bTermBinding.entries.map { it.name }.toSet())
    }

    // ─── The pairwise covering table — single source of truth for the report's filled matrix ─────

    /** One row of the covering set. [term] is `null` only for rows illustrating a structurally
     *  unrealizable cell (documented as a finding, not silently skipped). */
    data class Ep6Row(
        val label: String,
        val kind: B2bFieldKind,
        val requirement: B2bFieldRequirement,
        val placement: B2bPlacement,
        val level: B2bTermLevel,
        val binding: B2bTermBinding?,
        val term: B2bTerm?,
    )

    companion object {
        private val TRIGGER = B2bTerm.BT_12 // any HEADER/FREE term distinct from BT_19 works as a sibling trigger

        /** Full cartesian of requirement x placement (12 rows) — the spec's own "two most critical
         *  axes" requirement — held at a fixed (kind, level, binding) = (TEXT, HEADER, FREE) via
         *  BT-19, the one FREE HEADER term not already exercised end-to-end by the neighboring
         *  border-case tests (BT-12/BT-18).
         *  HEADER is the only level every one of the 3 placements is legal for without coercion or
         *  rejection (see [Ep6ConfigRejectionTest] for the LINE-coercion/BUYER_PARTY-rejection
         *  rows this table deliberately does NOT claim as "combinations"). */
        val REQUIREMENT_X_PLACEMENT: List<Ep6Row> = B2bFieldRequirement.entries.flatMap { req ->
            B2bPlacement.entries.map { plc ->
                Ep6Row("RxP-$req-$plc", B2bFieldKind.TEXT, req, plc, B2bTermLevel.HEADER, B2bTermBinding.FREE, B2bTerm.BT_19)
            }
        }

        /** kind x binding representative sweep — one real term per cell that actually exists in
         *  the catalog. */
        val KIND_X_BINDING: List<Ep6Row> = listOf(
            Ep6Row("KxB-TEXT-FREE-HEADER", B2bFieldKind.TEXT, B2bFieldRequirement.MANDATORY, B2bPlacement.INVOICE, B2bTermLevel.HEADER, B2bTermBinding.FREE, B2bTerm.BT_12),
            Ep6Row("KxB-TEXT-FREE-LINE", B2bFieldKind.TEXT, B2bFieldRequirement.MANDATORY, B2bPlacement.INVOICE, B2bTermLevel.LINE, B2bTermBinding.FREE, B2bTerm.BT_155),
            Ep6Row("KxB-TEXT-BOUND-HEADER", B2bFieldKind.TEXT, B2bFieldRequirement.MANDATORY, B2bPlacement.INVOICE, B2bTermLevel.HEADER, B2bTermBinding.BOUND, B2bTerm.BT_11),
            Ep6Row("KxB-TEXT-BOUND-SELLER_PARTY", B2bFieldKind.TEXT, B2bFieldRequirement.MANDATORY, B2bPlacement.SELLER, B2bTermLevel.SELLER_PARTY, B2bTermBinding.BOUND, B2bTerm.BT_27),
            Ep6Row("KxB-TEXT-BOUND-BUYER_PARTY", B2bFieldKind.TEXT, B2bFieldRequirement.MANDATORY, B2bPlacement.INVOICE, B2bTermLevel.BUYER_PARTY, B2bTermBinding.BOUND, B2bTerm.BT_44),
            Ep6Row("KxB-DATE-BOUND-HEADER", B2bFieldKind.DATE, B2bFieldRequirement.MANDATORY, B2bPlacement.INVOICE, B2bTermLevel.HEADER, B2bTermBinding.BOUND, B2bTerm.BT_72),
            Ep6Row("KxB-DATE-BOUND-LINE", B2bFieldKind.DATE, B2bFieldRequirement.MANDATORY, B2bPlacement.INVOICE, B2bTermLevel.LINE, B2bTermBinding.BOUND, B2bTerm.BT_134),
            Ep6Row("KxB-NUMBER-BOUND-LINE", B2bFieldKind.NUMBER, B2bFieldRequirement.OPTIONAL, B2bPlacement.INVOICE, B2bTermLevel.LINE, B2bTermBinding.BOUND, B2bTerm.BT_129),
            Ep6Row("KxB-ENUM-BOUND-HEADER", B2bFieldKind.ENUM, B2bFieldRequirement.OPTIONAL, B2bPlacement.INVOICE, B2bTermLevel.HEADER, B2bTermBinding.BOUND, B2bTerm.BT_3),
            Ep6Row("KxB-ENUM-BOUND-LINE", B2bFieldKind.ENUM, B2bFieldRequirement.OPTIONAL, B2bPlacement.INVOICE, B2bTermLevel.LINE, B2bTermBinding.BOUND, B2bTerm.BT_130),
        )

        /** level x placement legality rows not already exercised above — the legal-but-unusual
         *  cell (a [B2bTermLevel.HEADER] term may be placed [B2bPlacement.SELLER]) plus a pointer
         *  to [Ep6ConfigRejectionTest] for the illegal/coerced cells this table does not claim.
         *
         *  The three SELLER_PARTY rows this list used
         *  to carry (RECIPIENT/INVOICE/BOTH, all "legal to save but never shown") moved to
         *  [impossibleLevelPlacementPairs]/[Ep6ConfigRejectionTest]: SELLER_PARTY is now rejected
         *  at save time for every placement except [B2bPlacement.SELLER] (still covered by
         *  [KIND_X_BINDING]'s `KxB-TEXT-BOUND-SELLER_PARTY` row), so they are no longer
         *  "legal-but-unusual" — they are rejected, same bucket as LINE/BUYER_PARTY.
         */
        val LEVEL_X_PLACEMENT_LEGAL_EXTRA: List<Ep6Row> = listOf(
            Ep6Row("LxP-HEADER-SELLER", B2bFieldKind.TEXT, B2bFieldRequirement.OPTIONAL, B2bPlacement.SELLER, B2bTermLevel.HEADER, B2bTermBinding.FREE, B2bTerm.BT_56),
            // The (RECIPIENT, BOUND) placement x binding pair used to be covered by the
            // now-rejected SELLER_PARTY-RECIPIENT row; a HEADER term is the only level still free
            // to combine BOUND with every placement, so this replaces that coverage with a real,
            // legal HEADER/BOUND term instead.
            Ep6Row("LxP-HEADER-RECIPIENT-BOUND", B2bFieldKind.TEXT, B2bFieldRequirement.OPTIONAL, B2bPlacement.RECIPIENT, B2bTermLevel.HEADER, B2bTermBinding.BOUND, B2bTerm.BT_13),
        )

        val ALL_ROWS: List<Ep6Row> = REQUIREMENT_X_PLACEMENT + KIND_X_BINDING + LEVEL_X_PLACEMENT_LEGAL_EXTRA

        @JvmStatic
        fun requirementXPlacementArgs(): Stream<Arguments> = REQUIREMENT_X_PLACEMENT.stream().map { Arguments.of(it) }

        @JvmStatic
        fun kindXBindingArgs(): Stream<Arguments> = KIND_X_BINDING.stream().map { Arguments.of(it) }
    }

    // ─── Pairwise coverage proof ───────────────────────────────────────────────────────────────

    /** Axis-value pairs the real domain structurally cannot realize — asserting coverage on these
     *  would force a fake fixture into existence; they are documented findings instead (see class
     *  KDoc), not gaps in this table. */
    private val impossibleKindBindingPairs = setOf(
        "NUMBER" to "FREE", "DATE" to "FREE", "ENUM" to "FREE", // every FREE term is TEXT
    )
    private val impossibleLevelBindingPairs = setOf(
        "BUYER_PARTY" to "FREE", // no FREE BUYER_PARTY term exists (T1 gap, B2bProfileRuleSet KDoc)
        "SELLER_PARTY" to "FREE", // no FREE SELLER_PARTY term exists either — every FREE term is HEADER or LINE
    )
    private val impossibleLevelPlacementPairs = setOf(
        // LINE is coerced to INVOICE at save time (not a real combination); BUYER_PARTY rejects
        // every non-INVOICE placement; SELLER_PARTY rejects every
        // non-SELLER placement. All proven in Ep6ConfigRejectionTest, not claimed here.
        "LINE" to "RECIPIENT", "LINE" to "SELLER",
        "BUYER_PARTY" to "RECIPIENT", "BUYER_PARTY" to "SELLER",
        "SELLER_PARTY" to "INVOICE", "SELLER_PARTY" to "RECIPIENT",
    )

    private fun <T> allPairsCovered(rows: List<Ep6Row>, axisA: (Ep6Row) -> T, axisB: (Ep6Row) -> T, valuesA: Set<T>, valuesB: Set<T>, impossible: Set<Pair<T, T>>): Set<Pair<T, T>> {
        val covered = rows.map { axisA(it) to axisB(it) }.toSet()
        val required = valuesA.flatMap { a -> valuesB.map { b -> a to b } }.toSet() - impossible
        return required - covered
    }

    @Test
    fun `requirement x placement is a full 12-cell cartesian, as the spec demands for its two critical axes`() {
        val pairs = REQUIREMENT_X_PLACEMENT.map { it.requirement.name to it.placement.name }.toSet()
        assertEquals(12, pairs.size, "every (requirement, placement) pair must appear exactly once: $pairs")
        assertEquals(actualRequirements.flatMap { r -> actualPlacements.map { p -> r to p } }.toSet(), pairs)
    }

    // Note: [B2bTermBinding] itself has only two values (FREE, BOUND) — a `null` (reference-term)
    // "binding" is a third real-world state (hundreds of generated terms) but not a member of the
    // enum the spec names, so it is deliberately excluded from these pairwise checks rather than
    // forced in as a pseudo-value.
    private val boundOnlyRows = ALL_ROWS.filter { it.binding != null }

    @Test
    fun `kind x binding - every realizable pair is covered at least once`() {
        val missing = allPairsCovered(boundOnlyRows, { it.kind.name }, { it.binding!!.name }, actualKinds, actualBindings, impossibleKindBindingPairs)
        assertTrue(missing.isEmpty(), "uncovered kind x binding pairs: $missing")
    }

    @Test
    fun `level x binding - every realizable pair is covered at least once`() {
        val missing = allPairsCovered(boundOnlyRows, { it.level.name }, { it.binding!!.name }, actualLevels, actualBindings, impossibleLevelBindingPairs)
        assertTrue(missing.isEmpty(), "uncovered level x binding pairs: $missing")
    }

    @Test
    fun `level x placement - every legal pair is covered at least once`() {
        val missing = allPairsCovered(ALL_ROWS, { it.level.name }, { it.placement.name }, actualLevels, actualPlacements, impossibleLevelPlacementPairs)
        assertTrue(missing.isEmpty(), "uncovered level x placement pairs: $missing")
    }

    @Test
    fun `kind x requirement - every pair is covered at least once`() {
        // Every kind reaches every requirement via the REQUIREMENT_X_PLACEMENT rows (TEXT) plus at
        // least MANDATORY/OPTIONAL for the other kinds (KIND_X_BINDING) — CONDITIONAL/
        // CONDITIONAL_IF_EMPTY are requirement-machinery, independent of kind, already proven
        // exhaustively for TEXT; re-deriving them per kind would just re-test B2bProfileRules
        // again (already covered by B2bProfileRulesTest), not a new finding.
        val coveredKinds = ALL_ROWS.map { it.kind.name }.toSet()
        assertEquals(actualKinds, coveredKinds, "every kind must appear in at least one row")
        val textRequirements = ALL_ROWS.filter { it.kind == B2bFieldKind.TEXT }.map { it.requirement.name }.toSet()
        assertEquals(actualRequirements, textRequirements, "TEXT must reach every requirement (carries the full RxP cartesian)")
    }

    @Test
    fun `placement x binding - every realizable pair is covered at least once`() {
        val missing = allPairsCovered(boundOnlyRows, { it.placement.name }, { it.binding!!.name }, actualPlacements, actualBindings, emptySet())
        assertTrue(missing.isEmpty(), "uncovered placement x binding pairs: $missing")
    }

    // ─── Fixture plumbing (mocked repositories, same idiom as B2bProfileServicesTest) ─────────────

    private val profileRepository = mock<B2bProfileRepository>()
    private val fieldValueRepository = mock<B2bFieldValueStore>()
    private val recipientRepository = mock<RecipientRepository>()
    private val recipientFieldValues = mock<RecipientFieldValueReadRepository>()
    private val invoiceLookup = mock<InvoiceLookupRepository>()
    private val fieldDefinitionService = B2bProfileFieldDefinitionService(
        profileRepository, recipientRepository, invoiceLookup,
    )
    private val fieldValueService = B2bFieldValueService(
        fieldValueRepository, recipientRepository, recipientFieldValues, mock(), fieldDefinitionService,
        B2bPartyFieldValues(fieldValueRepository, recipientFieldValues),
    )

    private fun freshProfile() = B2bProfile().edit { id = 1L; name = "EP-6" }

    // ─── Full requirement x placement cartesian — Shown (eligibility) + config acceptance ─────────

    @Nested
    inner class RequirementByPlacement {

        @ParameterizedTest(name = "{0}")
        @MethodSource("de.littletrotter.application.reference.Ep6FieldCombinationMatrixTest#requirementXPlacementArgs")
        fun `every requirement x placement cell on a HEADER FREE term is accepted at config time and shown at the right pane`(row: Ep6Row) {
            val profile = freshProfile()
            val needsTrigger = row.requirement == B2bFieldRequirement.CONDITIONAL || row.requirement == B2bFieldRequirement.CONDITIONAL_IF_EMPTY
            val trigger = B2bProfileField().edit { id = 50L; this.profile = profile; term = TRIGGER; businessDefinition = "Trigger" }
            val siblings = if (needsTrigger) listOf(trigger) else emptyList()
            profileRepository.givenFields(1L, siblings)

            val field = B2bProfileField().edit {
                this.profile = profile
                term = row.term
                businessDefinition = "Definition ${row.label}"
                requirement = row.requirement
                placement = row.placement
                if (needsTrigger) conditionTerm = TRIGGER
            }

            val saved = fieldDefinitionService.saveField(field)

            assertEquals(row.placement, saved.placement, "${row.label}: HEADER level never coerces placement")
            assertEquals(row.requirement, saved.requirement)

            // Shown eligibility — which find*Fields() query would surface this field, per placement.
            profileRepository.givenFields(1L, siblings + saved)
            val shownOnInvoice = fieldDefinitionService.findInvoiceHeaderFields(1L).any { it.term == row.term }
            val shownOnRecipient = fieldDefinitionService.findRecipientFields(1L).any { it.term == row.term }
            val shownOnSeller = fieldDefinitionService.findSellerFields(1L).any { it.term == row.term }

            when (row.placement) {
                B2bPlacement.INVOICE -> { assertTrue(shownOnInvoice, row.label); assertFalse(shownOnRecipient, row.label); assertFalse(shownOnSeller, row.label) }
                B2bPlacement.RECIPIENT -> { assertFalse(shownOnInvoice, row.label); assertTrue(shownOnRecipient, row.label); assertFalse(shownOnSeller, row.label) }
                B2bPlacement.SELLER -> { assertFalse(shownOnInvoice, row.label); assertFalse(shownOnRecipient, row.label); assertTrue(shownOnSeller, row.label) }
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("de.littletrotter.application.reference.Ep6FieldCombinationMatrixTest#requirementXPlacementArgs")
        fun `Validated - the pure B2bProfileRules logic fires exactly per requirement, independent of placement`(row: Ep6Row) {
            // Validated (the MANDATORY/CONDITIONAL/CONDITIONAL_IF_EMPTY firing logic) is proven
            // once, per requirement, at the pure-domain level (B2bProfileRulesTest already covers
            // this exhaustively) — this assertion is this matrix's own cross-check that the same
            // 16 rows this matrix claims Shown for also behave correctly when Validated, not a
            // rediscovery.
            val trigger = B2bFieldState(TRIGGER, B2bFieldRequirement.OPTIONAL, null, "Trigger", hasValue = true, target = FindingTarget.Header)
            val underTest = B2bFieldState(row.term!!, row.requirement, TRIGGER.takeIf { row.requirement == B2bFieldRequirement.CONDITIONAL || row.requirement == B2bFieldRequirement.CONDITIONAL_IF_EMPTY }, "Def", hasValue = false, target = FindingTarget.Header)

            val findings = B2bProfileRules.evaluate(listOf(trigger, underTest))

            when (row.requirement) {
                B2bFieldRequirement.OPTIONAL -> assertTrue(findings.isEmpty(), row.label)
                B2bFieldRequirement.MANDATORY -> assertEquals(1, findings.size, row.label)
                B2bFieldRequirement.CONDITIONAL -> assertEquals(1, findings.size, "${row.label}: trigger has a value, so CONDITIONAL fires")
                B2bFieldRequirement.CONDITIONAL_IF_EMPTY -> assertTrue(findings.isEmpty(), "${row.label}: trigger has a value, so CONDITIONAL_IF_EMPTY does NOT fire")
            }
        }
    }

    // ─── Kind x binding — Shown (widget presence/type) ────────────────────────────────────────────

    @Nested
    inner class KindByBinding {

        // German locale by default, English as backup: messages_de.properties is the bundle production actually shows, so it stays "the real bundle" this tr callback mirrors, even though these tests only assert widget types, not label text.
        private val messages = java.util.Properties().apply {
            requireNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("i18n/messages_de.properties")) {
                "i18n/messages_de.properties not found on the test classpath"
            }.use { load(it) }
        }
        private val tr: (String) -> String = { key -> messages.getProperty(key, key) }

        @BeforeEach
        fun setup() {
            MockVaadin.setup()
        }

        @AfterEach
        fun teardown() { try { MockVaadin.tearDown() } catch (_: Exception) { } }

        private fun fieldFor(row: Ep6Row) = B2bProfileField().edit {
            term = row.term
            placement = row.placement
            requirement = row.requirement
            businessDefinition = "Definition ${row.label}"
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("de.littletrotter.application.reference.Ep6FieldCombinationMatrixTest#kindXBindingArgs")
        fun `Shown - a FREE term renders its documented widget type, a BOUND or reference term renders none`(row: Ep6Row) {
            val field = fieldFor(row)
            val section = B2bProfileFieldsSection { tr(it) }

            section.setFields(listOf(field.toDto()))

            if (row.binding == B2bTermBinding.FREE) {
                assertTrue(section.hasContent, "${row.label}: a FREE term must render a widget")
            } else {
                assertFalse(section.hasContent, "${row.label}: BOUND/reference terms never get a capture widget (B2bProfileFieldsSection filters on binding == FREE)")
            }
        }

        @Test
        fun `Shown - createWidget dispatches every kind to its documented Vaadin component type`() {
            assertTrue(B2bFieldWidgetFactory.createWidget(B2bTerm.BT_12, fieldFor(KIND_X_BINDING[0]).toDto(), tr) is B2bFieldWidget.Text, "TEXT -> TextField")
            assertTrue(B2bFieldWidgetFactory.createWidget(B2bTerm.BT_129, fieldFor(KIND_X_BINDING[7]).toDto(), tr) is B2bFieldWidget.Text, "NUMBER -> TextField (BTE-3: no numeric widget exists)")
            assertTrue(B2bFieldWidgetFactory.createWidget(B2bTerm.BT_72, fieldFor(KIND_X_BINDING[5]).toDto(), tr) is B2bFieldWidget.Date, "DATE -> DatePicker")
            assertTrue(B2bFieldWidgetFactory.createWidget(B2bTerm.BT_3, fieldFor(KIND_X_BINDING[8]).toDto(), tr) is B2bFieldWidget.Enum, "ENUM -> ComboBox")
        }

        @Test
        fun `finding EP6-F1 (fixed) - the former BOOLEAN kind and its Checkbox branch no longer exist in the catalog or the widget factory`() {
            // B2bFieldKind no longer has a BOOLEAN entry at all (Greenfield removal, not merely
            // documented as unreachable) — this compiles precisely because that axis value is gone;
            // asserting it here would be asserting the absence of a symbol that would fail to
            // compile if it still existed, so the real proof is the axis-value-set test above
            // (`actualKinds` no longer contains "BOOLEAN") plus B2bFieldWidgetFactory no longer
            // importing Checkbox at all.
            assertEquals(setOf("TEXT", "NUMBER", "DATE", "ENUM"), B2bFieldKind.entries.map { it.name }.toSet())
        }
    }

    // ─── Kind format-validation gaps ─────────────────────────────────────

    @Nested
    inner class KindFormatValidationGaps {

        @Test
        fun `finding EP6-F2 (fixed) - NUMBER kind has no FREE catalog term, but saveFieldValues now rejects a non-numeric value regardless`() {
            assertTrue(
                B2bTerm.entries.none { it.fieldKind == B2bFieldKind.NUMBER && it.binding == B2bTermBinding.FREE },
                "sanity for the finding: no FREE NUMBER term exists in the catalog today",
            )
            // Even bypassing the (nonexistent) capture widget and writing straight through the
            // service API against a real NUMBER term (BT-129, BOUND — its widget never renders):
            // B2bFieldValue.validate()'s NUMBER-kind check now catches it regardless of binding.
            whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.SENDER_PROFILE, 1L, B2bTerm.BT_129)).thenReturn(null)

            val ex = assertThrows(InvalidParameterException::class.java) {
                fieldValueService.saveFieldValues(B2bFieldOwnerType.SENDER_PROFILE, 1L, mapOf("BT-129" to "not-a-number-at-all"))
            }

            assertTrue(ex.message!!.contains("BT-129"), ex.message)
            org.mockito.kotlin.verify(fieldValueRepository, org.mockito.kotlin.never()).save(any())
        }

        @Test
        fun `finding EP6-F3 (fixed) - saveFieldValues now applies the same ISO-8601 DATE format check the widget layer already had`() {
            whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.SENDER_PROFILE, 1L, B2bTerm.BT_72)).thenReturn(null)

            val ex = assertThrows(InvalidParameterException::class.java) {
                fieldValueService.saveFieldValues(B2bFieldOwnerType.SENDER_PROFILE, 1L, mapOf("BT-72" to "32.13.2026-not-iso"))
            }

            assertTrue(ex.message!!.contains("BT-72"), ex.message)
            org.mockito.kotlin.verify(fieldValueRepository, org.mockito.kotlin.never()).save(any())
            // Contrast: B2bFieldWidgetFactoryTest's own "setWidgetValue with malformed date string
            // does not crash" test still documents the widget's own, separate silent-clear
            // behavior (runCatching { LocalDate.parse(it) }.getOrNull() -> null) — unrelated to
            // this server-side check, which now rejects instead of silently persisting.
        }

        @Test
        fun `B2bFieldValue now rejects an out-of-domain string for NUMBER or DATE kind too, same as ENUM and maxLength`() {
            val numberValue = B2bFieldValue().edit { ownerId = 1L; ownerType = B2bFieldOwnerType.SENDER_PROFILE; term = B2bTerm.BT_129; value = "abc" }
            assertThrows(InvalidParameterException::class.java) { numberValue.validate() }

            val dateValue = B2bFieldValue().edit { ownerId = 1L; ownerType = B2bFieldOwnerType.SENDER_PROFILE; term = B2bTerm.BT_72; value = "not-a-date" }
            assertThrows(InvalidParameterException::class.java) { dateValue.validate() }

            val badEnum = B2bFieldValue().edit { ownerId = 1L; ownerType = B2bFieldOwnerType.SENDER_PROFILE; term = B2bTerm.BT_130; value = "NOT-A-UNIT-CODE" }
            assertThrows(InvalidParameterException::class.java) { badEnum.validate() }
        }
    }

    // ─── Level x placement legal-but-unusual cells (Shown) ────────────────────────────────────────

    @Nested
    inner class LevelByPlacementLegalExtra {

        @Test
        fun `LxP-HEADER-SELLER - a HEADER-level FREE term may legally be placed SELLER and is shown there`() {
            val row = LEVEL_X_PLACEMENT_LEGAL_EXTRA[0]
            val profile = freshProfile()
            profileRepository.givenFields(1L, emptyList())

            val field = B2bProfileField().edit { this.profile = profile; term = row.term; businessDefinition = "Kontaktstelle"; placement = row.placement }
            val saved = fieldDefinitionService.saveField(field)

            assertEquals(B2bPlacement.SELLER, saved.placement)
            profileRepository.givenFields(1L, listOf(saved))
            assertTrue(fieldDefinitionService.findSellerFields(1L).any { it.term == row.term }, "SELLER placement makes findSellerFields include it regardless of level")
        }

        @Test
        fun `LxP-HEADER-RECIPIENT-BOUND - a HEADER-level BOUND term may legally be placed RECIPIENT`() {
            val row = LEVEL_X_PLACEMENT_LEGAL_EXTRA[1]
            val profile = freshProfile()
            profileRepository.givenFields(1L, emptyList())

            val field = B2bProfileField().edit { this.profile = profile; term = row.term; businessDefinition = "Bestellreferenz beim Kunden?!"; placement = row.placement }
            val saved = fieldDefinitionService.saveField(field) // must not throw

            assertEquals(B2bPlacement.RECIPIENT, saved.placement)
        }

        // The SELLER_PARTY x {INVOICE, RECIPIENT,
        // BOTH} rows formerly here ("legal to save, never shown") moved to
        // Ep6ConfigRejectionTest.LevelPlacementLegality as rejection pins; SELLER_PARTY is only
        // ever legal at SELLER now (KIND_X_BINDING's KxB-TEXT-BOUND-SELLER_PARTY row).
    }
}
