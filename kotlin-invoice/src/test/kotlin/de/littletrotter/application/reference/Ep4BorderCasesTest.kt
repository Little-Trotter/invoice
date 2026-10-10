package de.littletrotter.application.reference

import com.github.mvysny.kaributesting.v10.MockVaadin
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
import de.littletrotter.domain.reference.converters.B2bTermConverter
import de.littletrotter.domain.repository.B2bFieldValueRepository
import de.littletrotter.domain.repository.B2bProfileFieldRepository
import de.littletrotter.domain.repository.B2bProfileRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.RecipientB2bFieldValueRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.shared.b2b.B2bFieldKind
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.shared.b2b.B2bTermBinding
import de.littletrotter.ui.component.B2bFieldWidget
import de.littletrotter.ui.component.B2bFieldWidgetFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The profile system's border-case catalog, the
 * unit/mocked-repository half: cases that need no real Postgres/XML plumbing at all, following the
 * exact fixture idiom [Ep6FieldCombinationMatrixTest]/[Ep6ConfigRejectionTest] already established
 * (mocked repositories, a fresh [B2bProfileFieldDefinitionService]/[B2bFieldValueService] per class). The IT-level border cases (delete
 * guards against real invoices, deactivation, concurrency, the "pane open" catalog-edit race) live
 * in [Ep4BorderCasesIT]; the XML/KoSIT-dependent ones (escaping, the SELLER-vs-RECIPIENT wire
 * proof) live in `kotlin-e2e-tests` (`Ep4BorderCasesE2EIT`).
 *
 * This file also seeds five already-recorded findings as their own pins: each is its own test
 * (not an edit of the read-only `Ep6*`/`NewFieldEndToEndE2EIT` files that first discovered them)
 * asserting CURRENT behavior, so the finding stays pinned in this living catalog.
 */
class Ep4BorderCasesTest {

    private val profileRepository = mock<B2bProfileRepository>()
    private val profileFieldRepository = mock<B2bProfileFieldRepository>()
    private val fieldValueRepository = mock<B2bFieldValueRepository>()
    private val recipientRepository = mock<RecipientRepository>()
    private val invoiceRepository = mock<InvoiceRepository>()
    private val recipientFieldValueRepository = mock<RecipientB2bFieldValueRepository>()
    private val service = B2bProfileFieldDefinitionService(
        profileRepository, profileFieldRepository, recipientRepository, invoiceRepository,
    )
    private val fieldValueService = B2bFieldValueService(
        fieldValueRepository, recipientFieldValueRepository, invoiceRepository, service,
        B2bPartyFieldValues(fieldValueRepository, recipientFieldValueRepository),
    )

    private fun freshProfile(id: Long = 1L, name: String = "EP-4") = B2bProfile().apply { this.id = id; this.name = name }

    private fun noSiblings(profileId: Long = 1L) {
        whenever(profileFieldRepository.findByProfileId(profileId)).thenReturn(emptyList())
    }

    private fun acceptSave() {
        whenever(profileFieldRepository.save(any())).thenAnswer { it.arguments[0] }
    }

    // ─── Spec case 3a — empty profile (zero fields) ───────────────────────────────────────────────

    @Nested
    inner class EmptyAndAllMandatoryProfiles {

        @Test
        fun `an empty profile (zero fields) shows nothing anywhere and blocks nothing`() {
            noSiblings(7L)
            val profileId = 7L

            assertTrue(service.findFields(profileId).isEmpty())
            assertTrue(service.findInvoiceHeaderFields(profileId).isEmpty())
            assertTrue(service.findSellerFields(profileId).isEmpty())
            assertTrue(service.findRecipientFields(profileId).isEmpty())
            assertTrue(service.findInvoiceItemFields(profileId).isEmpty())
            // Pure-domain confirmation: no fields at all means B2bProfileRules has nothing to
            // evaluate and can never produce a finding.
            assertTrue(B2bProfileRules.evaluate(emptyList()).isEmpty())
        }

        @Test
        fun `a profile where every catalog-eligible field is MANDATORY fires one independent finding per empty field, no cross-blocking`() {
            // BT-19/56/58 (HEADER FREE), BT-12/18 (as in the neighboring fixture), all MANDATORY,
            // all simultaneously empty — every field's requiredness is evaluated independently
            // (B2bProfileRules never short-circuits once one MANDATORY field already fired).
            val terms = listOf(B2bTerm.BT_19, B2bTerm.BT_56, B2bTerm.BT_58, B2bTerm.BT_12, B2bTerm.BT_18)
            val states = terms.map { term ->
                B2bFieldState(term, B2bFieldRequirement.MANDATORY, null, "Definition ${term.officialCode}", hasValue = false, target = FindingTarget.Header)
            }

            val findings = B2bProfileRules.evaluate(states)

            assertEquals(terms.size, findings.size, "every MANDATORY field must fire its own finding: $findings")
            assertEquals(terms.toSet(), findings.flatMap { it.businessTerms }.toSet(), "each finding must name exactly its own term")
        }
    }

    // ─── Spec case 3b — circular/conflicting CONDITIONAL_IF_EMPTY chains ─────────

    @Nested
    inner class CircularConditionalIfEmptyChains {

        @Test
        fun `finding EP4-F1 (fixed) - a 3-node CONDITIONAL_IF_EMPTY cycle (A if-empty B, B if-empty C, C if-empty A) is now rejected at config time`() {
            // B2bProfileField.assertValidConditionTarget's own KDoc: the chain walk follows
            // CONDITIONAL_IF_EMPTY edges too, exempting only the direct 2-node mutual XOR
            // pair (Ep6ConfigRejectionTest's own "mutual CONDITIONAL_IF_EMPTY XOR pair" control
            // case) — a longer, genuinely circular if-empty chain of 3+ nodes is exactly the
            // footgun rejected the same way a CONDITIONAL cycle already was.
            val profile = freshProfile()
            acceptSave()

            // A and B are fabricated as ALREADY-PERSISTED siblings (the same trick
            // Ep6ConfigRejectionTest's own "mutual CONDITIONAL_IF_EMPTY XOR pair" test uses):
            // assertValidConditionTarget requires a candidate's trigger to already exist among its
            // siblings, so building the cycle edge-by-edge through saveField itself would fail on
            // the very first edge (A -> B, B not yet a sibling) before a cycle is even in play —
            // that presence check, not cycle detection, is what would block it. Closing the LAST
            // edge (C -> A) through the real service call is what this test actually exercises.
            val a = B2bProfileField().apply {
                id = 201L; this.profile = profile; term = B2bTerm.BT_12; businessDefinition = "A"
                requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY; conditionTerm = B2bTerm.BT_18
            }
            val b = B2bProfileField().apply {
                id = 202L; this.profile = profile; term = B2bTerm.BT_18; businessDefinition = "B"
                requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY; conditionTerm = B2bTerm.BT_19
            }
            whenever(profileFieldRepository.findByProfileId(1L)).thenReturn(listOf(a, b))

            val c = B2bProfileField().apply {
                this.profile = profile; term = B2bTerm.BT_19; businessDefinition = "C"
                requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY; conditionTerm = B2bTerm.BT_12
            }
            // Must throw now — closing the cycle (C's trigger A, present among the siblings) is a
            // 3-hop if-empty cycle, rejected by the extended cycle-walk.
            val ex = assertThrows(InvalidParameterException::class.java) { service.saveField(c) }
            assertTrue(ex.message!!.contains("Zyklische bedingte Pflicht"), ex.message)
        }

        @Test
        fun `finding EP4-F1 (fixed) - the direct mutual CONDITIONAL_IF_EMPTY XOR pair (2 nodes) stays legal, not swept up by the extended cycle check`() {
            // The exemption this fix carves out explicitly: only cycles of 3+ nodes are
            // rejected, so the pre-existing XOR pattern (Ep6ConfigRejectionTest's own pinning test)
            // keeps working unchanged.
            val profile = freshProfile()
            acceptSave()
            val a = B2bProfileField().apply {
                id = 301L; this.profile = profile; term = B2bTerm.BT_72; businessDefinition = "A"
                requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY; conditionTerm = B2bTerm.BT_134
            }
            whenever(profileFieldRepository.findByProfileId(1L)).thenReturn(listOf(a))

            val b = B2bProfileField().apply {
                this.profile = profile; term = B2bTerm.BT_134; businessDefinition = "B"
                requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY; conditionTerm = B2bTerm.BT_72
            }
            val savedB = service.saveField(b)
            assertEquals(B2bTerm.BT_72, savedB.conditionTerm)
        }

        @Test
        fun `finding EP4-F1 - once closed, an empty 3-node if-empty cycle fires ALL three findings simultaneously - a config footgun, not a deadlock`() {
            // The runtime consequence of allowing this configuration: B2bProfileRules.evaluate
            // applies no recursion for CONDITIONAL_IF_EMPTY (a direct hasValue lookup on the
            // immediate trigger only, per its own KDoc) — so a cycle never infinite-loops, but
            // when every node in it is empty, EVERY node reads its own trigger as empty and
            // becomes required. Nothing resolves the cycle; the profile author gets three
            // simultaneous MANDATORY findings on an invoice that captured none of the three values.
            val a = B2bFieldState(B2bTerm.BT_12, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, B2bTerm.BT_18, "A", hasValue = false, target = FindingTarget.Header)
            val b = B2bFieldState(B2bTerm.BT_18, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, B2bTerm.BT_19, "B", hasValue = false, target = FindingTarget.Header)
            val c = B2bFieldState(B2bTerm.BT_19, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, B2bTerm.BT_12, "C", hasValue = false, target = FindingTarget.Header)

            val findings = B2bProfileRules.evaluate(listOf(a, b, c))

            assertEquals(3, findings.size, "every node of the closed, all-empty cycle must fire: $findings")
            assertEquals(setOf(B2bTerm.BT_12, B2bTerm.BT_18, B2bTerm.BT_19), findings.map { it.businessTerms.first() }.toSet())
        }

        @Test
        fun `filling exactly one node of the cycle only clears that node's own trigger-dependents, not the whole cycle`() {
            // A if-empty B (B now filled -> A no longer required), B if-empty C (C still empty ->
            // B still required), C if-empty A (A still empty -> C still required). Filling one
            // node breaks exactly one edge, not the cycle as a whole — worth pinning so a future
            // fix to this behavior does not accidentally assume the cycle is "all or nothing".
            val a = B2bFieldState(B2bTerm.BT_12, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, B2bTerm.BT_18, "A", hasValue = false, target = FindingTarget.Header)
            val b = B2bFieldState(B2bTerm.BT_18, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, B2bTerm.BT_19, "B", hasValue = true, target = FindingTarget.Header)
            val c = B2bFieldState(B2bTerm.BT_19, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, B2bTerm.BT_12, "C", hasValue = false, target = FindingTarget.Header)

            val findings = B2bProfileRules.evaluate(listOf(a, b, c))

            assertEquals(1, findings.size, "only C (whose trigger A is still empty) must fire: $findings")
            assertEquals(B2bTerm.BT_19, findings.single().businessTerms.first())
        }
    }

    // ─── Spec case 4a — same BT code on SELLER and RECIPIENT placement simultaneously ─────────────

    @Nested
    inner class SameBtCodeAcrossPlacements {

        @Test
        fun `the same BT code may be placed SELLER on one profile and RECIPIENT on another - no cross-profile rejection exists`() {
            // Within ONE profile this is structurally impossible (uq_b2b_profile_field_profile_bt_code
            // / B2bProfile.assertUniqueTerms — see DuplicateBtCodeAttempts below); saveField's own
            // invariants never look at sibling profiles at all, so two independent profiles placing
            // the identical term at opposite placements is silently legal at config time.
            val profileSeller = freshProfile(id = 101L, name = "EP4-SamBt-Seller")
            val profileRecipient = freshProfile(id = 102L, name = "EP4-SamBt-Recipient")
            noSiblings(101L)
            noSiblings(102L)
            acceptSave()

            val sellerField = service.saveField(
                B2bProfileField().apply { profile = profileSeller; term = B2bTerm.BT_56; businessDefinition = "Kontakt (Anbieter)"; placement = B2bPlacement.SELLER },
            )
            val recipientField = service.saveField(
                B2bProfileField().apply { profile = profileRecipient; term = B2bTerm.BT_56; businessDefinition = "Kontakt (Kunde)"; placement = B2bPlacement.RECIPIENT },
            )

            assertEquals(B2bTerm.BT_56, sellerField.term)
            assertEquals(B2bPlacement.SELLER, sellerField.placement)
            assertEquals(B2bTerm.BT_56, recipientField.term)
            assertEquals(B2bPlacement.RECIPIENT, recipientField.placement)
            // The wire-level consequence of this exact setup (which value, if either, reaches the
            // e-invoice) is proven in kotlin-e2e-tests' Ep4BorderCasesE2EIT.
        }
    }

    // ─── Spec case 4b — unknown/malformed BT code in catalog ──────────────────────────────────────

    @Nested
    inner class UnknownAndMalformedBtCode {

        @Test
        fun `B2bTermConverter reads an out-of-range or unknown SMALLINT code as null, never throws`() {
            val converter = B2bTermConverter()
            assertNull(converter.convertToEntityAttribute(999_999), "a code naming no known term must load as null (H27-32-style defensive read)")
            assertNull(converter.convertToEntityAttribute(-1))
            assertNull(converter.convertToEntityAttribute(null))
            assertEquals(B2bTerm.BT_19, converter.convertToEntityAttribute(19))
        }

        @Test
        fun `saveFieldValues silently ignores a key that does not parse to a known B2bTerm - documented contract, not a bug`() {
            assertNull(B2bTerm.fromOfficialCodeOrNull("BT-999999"))
            assertNull(B2bTerm.fromOfficialCodeOrNull("not-a-bt-code-at-all"))

            fieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE, 42L, mapOf("BT-999999" to "some value", "not-a-bt-code-at-all" to "x"))

            verify(fieldValueRepository, never()).findByOwnerAndBtCode(any(), any(), any())
            verify(fieldValueRepository, never()).save(any())
        }
    }

    // ─── Spec case 4c — duplicate BT code attempts ─────────────────────────────────────────────────

    @Nested
    inner class DuplicateBtCodeAttempts {

        @Test
        fun `attempting to add the same BT code twice to one profile is rejected, the first field is untouched`() {
            val profile = freshProfile()
            val first = B2bProfileField().apply { id = 500L; this.profile = profile; term = B2bTerm.BT_19; businessDefinition = "Erster Versuch" }
            whenever(profileFieldRepository.findByProfileId(1L)).thenReturn(listOf(first))

            val duplicate = B2bProfileField().apply { this.profile = profile; term = B2bTerm.BT_19; businessDefinition = "Zweiter Versuch, gleicher Term" }
            val ex = assertThrows(InvalidParameterException::class.java) { service.saveField(duplicate) }

            assertTrue(ex.message!!.contains("BT-19"), "the error must name the colliding term: ${ex.message}")
            verify(profileFieldRepository, never()).save(argThat { field -> field.businessDefinition == "Zweiter Versuch, gleicher Term" })
        }

        @Test
        fun `re-saving the SAME field (same id) with the same term is not a duplicate against itself`() {
            val profile = freshProfile()
            val existing = B2bProfileField().apply { id = 500L; this.profile = profile; term = B2bTerm.BT_19; businessDefinition = "Original" }
            whenever(profileFieldRepository.findByProfileId(1L)).thenReturn(listOf(existing))
            acceptSave()

            existing.businessDefinition = "Umbenannt"
            val saved = service.saveField(existing) // its own siblings-minus-itself computation must exclude id=500 from the "with candidate" check

            assertEquals("Umbenannt", saved.businessDefinition)
        }
    }

    // ─── Spec case 5 — value edge content ──────────────────────────────────────────────────────────

    @Nested
    inner class ValueEdgeContent {

        @Test
        fun `a value exactly at maxLength is accepted, one character over is rejected - never silently truncated`() {
            // BT-19: HEADER/TEXT/FREE, maxLength = 50 (B2bTerm.kt).
            assertEquals(50, B2bTerm.BT_19.maxLength)
            val atLimit = B2bFieldValue().apply { ownerId = 1L; ownerType = B2bFieldOwnerType.INVOICE; term = B2bTerm.BT_19; value = "x".repeat(50) }
            atLimit.validate() // must not throw

            val overLimit = B2bFieldValue().apply { ownerId = 1L; ownerType = B2bFieldOwnerType.INVOICE; term = B2bTerm.BT_19; value = "x".repeat(51) }
            val ex = assertThrows(InvalidParameterException::class.java) { overLimit.validate() }
            assertTrue(ex.message!!.contains("BT-19"), ex.message)
            // Rejection, not truncation: the value on the entity itself is untouched (still 51
            // chars) — nothing anywhere silently shortens it to fit.
            assertEquals(51, overLimit.value?.length)
        }

        @Test
        fun `finding EP4-F3 (fixed) - leading and trailing whitespace on a FREE value is now trimmed before it is persisted`() {
            whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 9L, B2bTerm.BT_56)).thenReturn(null)
            whenever(fieldValueRepository.save(any())).thenAnswer { it.arguments[0] }

            fieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE, 9L, mapOf("BT-56" to "  Frau Meier  "))

            verify(fieldValueRepository).save(argThat { value -> value.value == "Frau Meier" })
        }

        @Test
        fun `umlauts and eszett pass through validate() untouched - no charset restriction on a FREE TEXT term`() {
            val value = B2bFieldValue().apply {
                ownerId = 1L; ownerType = B2bFieldOwnerType.INVOICE; term = B2bTerm.BT_56
                this.value = "Straße, Müller & Söhne, Größe: äöüÄÖÜß"
            }
            value.validate() // must not throw
            assertEquals("Straße, Müller & Söhne, Größe: äöüÄÖÜß", value.value)
        }

        @Test
        fun `a pure-whitespace value is treated as blank by saveFieldValues and deletes any existing row, rather than storing whitespace`() {
            val existing = B2bFieldValue().apply { id = 77L; ownerId = 9L; ownerType = B2bFieldOwnerType.INVOICE; term = B2bTerm.BT_56; value = "old" }
            whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 9L, B2bTerm.BT_56)).thenReturn(existing)

            fieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE, 9L, mapOf("BT-56" to "   "))

            verify(fieldValueRepository).delete(77L)
            verify(fieldValueRepository, never()).save(any())
        }
    }

    // ─── Seeded finding pins ─────────────────────────────

    @Nested
    inner class SeededFindingPins {

        @BeforeEach
        fun setup() {
            MockVaadin.setup()
        }

        @AfterEach
        fun teardown() {
            try {
                MockVaadin.tearDown()
            } catch (_: Exception) {
                // best-effort, matches Ep6FieldCombinationMatrixTest's own teardown
            }
        }

        @Test
        fun `finding EP3-F1 - createWidget only ever flags MANDATORY as required, never CONDITIONAL_IF_EMPTY even while its trigger is currently empty`() {
            val mandatory = B2bProfileField().apply { term = B2bTerm.BT_12; businessDefinition = "Vertragsreferenz"; requirement = B2bFieldRequirement.MANDATORY }
            val conditionalIfEmpty = B2bProfileField().apply {
                term = B2bTerm.BT_18; businessDefinition = "Belegobjekt"
                requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY; conditionTerm = B2bTerm.BT_12
            }

            val mandatoryWidget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_12, mandatory.toDto()) { it } as B2bFieldWidget.Text
            val conditionalWidget = B2bFieldWidgetFactory.createWidget(B2bTerm.BT_18, conditionalIfEmpty.toDto()) { it } as B2bFieldWidget.Text

            assertTrue(mandatoryWidget.field.isRequiredIndicatorVisible, "MANDATORY must show the required indicator")
            assertFalse(
                conditionalWidget.field.isRequiredIndicatorVisible,
                "documented gap EP3-F1: CONDITIONAL_IF_EMPTY never gets the required indicator, even though B2bProfileRules " +
                    "currently requires it while its trigger (BT-12) is empty — the field still renders, just without the live cue",
            )
        }

        @Test
        fun `finding EP6-F2 (fixed) - NUMBER kind has no FREE catalog term, but saveFieldValues now rejects a non-numeric value against any NUMBER term`() {
            assertTrue(
                B2bTerm.entries.none { it.fieldKind == B2bFieldKind.NUMBER && it.binding == B2bTermBinding.FREE },
                "sanity: no FREE NUMBER term exists in the catalog",
            )
            // BT-129 is BOUND (its own widget never renders), but a bypass via the service API
            // against any term now hits B2bFieldValue.validate()'s new NUMBER-kind check.
            whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE_ITEM, 2001L, B2bTerm.BT_129)).thenReturn(null)

            val ex = assertThrows(InvalidParameterException::class.java) {
                fieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE_ITEM, 2001L, mapOf("BT-129" to "not-a-number"))
            }

            assertTrue(ex.message!!.contains("BT-129"), ex.message)
            verify(fieldValueRepository, never()).save(any())
        }

        @Test
        fun `finding EP6-F3 (fixed) - saveFieldValues now applies the same ISO-8601 DATE format check the widget layer already had`() {
            whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 2002L, B2bTerm.BT_72)).thenReturn(null)

            val ex = assertThrows(InvalidParameterException::class.java) {
                fieldValueService.saveFieldValues(B2bFieldOwnerType.INVOICE, 2002L, mapOf("BT-72" to "not-iso-8601-at-all"))
            }

            assertTrue(ex.message!!.contains("BT-72"), ex.message)
            verify(fieldValueRepository, never()).save(any())
        }

        @Test
        fun `finding EP6-F4 - a LINE-level term requested at a non-INVOICE placement is silently coerced to INVOICE, not rejected`() {
            noSiblings()
            acceptSave()
            val field = B2bProfileField().apply { profile = freshProfile(); term = B2bTerm.BT_155; businessDefinition = "Verkäufer-Artikelnummer"; placement = B2bPlacement.RECIPIENT }

            val saved = service.saveField(field)

            assertEquals(
                B2bPlacement.INVOICE, saved.placement,
                "EP6-F4: the EP-4 spec wording groups this with configurations the domain 'rejects', but B2bProfileFieldDefinitionService.saveField silently " +
                    "coerces LINE-level terms to INVOICE instead of throwing",
            )
        }

        @Test
        fun `finding EP6-F5 (fixed) - a SELLER_PARTY-level BOUND term placed RECIPIENT is now rejected, not silently saved`() {
            // Wave F (2026-08-30 evening, PO framing) — SELLER_PARTY exists only for the seller's
            // own mandatory self-service fields; the domain used to accept any placement for it
            // and this exact combination saved silently while never surfacing on the customer form
            // (findRecipientFields' HEADER-only level filter). B2bProfileField
            // .assertPlacementCompatibleWithLevel now rejects it at save time instead — closing the
            // gap at the source rather than leaving an inert row in the profile.
            noSiblings()
            acceptSave()
            val field = B2bProfileField().apply { profile = freshProfile(); term = B2bTerm.BT_84; businessDefinition = "IBAN am Kunden?!"; placement = B2bPlacement.RECIPIENT }

            val ex = assertThrows(InvalidParameterException::class.java) { service.saveField(field) }

            assertTrue(ex.message!!.contains("SELLER_PARTY"), ex.message)
            verify(profileFieldRepository, never()).save(any())
        }
    }
}
