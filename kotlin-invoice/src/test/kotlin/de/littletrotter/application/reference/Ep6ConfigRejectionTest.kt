package de.littletrotter.application.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.repository.B2bProfileFieldRepository
import de.littletrotter.domain.repository.B2bProfileRepository
import de.littletrotter.domain.repository.InvoiceRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * A dedicated class gathering every field-combination the domain rejects
 * (or silently coerces) at configuration time in one place, organized by which axis
 * pair drives the rejection. [Ep6FieldCombinationMatrixTest] only ever claims the combinations
 * this class does NOT reject; every row appearing there has a matching "would be rejected if you
 * changed X" counterpart proven here.
 *
 * Some individual assertions here already exist scattered across [B2bProfileServicesTest] — kept
 * anyway, so a reviewer can read this class as "the full rejection surface" without reassembling
 * it from several files.
 */
class Ep6ConfigRejectionTest {

    private val profileRepository = mock<B2bProfileRepository>()
    private val profileFieldRepository = mock<B2bProfileFieldRepository>()
    private val recipientRepository = mock<RecipientRepository>()
    private val invoiceRepository = mock<InvoiceRepository>()
    private val service = B2bProfileFieldDefinitionService(
        profileRepository, profileFieldRepository, recipientRepository, invoiceRepository,
    )

    private fun freshProfile() = B2bProfile().apply { id = 1L; name = "EP-6 rejection" }

    private fun noSiblings() {
        whenever(profileFieldRepository.findByProfileId(1L)).thenReturn(emptyList())
    }

    // ─── requirement x binding=null (reference terms) ─────────────────────────────────────────────

    @Nested
    inner class ReferenceTermRequirement {

        @Test
        fun `MANDATORY on a reference term (binding null) is rejected`() {
            noSiblings()
            val field = B2bProfileField().apply { profile = freshProfile(); term = B2bTerm.BT_16; businessDefinition = "x"; requirement = B2bFieldRequirement.MANDATORY }
            val ex = assertThrows(InvalidParameterException::class.java) { service.saveField(field) }
            assertEquals(true, ex.message?.contains("BT-16"))
            verify(profileFieldRepository, never()).save(any())
        }

        @Test
        fun `CONDITIONAL on a reference term (binding null) is rejected`() {
            val trigger = B2bProfileField().apply { id = 1L; profile = freshProfile(); term = B2bTerm.BT_19; businessDefinition = "trig" }
            whenever(profileFieldRepository.findByProfileId(1L)).thenReturn(listOf(trigger))
            val field = B2bProfileField().apply {
                profile = freshProfile(); term = B2bTerm.BT_16; businessDefinition = "x"
                requirement = B2bFieldRequirement.CONDITIONAL; conditionTerm = B2bTerm.BT_19
            }
            assertThrows(InvalidParameterException::class.java) { service.saveField(field) }
        }

        @Test
        fun `CONDITIONAL_IF_EMPTY on a reference term (binding null) is rejected`() {
            val trigger = B2bProfileField().apply { id = 1L; profile = freshProfile(); term = B2bTerm.BT_19; businessDefinition = "trig" }
            whenever(profileFieldRepository.findByProfileId(1L)).thenReturn(listOf(trigger))
            val field = B2bProfileField().apply {
                profile = freshProfile(); term = B2bTerm.BT_16; businessDefinition = "x"
                requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY; conditionTerm = B2bTerm.BT_19
            }
            assertThrows(InvalidParameterException::class.java) { service.saveField(field) }
        }

        @Test
        fun `OPTIONAL on a reference term (binding null) is accepted - the only legal requirement for it`() {
            noSiblings()
            whenever(profileFieldRepository.save(any())).thenAnswer { it.arguments[0] }
            val field = B2bProfileField().apply { profile = freshProfile(); term = B2bTerm.BT_16; businessDefinition = "x"; requirement = B2bFieldRequirement.OPTIONAL }
            assertEquals(B2bTerm.BT_16, service.saveField(field).term)
        }
    }

    // ─── catalogExcluded (BT-151 — T3 point 5's tax-determination guard) ──────────────────────────

    @Nested
    inner class CatalogExcluded {

        @Test
        fun `a catalogExcluded term (BT-151) is rejected regardless of requirement`() {
            noSiblings()
            val field = B2bProfileField().apply { profile = freshProfile(); term = B2bTerm.BT_151; businessDefinition = "x"; requirement = B2bFieldRequirement.OPTIONAL }
            val ex = assertThrows(InvalidParameterException::class.java) { service.saveField(field) }
            assertEquals(true, ex.message?.contains("BT-151"))
        }
    }

    // ─── level x placement ─────────────────────────────────────────────────────────────────────────

    @Nested
    inner class LevelPlacementLegality {

        @Test
        fun `BUYER_PARTY level with RECIPIENT placement is rejected`() {
            noSiblings()
            val field = B2bProfileField().apply { profile = freshProfile(); term = B2bTerm.BT_44; businessDefinition = "x"; placement = B2bPlacement.RECIPIENT }
            assertThrows(InvalidParameterException::class.java) { service.saveField(field) }
        }

        @Test
        fun `BUYER_PARTY level with SELLER placement is rejected`() {
            noSiblings()
            val field = B2bProfileField().apply { profile = freshProfile(); term = B2bTerm.BT_44; businessDefinition = "x"; placement = B2bPlacement.SELLER }
            assertThrows(InvalidParameterException::class.java) { service.saveField(field) }
        }

        @Test
        fun `BUYER_PARTY level with INVOICE placement is accepted - the control case`() {
            noSiblings()
            whenever(profileFieldRepository.save(any())).thenAnswer { it.arguments[0] }
            val field = B2bProfileField().apply { profile = freshProfile(); term = B2bTerm.BT_44; businessDefinition = "x"; placement = B2bPlacement.INVOICE }
            assertEquals(B2bPlacement.INVOICE, service.saveField(field).placement)
        }

        @Test
        fun `SELLER_PARTY level with INVOICE placement is rejected - EP6-F5 closed`() {
            noSiblings()
            val field = B2bProfileField().apply { profile = freshProfile(); term = B2bTerm.BT_84; businessDefinition = "x"; placement = B2bPlacement.INVOICE }
            assertThrows(InvalidParameterException::class.java) { service.saveField(field) }
        }

        @Test
        fun `SELLER_PARTY level with RECIPIENT placement is rejected - EP6-F5 closed`() {
            // A SELLER_PARTY-level BOUND term placed RECIPIENT is rejected at the source instead
            // of saving silently and never rendering. See Ep4BorderCasesTest's flipped pin for
            // the same case.
            noSiblings()
            val field = B2bProfileField().apply { profile = freshProfile(); term = B2bTerm.BT_84; businessDefinition = "x"; placement = B2bPlacement.RECIPIENT }
            val ex = assertThrows(InvalidParameterException::class.java) { service.saveField(field) }
            assertEquals(true, ex.message?.contains("SELLER_PARTY"))
            verify(profileFieldRepository, never()).save(any())
        }

        @Test
        fun `SELLER_PARTY level with SELLER placement is accepted - the control case`() {
            noSiblings()
            whenever(profileFieldRepository.save(any())).thenAnswer { it.arguments[0] }
            val field = B2bProfileField().apply { profile = freshProfile(); term = B2bTerm.BT_84; businessDefinition = "x"; placement = B2bPlacement.SELLER }
            assertEquals(B2bPlacement.SELLER, service.saveField(field).placement)
        }

        @Test
        fun `LINE level with SELLER placement requested is silently coerced to INVOICE, not rejected - a documented gap between spec wording and behavior`() {
            // This case groups with "combinations the domain REJECTS at configuration time" in
            // wording, but the actual production behavior (B2bProfileFieldDefinitionService
            // .saveField, "term.level == LINE -> field.placement = B2bPlacement.INVOICE") is a
            // silent COERCION, not a thrown exception.
            noSiblings()
            whenever(profileFieldRepository.save(any())).thenAnswer { it.arguments[0] }
            val field = B2bProfileField().apply { profile = freshProfile(); term = B2bTerm.BT_155; businessDefinition = "x"; placement = B2bPlacement.SELLER }
            assertEquals(B2bPlacement.INVOICE, service.saveField(field).placement)
        }

        @Test
        fun `LINE level with RECIPIENT placement requested is also coerced to INVOICE`() {
            noSiblings()
            whenever(profileFieldRepository.save(any())).thenAnswer { it.arguments[0] }
            val field = B2bProfileField().apply { profile = freshProfile(); term = B2bTerm.BT_156; businessDefinition = "x"; placement = B2bPlacement.RECIPIENT }
            assertEquals(B2bPlacement.INVOICE, service.saveField(field).placement)
        }
    }

    // ─── default value compatibility ──────────────────────────────────────────────────

    @Nested
    inner class DefaultValueCompatibility {

        @Test
        fun `a default value on a DATE-kind term is rejected`() {
            noSiblings()
            val field = B2bProfileField().apply { profile = freshProfile(); term = B2bTerm.BT_72; businessDefinition = "x"; defaultValue = "2026-01-01" }
            assertThrows(InvalidParameterException::class.java) { service.saveField(field) }
        }

        @Test
        fun `a default value on a BOUND term is rejected`() {
            noSiblings()
            val field = B2bProfileField().apply { profile = freshProfile(); term = B2bTerm.BT_84; businessDefinition = "x"; defaultValue = "DE02120300000000202051" }
            assertThrows(InvalidParameterException::class.java) { service.saveField(field) }
        }

        @Test
        fun `a default value exceeding the term's maxLength is rejected`() {
            noSiblings()
            val field = B2bProfileField().apply { profile = freshProfile(); term = B2bTerm.BT_19; businessDefinition = "x"; defaultValue = "x".repeat(51) }
            assertThrows(InvalidParameterException::class.java) { service.saveField(field) }
        }

        @Test
        fun `a default value on a FREE non-DATE term within maxLength is accepted - the control case`() {
            noSiblings()
            whenever(profileFieldRepository.save(any())).thenAnswer { it.arguments[0] }
            val field = B2bProfileField().apply { profile = freshProfile(); term = B2bTerm.BT_19; businessDefinition = "x"; defaultValue = "KST-001" }
            assertEquals("KST-001", service.saveField(field).defaultValue)
        }
    }

    // ─── conditional-requirement wiring (B2bProfileField.validate / assertValidConditionTarget) ───

    @Nested
    inner class ConditionalWiring {

        @Test
        fun `CONDITIONAL with no conditionTerm at all is rejected`() {
            noSiblings()
            val field = B2bProfileField().apply { profile = freshProfile(); term = B2bTerm.BT_19; businessDefinition = "x"; requirement = B2bFieldRequirement.CONDITIONAL }
            assertThrows(InvalidParameterException::class.java) { service.saveField(field) }
        }

        @Test
        fun `CONDITIONAL_IF_EMPTY whose conditionTerm is the field itself is rejected`() {
            noSiblings()
            val field = B2bProfileField().apply {
                profile = freshProfile(); term = B2bTerm.BT_19; businessDefinition = "x"
                requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY; conditionTerm = B2bTerm.BT_19
            }
            assertThrows(InvalidParameterException::class.java) { service.saveField(field) }
        }

        @Test
        fun `CONDITIONAL whose conditionTerm is not a sibling of the same profile is rejected`() {
            noSiblings()
            val field = B2bProfileField().apply {
                profile = freshProfile(); term = B2bTerm.BT_19; businessDefinition = "x"
                requirement = B2bFieldRequirement.CONDITIONAL; conditionTerm = B2bTerm.BT_12
            }
            assertThrows(InvalidParameterException::class.java) { service.saveField(field) }
        }

        @Test
        fun `a cyclic CONDITIONAL trigger chain is rejected`() {
            val profile = freshProfile()
            val a = B2bProfileField().apply { id = 1L; this.profile = profile; term = B2bTerm.BT_12; businessDefinition = "A"; requirement = B2bFieldRequirement.CONDITIONAL; conditionTerm = B2bTerm.BT_18 }
            whenever(profileFieldRepository.findByProfileId(1L)).thenReturn(listOf(a))
            val b = B2bProfileField().apply {
                this.profile = profile; term = B2bTerm.BT_18; businessDefinition = "B"
                requirement = B2bFieldRequirement.CONDITIONAL; conditionTerm = B2bTerm.BT_12
            }
            assertThrows(InvalidParameterException::class.java) { service.saveField(b) }
        }

        @Test
        fun `a mutual CONDITIONAL_IF_EMPTY XOR pair is explicitly allowed - not treated as a cycle`() {
            val profile = freshProfile()
            val a = B2bProfileField().apply { id = 1L; this.profile = profile; term = B2bTerm.BT_72; businessDefinition = "A"; requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY; conditionTerm = B2bTerm.BT_134 }
            whenever(profileFieldRepository.findByProfileId(1L)).thenReturn(listOf(a))
            whenever(profileFieldRepository.save(any())).thenAnswer { it.arguments[0] }
            val b = B2bProfileField().apply {
                this.profile = profile; term = B2bTerm.BT_134; businessDefinition = "B"
                requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY; conditionTerm = B2bTerm.BT_72
            }
            assertEquals(B2bTerm.BT_72, service.saveField(b).conditionTerm)
        }
    }

    // ─── The BT-19 (HEADER) / BT-133 (LINE) cross-level XOR pin ───────────────────────

    /**
     * A cross-level pair (a HEADER term paired with a LINE term) had never been explicitly
     * tested: the mechanism is level-aware, but the cross-level case itself was unpinned.
     *
     * **Confirmed defect:** the BT-19/BT-133 pair **cannot actually be
     * configured as a working mutual XOR**, for a reason neither [Ep4BorderCasesTest]'s nor this
     * class's existing `CONDITIONAL_IF_EMPTY` coverage (BT-72/BT-134, BT-19/BT-56/BT-58 — all
     * [de.littletrotter.shared.b2b.B2bTermBinding.BOUND] or [de.littletrotter.shared.b2b
     * .B2bTermBinding.FREE] on both sides) ever exercises: **[B2bTerm.BT_133] is a pure *reference*
     * term** (`binding == null` — the "BT-133 line fanout" [de.littletrotter.xpdf.impl
     * .Bt19HeaderInjector] writes is a post-processing *duplication* of the captured BT-19 value
     * onto every line, never an independently captured BT-133 value). Two independent breakages
     * follow:
     * 1. Direction LINE→HEADER (BT-133 itself as `CONDITIONAL_IF_EMPTY`, triggered by BT-19) is
     *    flatly **rejected at config time** — `B2bProfileFieldDefinitionService.saveField`'s
     *    reference-term guard (`term.binding == null && requirement != OPTIONAL`) fires before the
     *    level is ever considered. Proven below, mirroring the existing generic
     *    `CONDITIONAL_IF_EMPTY on a reference term (binding null) is rejected` test above, this time
     *    naming BT-133/BT-19 specifically.
     * 2. Direction HEADER→LINE (BT-19 as `CONDITIONAL_IF_EMPTY`, triggered by BT-133) **is accepted**
     *    at config time — `assertValidConditionTarget`'s cycle walk never reaches the "mutual pair"
     *    exemption because BT-133 can only ever be `OPTIONAL` (a different `requirement` than the
     *    candidate's, so the chain walk stops after one hop without detecting a cycle either way) —
     *    but the accepted config **misevaluates** at runtime: [de.littletrotter.application.invoice
     *    .B2bProfileRuleSet.hasValue] branches on `term.binding` *before* ever touching the
     *    `b2b_field_value` table, so a reference term's `hasValue` is `false` unconditionally,
     *    regardless of what (if anything) is stored for it. BT-133 can therefore never be read as
     *    "filled", so the "if-empty" condition is permanently true and BT-19 ends up **always**
     *    required — not an XOR with the line side, a disguised MANDATORY. Pinned in
     *    [B2bProfileRuleSetTest] ("BT-19/BT-133 cross-level XOR degenerates to MANDATORY").
     *
     * [B2bTerm.BT_133] gaining a real capture path (an independent per-line value, not a BT-19
     * mirror) would be a catalog/export decision, not a test-writing one. The pragmatic
     * config-time consequence is: use plain `MANDATORY` on BT-19 instead of a
     * `CONDITIONAL_IF_EMPTY` pair with BT-133.
     */
    @Nested
    inner class NekarBt19Bt133CrossLevelXorPin {

        @Test
        fun `D-P1-F(1) - BT-133 (LINE) as CONDITIONAL_IF_EMPTY triggered by BT-19 (HEADER) is rejected at config time`() {
            val trigger = B2bProfileField().apply { id = 1L; profile = freshProfile(); term = B2bTerm.BT_19; businessDefinition = "Kostenstelle" }
            whenever(profileFieldRepository.findByProfileId(1L)).thenReturn(listOf(trigger))
            val bt133AsLineField = B2bProfileField().apply {
                profile = freshProfile(); term = B2bTerm.BT_133; businessDefinition = "Kostenstelle je Position"
                requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY; conditionTerm = B2bTerm.BT_19
            }
            val ex = assertThrows(InvalidParameterException::class.java) { service.saveField(bt133AsLineField) }
            assertEquals(true, ex.message?.contains("BT-133"), "the rejection must name the actual rejected term: ${ex.message}")
        }

        @Test
        fun `D-P1-F(2) - BT-19 (HEADER) as CONDITIONAL_IF_EMPTY triggered by BT-133 (LINE) is ACCEPTED, unlike the reverse direction`() {
            // BT-133 first, saved OPTIONAL (its only legal requirement) so it exists as a sibling
            // `assertValidConditionTarget` can resolve BT-19's conditionTerm against.
            val profile = freshProfile()
            whenever(profileFieldRepository.findByProfileId(1L)).thenReturn(emptyList())
            whenever(profileFieldRepository.save(any())).thenAnswer { it.arguments[0] }
            val bt133Optional = B2bProfileField().apply {
                id = 5L; this.profile = profile; term = B2bTerm.BT_133; businessDefinition = "Kostenstelle je Position"; requirement = B2bFieldRequirement.OPTIONAL
            }
            service.saveField(bt133Optional)

            whenever(profileFieldRepository.findByProfileId(1L)).thenReturn(listOf(bt133Optional))
            val bt19ConditionalIfEmpty = B2bProfileField().apply {
                this.profile = profile; term = B2bTerm.BT_19; businessDefinition = "Kostenstelle"
                requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY; conditionTerm = B2bTerm.BT_133
            }
            // No throw — the config layer has no way to know BT-133's `hasValue` can never become
            // true (see B2bProfileRuleSetTest's runtime pin for the actual consequence).
            assertEquals(B2bTerm.BT_133, service.saveField(bt19ConditionalIfEmpty).conditionTerm)
        }
    }

    // ─── duplicate term within one profile ───────────────────────────────────────────────────────

    @Nested
    inner class UniqueTerms {

        @Test
        fun `the same term twice in one profile is rejected`() {
            val profile = freshProfile()
            val existing = B2bProfileField().apply { id = 10L; this.profile = profile; term = B2bTerm.BT_19; businessDefinition = "erstes" }
            whenever(profileFieldRepository.findByProfileId(1L)).thenReturn(listOf(existing))
            val duplicate = B2bProfileField().apply { this.profile = profile; term = B2bTerm.BT_19; businessDefinition = "zweites" }
            assertThrows(InvalidParameterException::class.java) { service.saveField(duplicate) }
        }
    }

    // ─── profile/term presence guards ────────────────────────────────────────────────────────────

    @Nested
    inner class PresenceGuards {

        @Test
        fun `a field with no profile at all is rejected`() {
            val field = B2bProfileField().apply { term = B2bTerm.BT_19; businessDefinition = "x" }
            assertThrows(InvalidParameterException::class.java) { service.saveField(field) }
        }

        @Test
        fun `a field for an unpersisted profile is rejected`() {
            val field = B2bProfileField().apply { profile = B2bProfile(); term = B2bTerm.BT_19; businessDefinition = "x" }
            assertThrows(InvalidParameterException::class.java) { service.saveField(field) }
        }

        @Test
        fun `a field with no term at all is rejected`() {
            noSiblings()
            val field = B2bProfileField().apply { profile = freshProfile(); businessDefinition = "x" }
            assertThrows(InvalidParameterException::class.java) { service.saveField(field) }
        }
    }
}
