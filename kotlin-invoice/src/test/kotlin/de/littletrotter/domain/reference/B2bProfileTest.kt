package de.littletrotter.domain.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import de.littletrotter.test.FIXED_TODAY

/**
 * T1 — domain invariants of the B2B profile model.
 *
 * The former `B2bFieldCatalogEntry.validate()` invariant (FREE requires xpath, BOUND
 * requires boundProperty, blank code/spec key rejected) moved into [B2bTerm]'s own `init` block
 * (`assertB2bTermBindingInvariant`) and is proven exhaustively over all 35 real constants in
 * kotlin-shared's `B2bTermTest` — nothing to duplicate here. Fixtures below pick real
 * catalog-eligible [B2bTerm] constants instead of a hand-rolled catalog entry.
 */
class B2bProfileTest {

    private fun profileField(
        term: B2bTerm? = B2bTerm.BT_19,
        businessDefinition: String = "Interne Referenz",
        requirement: B2bFieldRequirement = B2bFieldRequirement.OPTIONAL,
        conditionTerm: B2bTerm? = null,
        placement: B2bPlacement = B2bPlacement.INVOICE,
    ): B2bProfileField = B2bProfileField().apply {
        this.term = term
        this.businessDefinition = businessDefinition
        this.requirement = requirement
        this.conditionTerm = conditionTerm
        this.placement = placement
    }

    // ── B2bProfile.validate() / assertUniqueTerms ───────────────────────────

    @Test
    fun `profile with blank name is rejected`() {
        val profile = B2bProfile().apply { name = "  " }
        assertThrows(InvalidParameterException::class.java) { profile.validate(today = FIXED_TODAY) }
    }

    @Test
    fun `profile with a name is accepted`() {
        val profile = B2bProfile().apply { name = "Kunde XY" }
        assertDoesNotThrow { profile.validate(today = FIXED_TODAY) }
    }

    @Test
    fun `duplicate term in the same profile is rejected`() {
        val fields = listOf(profileField(term = B2bTerm.BT_19), profileField(term = B2bTerm.BT_19))
        assertThrows(InvalidParameterException::class.java) { B2bProfile.assertUniqueTerms(fields) }
    }

    @Test
    fun `distinct terms in the same profile are accepted`() {
        val fields = listOf(profileField(term = B2bTerm.BT_19), profileField(term = B2bTerm.BT_56))
        assertDoesNotThrow { B2bProfile.assertUniqueTerms(fields) }
    }

    @Test
    fun `two fields both missing a term group under the null key and are rejected too`() {
        // validate() rejects a null term before this invariant matters in practice, but
        // assertUniqueTerms is a standalone entry point over an arbitrary candidate list (e.g.
        // before each field's own validate() has run), so `duplicated.first()?.officialCode`'s
        // null side is a real, reachable outcome — not a fictional one.
        val fields = listOf(profileField(term = null), profileField(term = null))
        assertThrows(InvalidParameterException::class.java) { B2bProfile.assertUniqueTerms(fields) }
    }

    // ── anbieter-einvoice-ergonomie §2 — B2bProfile.countries ────────────────

    @Test
    fun `an unscoped profile (empty countries) is valid`() {
        val profile = B2bProfile().apply { name = "Standard" }
        assertDoesNotThrow { profile.validate(today = FIXED_TODAY) }
    }

    @Test
    fun `a profile scoped to allowed countries is valid`() {
        val profile = B2bProfile().apply {
            name = "Factur-X FR/LU"
            countries = mutableSetOf(de.littletrotter.shared.country.Country.FR, de.littletrotter.shared.country.Country.LU)
        }
        assertDoesNotThrow { profile.validate(today = FIXED_TODAY) }
    }

    @Test
    fun `a profile scoped to Germany is valid - unlike additional VAT registrations, own scope may include DE`() {
        val profile = B2bProfile().apply { name = "Nur DE"; countries = mutableSetOf(de.littletrotter.shared.country.Country.DE) }
        assertDoesNotThrow { profile.validate(today = FIXED_TODAY) }
    }

    @Test
    fun `a profile scoped to a disallowed country is rejected`() {
        val profile = B2bProfile().apply { name = "Ungueltig"; countries = mutableSetOf(de.littletrotter.shared.country.Country.US) }
        assertThrows(InvalidParameterException::class.java) { profile.validate(today = FIXED_TODAY) }
    }

    // ── fix-einvoice-guided-profile-party-values (F3) — equals()/hashCode() ─────────────────
    // Hand-written JPA identity equality (not a data class), mirroring RecipientTest/
    // SenderProfileTest verbatim: the guided entry pane's b2bProfileBox preselects a
    // server-resolved B2bProfile instance that is never the same object as the one in the
    // ComboBox's own item list (a different persistence-context load) — without id-based equals,
    // Vaadin's default identity equality never finds the match.

    @Test
    fun `a profile is equal to itself`() {
        val profile = B2bProfile().apply { id = 1L; name = "Standard" }
        assertEquals(profile, profile)
    }

    @Test
    fun `a profile is never equal to an unrelated type`() {
        val profile = B2bProfile().apply { id = 1L; name = "Standard" }
        assertNotEquals(profile as Any, "not a profile" as Any)
    }

    @Test
    fun `two persisted profiles with the same id are equal even if other fields differ - the b2bProfileBox regression`() {
        val a = B2bProfile().apply { id = 5L; name = "Neckar" }
        val b = B2bProfile().apply { id = 5L; name = "Neckar (stale copy)"; active = false }
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `two persisted profiles with different ids are not equal`() {
        val a = B2bProfile().apply { id = 1L; name = "A" }
        val b = B2bProfile().apply { id = 2L; name = "A" }
        assertNotEquals(a, b)
    }

    @Test
    fun `two transient (unsaved) profiles are never equal, even with identical fields`() {
        assertNotEquals(B2bProfile().apply { name = "Standard" }, B2bProfile().apply { name = "Standard" })
    }

    @Test
    fun `a persisted and a transient profile are never equal`() {
        val persisted = B2bProfile().apply { id = 1L; name = "Standard" }
        val transient = B2bProfile().apply { name = "Standard" }
        assertNotEquals(persisted, transient)
    }

    @Test
    fun `hashCode does not derive from id and stays stable once a transient profile is persisted`() {
        val profile = B2bProfile().apply { name = "Standard" }
        val set = hashSetOf(profile)
        val hashBefore = profile.hashCode()

        profile.id = 7L // saving assigns the id

        assertEquals(hashBefore, profile.hashCode(), "hashCode must not change when the id is assigned")
        assertTrue(profile in set, "the entity must stay findable in a set it was already placed in")
    }

    // ── B2bProfileField.validate() ──────────────────────────────────────────

    @Test
    fun `field with blank own definition is rejected`() {
        val field = profileField(businessDefinition = "  ")
        assertThrows(InvalidParameterException::class.java) { field.validate() }
    }

    @Test
    fun `field with no term is rejected`() {
        val field = profileField(term = null)
        assertThrows(InvalidParameterException::class.java) { field.validate() }
    }

    @Test
    fun `conditional field without a condition term is rejected`() {
        val field = profileField(requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = null)
        assertThrows(InvalidParameterException::class.java) { field.validate() }
    }

    @Test
    fun `conditional field pointing at itself is rejected`() {
        val field = profileField(term = B2bTerm.BT_19, requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_19)
        assertThrows(InvalidParameterException::class.java) { field.validate() }
    }

    @Test
    fun `non-conditional field with a condition term is rejected`() {
        val field = profileField(requirement = B2bFieldRequirement.OPTIONAL, conditionTerm = B2bTerm.BT_56)
        assertThrows(InvalidParameterException::class.java) { field.validate() }
    }

    @Test
    fun `conditional field with a valid condition term is accepted`() {
        val field = profileField(term = B2bTerm.BT_19, requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_56)
        assertDoesNotThrow { field.validate() }
    }

    // ── B2bProfileField.assertPlacementCompatibleWithLevel ──────────────────

    @Test
    fun `a LINE term cannot be placed at the seller`() {
        assertThrows(InvalidParameterException::class.java) {
            B2bProfileField.assertPlacementCompatibleWithLevel(B2bPlacement.SELLER, de.littletrotter.shared.b2b.B2bTermLevel.LINE)
        }
    }

    @Test
    fun `a LINE term placed on the invoice is accepted`() {
        assertDoesNotThrow { B2bProfileField.assertPlacementCompatibleWithLevel(B2bPlacement.INVOICE, de.littletrotter.shared.b2b.B2bTermLevel.LINE) }
    }

    @Test
    fun `a BUYER_PARTY term cannot be placed at the seller`() {
        assertThrows(InvalidParameterException::class.java) {
            B2bProfileField.assertPlacementCompatibleWithLevel(B2bPlacement.SELLER, de.littletrotter.shared.b2b.B2bTermLevel.BUYER_PARTY)
        }
    }

    @Test
    fun `HEADER terms may use any placement`() {
        for (placement in B2bPlacement.entries) {
            assertDoesNotThrow { B2bProfileField.assertPlacementCompatibleWithLevel(placement, de.littletrotter.shared.b2b.B2bTermLevel.HEADER) }
        }
    }

    // SELLER_PARTY exists only for the seller's own mandatory self-service fields; it is not
    // free to choose any placement.
    @Test
    fun `a SELLER_PARTY term may only be placed at the seller`() {
        assertDoesNotThrow {
            B2bProfileField.assertPlacementCompatibleWithLevel(B2bPlacement.SELLER, de.littletrotter.shared.b2b.B2bTermLevel.SELLER_PARTY)
        }
        for (placement in listOf(B2bPlacement.INVOICE, B2bPlacement.RECIPIENT)) {
            assertThrows(InvalidParameterException::class.java) {
                B2bProfileField.assertPlacementCompatibleWithLevel(placement, de.littletrotter.shared.b2b.B2bTermLevel.SELLER_PARTY)
            }
        }
    }

    // ── B2bProfileField.assertValidConditionTarget ──────────────────────────

    @Test
    fun `condition target missing from the profile is rejected`() {
        val candidate = profileField(term = B2bTerm.BT_19, requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_56)
        assertThrows(InvalidParameterException::class.java) {
            B2bProfileField.assertValidConditionTarget(candidate, siblings = emptyList())
        }
    }

    @Test
    fun `condition target present in the profile is accepted`() {
        val trigger = profileField(term = B2bTerm.BT_56)
        val candidate = profileField(term = B2bTerm.BT_19, requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_56)
        assertDoesNotThrow {
            B2bProfileField.assertValidConditionTarget(candidate, siblings = listOf(trigger, candidate))
        }
    }

    @Test
    fun `two-node conditional cycle is rejected`() {
        // A depends on B, B depends on A.
        val a = profileField(term = B2bTerm.BT_19, requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_56)
        val b = profileField(term = B2bTerm.BT_56, requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_19)
        assertThrows(InvalidParameterException::class.java) {
            B2bProfileField.assertValidConditionTarget(a, siblings = listOf(a, b))
        }
    }

    @Test
    fun `three-node conditional cycle is rejected`() {
        // A depends on B, B depends on C, C depends on A.
        val a = profileField(term = B2bTerm.BT_19, requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_56)
        val b = profileField(term = B2bTerm.BT_56, requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_58)
        val c = profileField(term = B2bTerm.BT_58, requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_19)
        assertThrows(InvalidParameterException::class.java) {
            B2bProfileField.assertValidConditionTarget(a, siblings = listOf(a, b, c))
        }
    }

    @Test
    fun `a conditional chain that does not loop back is accepted`() {
        // A depends on B, B depends on C (not conditional itself, so the chain simply ends).
        val a = profileField(term = B2bTerm.BT_19, requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_56)
        val b = profileField(term = B2bTerm.BT_56, requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_58)
        val c = profileField(term = B2bTerm.BT_58)
        assertDoesNotThrow {
            B2bProfileField.assertValidConditionTarget(a, siblings = listOf(a, b, c))
        }
    }

    @Test
    fun `a candidate without a term is left to validate, not resolved here`() {
        // validate() already rejects term == null before this invariant matters in practice, but
        // assertValidConditionTarget is a standalone public entry point and still guards it —
        // mirrors "a conditional field without a trigger is left to validate, not resolved here"
        // just below for the conditionTerm side of the same function.
        val candidate = profileField(term = null, requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_56)
        assertDoesNotThrow {
            B2bProfileField.assertValidConditionTarget(candidate, siblings = listOf(candidate))
        }
    }

    // ── T7 — the edges of the same invariants ────────────────────

    @Test
    fun `a field value without an owner id is rejected`() {
        assertThrows(InvalidParameterException::class.java) { fieldValue(ownerId = 0L).validate() }
    }

    @Test
    fun `a field value without a business term is rejected`() {
        assertThrows(InvalidParameterException::class.java) { fieldValue(term = null).validate() }
    }

    @Test
    fun `a field value with owner and term is accepted`() {
        assertDoesNotThrow { fieldValue().validate() }
    }

    private fun fieldValue(ownerId: Long = 1L, term: B2bTerm? = B2bTerm.BT_19) = B2bFieldValue().apply {
        this.ownerType = B2bFieldOwnerType.INVOICE
        this.ownerId = ownerId
        this.term = term
        this.value = "KST-4711"
    }

    // ── B2bProfileField.validate() / assertValidConditionTarget() ───────────

    @Test
    fun `a conditional field without a trigger is left to validate, not resolved here`() {
        // `assertValidConditionTarget` checks the target, not its presence -- that is
        // `validate()`'s job. Without this case the order of the two checks would be
        // arbitrary, and a caller that only calls this one would get an NPE instead of a
        // domain-level error message.
        val orphan = profileField(requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = null)
        assertDoesNotThrow { B2bProfileField.assertValidConditionTarget(orphan, siblings = listOf(orphan)) }
    }

    @Test
    fun `a chain that leaves the profile ends there instead of failing the candidate`() {
        // A depends on B, B on C -- and C is not part of the profile at all. For A that is not a
        // cycle: the traversal ends at the gap. That *B* names an invalid target is B's own
        // finding, reported by B's own check, not A's.
        val a = profileField(term = B2bTerm.BT_19, requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_56)
        val b = profileField(term = B2bTerm.BT_56, requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_58)

        assertDoesNotThrow { B2bProfileField.assertValidConditionTarget(a, siblings = listOf(a, b)) }
        assertThrows(InvalidParameterException::class.java) {
            B2bProfileField.assertValidConditionTarget(b, siblings = listOf(a, b))
        }
    }

    // ── B2bProfileField.assertDefaultValueCompatibleWithTerm ─────

    private fun profileFieldWithDefault(default: String?, term: B2bTerm? = B2bTerm.BT_58) =
        profileField(term = term).apply { defaultValue = default }

    @Test
    fun `no default value is always accepted, regardless of the term`() {
        assertDoesNotThrow {
            B2bProfileField.assertDefaultValueCompatibleWithTerm(profileFieldWithDefault(null, B2bTerm.BT_84), B2bTerm.BT_84)
        }
    }

    @Test
    fun `a blank default value is treated as absent, just like null`() {
        assertDoesNotThrow {
            B2bProfileField.assertDefaultValueCompatibleWithTerm(profileFieldWithDefault("   ", B2bTerm.BT_84), B2bTerm.BT_84)
        }
    }

    @Test
    fun `a default value on a BOUND term is rejected`() {
        // BT-84 (SELLER_PARTY, BOUND, boundProperty = iban) -- not DATE-kind, so this exercises
        // the BOUND branch specifically, distinct from the DATE branch below.
        assertThrows(InvalidParameterException::class.java) {
            B2bProfileField.assertDefaultValueCompatibleWithTerm(
                profileFieldWithDefault("DE02120300000000202051", B2bTerm.BT_84),
                B2bTerm.BT_84,
            )
        }
    }

    @Test
    fun `a default value on a FREE term is accepted`() {
        assertDoesNotThrow {
            B2bProfileField.assertDefaultValueCompatibleWithTerm(
                profileFieldWithDefault("rechnung@nekar-supermarkt.de", B2bTerm.BT_58),
                B2bTerm.BT_58,
            )
        }
    }

    @Test
    fun `a default value on a DATE-kind term is rejected - v1 decision`() {
        // BT-72 is DATE and (today) also BOUND -- checked before BOUND precisely so a real DATE
        // catalog term still exercises this branch (see B2bProfileField.kt's ordering comment).
        assertThrows(InvalidParameterException::class.java) {
            B2bProfileField.assertDefaultValueCompatibleWithTerm(profileFieldWithDefault("2026-01-01", B2bTerm.BT_72), B2bTerm.BT_72)
        }
    }

    @Test
    fun `a default value within the term's max length is accepted`() {
        // BT-58 maxLength = 100.
        assertDoesNotThrow {
            B2bProfileField.assertDefaultValueCompatibleWithTerm(
                profileFieldWithDefault("rechnung@nekar-supermarkt.de", B2bTerm.BT_58),
                B2bTerm.BT_58,
            )
        }
    }

    @Test
    fun `a default value exceeding the term's max length is rejected`() {
        // BT-19 maxLength = 50.
        assertThrows(InvalidParameterException::class.java) {
            B2bProfileField.assertDefaultValueCompatibleWithTerm(
                profileFieldWithDefault("x".repeat(51), B2bTerm.BT_19),
                B2bTerm.BT_19,
            )
        }
    }

    @Test
    fun `a term with no configured max length skips the length check entirely`() {
        // BT-16 is a reference-only term (maxLength = null, the constructor's default) -- the
        // function itself does not require a catalog-eligible term, so this directly exercises
        // the null side of `term.maxLength?.let` without needing a fictional B2bTerm. Every other
        // former reference term with a non-DATE fieldKind moved to BOUND (whose own branch
        // above rejects a default outright), leaving BT-16 as the one still-reachable fixture.
        assertDoesNotThrow {
            B2bProfileField.assertDefaultValueCompatibleWithTerm(
                profileFieldWithDefault("x".repeat(500), B2bTerm.BT_16),
                B2bTerm.BT_16,
            )
        }
    }

    @Test
    fun `a cycle among other terms does not implicate the candidate`() {
        // A depends on B; B and C form a cycle between themselves that never reaches A. That is
        // a defect of the profile, but not of *this* field -- the check bails out instead of
        // wrongly accusing A of a cycle.
        val a = profileField(term = B2bTerm.BT_19, requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_56)
        val b = profileField(term = B2bTerm.BT_56, requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_58)
        val c = profileField(term = B2bTerm.BT_58, requirement = B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_56)

        assertDoesNotThrow { B2bProfileField.assertValidConditionTarget(a, siblings = listOf(a, b, c)) }
    }

    // ── B2bFieldRequirement.CONDITIONAL_IF_EMPTY ─────────────────
    //
    // Same self-contained invariants as CONDITIONAL (validate()), but the mutual XOR pair — the
    // whole point of this requirement kind — must be *allowed* where CONDITIONAL's cycle check
    // would reject it.

    @Test
    fun `if-empty field without a condition term is rejected`() {
        val field = profileField(requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY, conditionTerm = null)
        assertThrows(InvalidParameterException::class.java) { field.validate() }
    }

    @Test
    fun `if-empty field pointing at itself is rejected`() {
        val field = profileField(term = B2bTerm.BT_72, requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY, conditionTerm = B2bTerm.BT_72)
        assertThrows(InvalidParameterException::class.java) { field.validate() }
    }

    @Test
    fun `non-conditional field with a condition term is rejected regardless of which conditional kind it resembles`() {
        val field = profileField(requirement = B2bFieldRequirement.MANDATORY, conditionTerm = B2bTerm.BT_134)
        assertThrows(InvalidParameterException::class.java) { field.validate() }
    }

    @Test
    fun `if-empty field with a valid condition term is accepted`() {
        val field = profileField(term = B2bTerm.BT_72, requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY, conditionTerm = B2bTerm.BT_134)
        assertDoesNotThrow { field.validate() }
    }

    @Test
    fun `if-empty condition target missing from the profile is rejected`() {
        val candidate = profileField(term = B2bTerm.BT_72, requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY, conditionTerm = B2bTerm.BT_134)
        assertThrows(InvalidParameterException::class.java) {
            B2bProfileField.assertValidConditionTarget(candidate, siblings = emptyList())
        }
    }

    @Test
    fun `if-empty condition target present in the profile is accepted`() {
        val trigger = profileField(term = B2bTerm.BT_134)
        val candidate = profileField(term = B2bTerm.BT_72, requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY, conditionTerm = B2bTerm.BT_134)
        assertDoesNotThrow {
            B2bProfileField.assertValidConditionTarget(candidate, siblings = listOf(trigger, candidate))
        }
    }

    @Test
    fun `a mutual if-empty pair (the XOR case) is explicitly allowed, unlike CONDITIONAL's mutual pair`() {
        // BT-72 required iff BT-134 empty; BT-134 required iff BT-72 empty — exactly the either-or
        // pairing this requirement kind exists for. CONDITIONAL's own cycle check
        // would reject the positive-trigger equivalent of this (`two-node conditional cycle is
        // rejected`, above); CONDITIONAL_IF_EMPTY must not.
        val bt72 = profileField(term = B2bTerm.BT_72, requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY, conditionTerm = B2bTerm.BT_134)
        val bt134 = profileField(term = B2bTerm.BT_134, requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY, conditionTerm = B2bTerm.BT_72)

        assertDoesNotThrow { B2bProfileField.assertValidConditionTarget(bt72, siblings = listOf(bt72, bt134)) }
        assertDoesNotThrow { B2bProfileField.assertValidConditionTarget(bt134, siblings = listOf(bt72, bt134)) }
    }

    @Test
    fun `a three-node if-empty cycle is rejected - only the 2-node XOR pair stays legal`() {
        // Cycles of length >= 3 are rejected exactly like CONDITIONAL cycles, while the direct
        // mutual pair above remains the sanctioned XOR pattern.
        val a = profileField(term = B2bTerm.BT_19, requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY, conditionTerm = B2bTerm.BT_56)
        val b = profileField(term = B2bTerm.BT_56, requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY, conditionTerm = B2bTerm.BT_58)
        val c = profileField(term = B2bTerm.BT_58, requirement = B2bFieldRequirement.CONDITIONAL_IF_EMPTY, conditionTerm = B2bTerm.BT_19)

        val ex = assertThrows(de.littletrotter.domain.exception.InvalidParameterException::class.java) {
            B2bProfileField.assertValidConditionTarget(a, siblings = listOf(a, b, c))
        }
        org.junit.jupiter.api.Assertions.assertTrue(ex.message!!.contains("Zyklische bedingte Pflicht"))
    }
}
