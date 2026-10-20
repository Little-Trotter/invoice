package de.littletrotter.application.reference

import de.littletrotter.application.reference.port.outbound.RecipientFieldValueReadRepository
import de.littletrotter.fixture.givenFields
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bFieldValue
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.application.reference.port.outbound.B2bFieldValueStore
import de.littletrotter.domain.repository.B2bProfileRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.shared.b2b.B2bTerm
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.test.FIXED_TIME_SOURCE

/**
 * [B2bProfileLifecycleService]/[B2bProfileFieldDefinitionService]/[B2bFieldValueService]/
 * [B2bProfileUsageService] orchestration: term uniqueness, LINE-forces-INVOICE placement, the
 * "profile must exist first" guard, seller-field mandatory checks and the recipient-assignment
 * delete-guard (moved off the seller, decision 12).
 *
 * No catalog repository/mock: [B2bProfileFieldDefinitionService.findCatalog] and
 * [B2bProfileFieldDefinitionService.findSelectableCatalogEntries] read [B2bTerm.entries] directly. Fixtures pick
 * real catalog-eligible [B2bTerm] constants spanning HEADER/SELLER_PARTY/LINE/BUYER_PARTY, with
 * exactly the level/binding/boundProperty/maxLength the old hand-rolled `catalogEntry()` fixtures
 * used to fake, instead of synthesizing arbitrary combinations; a "stale field whose catalog term
 * no longer exists" is now simply `term == null` (see [B2bTermConverter]'s null-safe read).
 */
class B2bProfileServicesTest {

    private val profileRepository = mock<B2bProfileRepository>()
    private val fieldValueRepository = mock<B2bFieldValueStore>()
    private val recipientRepository = mock<RecipientRepository>()
    private val recipientFieldValues = mock<RecipientFieldValueReadRepository>()
    private val invoiceRepository = mock<de.littletrotter.domain.repository.InvoiceRepository>()
    private val invoiceLookup = mock<de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository>()
    private val fieldDefinitionService = B2bProfileFieldDefinitionService(
        profileRepository, recipientRepository, invoiceLookup,
    )
    private val lifecycleService = B2bProfileLifecycleService(
        profileRepository, recipientRepository, invoiceLookup, time = FIXED_TIME_SOURCE,
    )
    private val fieldValueService = B2bFieldValueService(
        fieldValueRepository, recipientRepository, recipientFieldValues, invoiceRepository, fieldDefinitionService,
        B2bPartyFieldValues(fieldValueRepository, recipientFieldValues),
    )
    private val usageService = B2bProfileUsageService(recipientRepository)

    /** A stored recipient 5 holding [values] (profile field id to value), as the repository hands it out. */
    private fun storedRecipient(vararg values: Pair<Long, String>): Recipient =
        aRecipient { id = 5L }.also { recipient ->
            values.forEach { (fieldId, value) -> recipient.recordB2bFieldValue(fieldId, value) }
            whenever(recipientRepository.findById(5L)).thenReturn(recipient)
        }

    private fun profileField(
        term: B2bTerm?,
        placement: B2bPlacement,
        requirement: B2bFieldRequirement = B2bFieldRequirement.OPTIONAL,
        conditionTerm: B2bTerm? = null,
    ): B2bProfileField = B2bProfileField().edit {
        this.term = term
        this.placement = placement
        this.requirement = requirement
        this.conditionTerm = conditionTerm
        this.businessDefinition = "Definition ${term?.officialCode}"
    }

    @Test
    fun `saving a field for an unpersisted profile is rejected`() {
        val field = B2bProfileField().edit {
            profile = B2bProfile() // id == null
            term = B2bTerm.BT_19
            businessDefinition = "Def"
        }
        assertThrows(InvalidParameterException::class.java) { fieldDefinitionService.saveField(field) }
    }

    @Test
    fun `saving a field with no profile at all is rejected`() {
        val field = B2bProfileField().edit {
            // profile left null entirely -- distinct from the "unpersisted profile" case above,
            // which sets a profile whose own id is null.
            term = B2bTerm.BT_19
            businessDefinition = "Def"
        }
        assertThrows(InvalidParameterException::class.java) { fieldDefinitionService.saveField(field) }
    }

    @Test
    fun `saving a field with no term at all is rejected`() {
        val profile = B2bProfile().edit { id = 1L; name = "Kunde" }
        profileRepository.givenFields(1L, emptyList())

        val field = B2bProfileField().edit {
            this.profile = profile
            // term left null entirely
            businessDefinition = "Def"
        }
        assertThrows(InvalidParameterException::class.java) { fieldDefinitionService.saveField(field) }
    }

    @Test
    fun `saving a field with a non-catalog-eligible (excluded) term is rejected`() {
        val profile = B2bProfile().edit { id = 1L; name = "Kunde" }
        profileRepository.givenFields(1L, emptyList())

        val field = B2bProfileField().edit {
            this.profile = profile
            // BT_151 is the single remaining catalogExcluded term (tax-determination
            // guard); every other reference term is catalog-eligible now (see the tests below).
            term = B2bTerm.BT_151
            businessDefinition = "Def"
        }
        assertThrows(InvalidParameterException::class.java) { fieldDefinitionService.saveField(field) }
    }

    // ── a pure-reference (binding == null) catalog-eligible term is selectable, but only
    //    with requirement OPTIONAL: MANDATORY/CONDITIONAL would always fail (the app can neither
    //    capture nor read it), so saveField rejects those up front. ──────────────────────────────

    @Test
    fun `saving an OPTIONAL field on a pure-reference term is accepted`() {
        val profile = B2bProfile().edit { id = 1L; name = "Kunde" }
        profileRepository.givenFields(1L, emptyList())

        val field = B2bProfileField().edit {
            this.profile = profile
            // Despatch advice reference -- binding == null, catalogEligible.
            term = B2bTerm.BT_16
            businessDefinition = "Def"
            requirement = B2bFieldRequirement.OPTIONAL
        }
        val saved = fieldDefinitionService.saveField(field)

        assertEquals(B2bTerm.BT_16, saved.term)
    }

    @Test
    fun `saving a MANDATORY field on a pure-reference term is rejected`() {
        val profile = B2bProfile().edit { id = 1L; name = "Kunde" }
        profileRepository.givenFields(1L, emptyList())

        val field = B2bProfileField().edit {
            this.profile = profile
            term = B2bTerm.BT_16
            businessDefinition = "Def"
            requirement = B2bFieldRequirement.MANDATORY
        }
        val ex = assertThrows(InvalidParameterException::class.java) { fieldDefinitionService.saveField(field) }
        assertTrue(ex.message!!.contains("BT-16"), "expected the term in the message: ${ex.message}")
        verify(profileRepository, never()).saveAndFlush(any())
    }

    @Test
    fun `saving a CONDITIONAL field on a pure-reference term is rejected`() {
        val profile = B2bProfile().edit { id = 1L; name = "Kunde" }
        val trigger = B2bProfileField().edit { id = 1L; this.profile = profile; term = B2bTerm.BT_19; businessDefinition = "x" }
        profileRepository.givenFields(1L, listOf(trigger))

        val field = B2bProfileField().edit {
            this.profile = profile
            term = B2bTerm.BT_16
            businessDefinition = "Def"
            requirement = B2bFieldRequirement.CONDITIONAL
            conditionTerm = B2bTerm.BT_19
        }
        assertThrows(InvalidParameterException::class.java) { fieldDefinitionService.saveField(field) }
    }

    @Test
    fun `a LINE-level term is forced to INVOICE placement even if SELLER was requested`() {
        val profile = B2bProfile().edit { id = 1L; name = "Kunde" }
        profileRepository.givenFields(1L, emptyList())

        val field = B2bProfileField().edit {
            this.profile = profile
            term = B2bTerm.BT_155
            businessDefinition = "Artikelnummer Verkäufer"
            placement = B2bPlacement.SELLER
        }
        val saved = fieldDefinitionService.saveField(field)

        assertEquals(B2bPlacement.INVOICE, saved.placement)
    }

    // ── saveField enforces B2bProfileField.assertDefaultValueCompatibleWithTerm ──

    @Test
    fun `saving a default value on a FREE term is accepted`() {
        val profile = B2bProfile().edit { id = 1L; name = "Kunde" }
        profileRepository.givenFields(1L, emptyList())

        val field = B2bProfileField().edit {
            this.profile = profile
            term = B2bTerm.BT_58
            businessDefinition = "Käufer-Kontakt-E-Mail"
            defaultValue = "rechnung@nekar-supermarkt.de"
        }
        val saved = fieldDefinitionService.saveField(field)

        assertEquals("rechnung@nekar-supermarkt.de", saved.defaultValue)
    }

    @Test
    fun `saving a default value on a BOUND term is rejected`() {
        val profile = B2bProfile().edit { id = 1L; name = "Kunde" }
        profileRepository.givenFields(1L, emptyList())

        val field = B2bProfileField().edit {
            this.profile = profile
            term = B2bTerm.BT_84 // SELLER_PARTY, BOUND, boundProperty = iban
            businessDefinition = "IBAN"
            defaultValue = "DE02120300000000202051"
        }
        assertThrows(InvalidParameterException::class.java) { fieldDefinitionService.saveField(field) }
    }

    @Test
    fun `saving a default value longer than the term's max length is rejected`() {
        val profile = B2bProfile().edit { id = 1L; name = "Kunde" }
        profileRepository.givenFields(1L, emptyList())

        val field = B2bProfileField().edit {
            this.profile = profile
            term = B2bTerm.BT_19 // maxLength = 50
            businessDefinition = "Kostenstelle"
            defaultValue = "x".repeat(51)
        }
        assertThrows(InvalidParameterException::class.java) { fieldDefinitionService.saveField(field) }
    }

    @Test
    fun `saving a duplicate term within the same profile is rejected`() {
        val profile = B2bProfile().edit { id = 1L; name = "Kunde" }
        val existing = B2bProfileField().edit { id = 10L; this.profile = profile; term = B2bTerm.BT_19; businessDefinition = "Bestehend" }
        profileRepository.givenFields(1L, listOf(existing))

        val field = B2bProfileField().edit {
            this.profile = profile
            term = B2bTerm.BT_19
            businessDefinition = "Neu"
        }
        assertThrows(InvalidParameterException::class.java) { fieldDefinitionService.saveField(field) }
    }

    @Test
    fun `saveProfile rejects a name already used by another profile`() {
        val existing = B2bProfile().edit { id = 1L; name = "Kunde XY" }
        whenever(profileRepository.findByName("Kunde XY")).thenReturn(existing)

        val candidate = B2bProfile().edit { id = 2L; name = "Kunde XY" }
        assertThrows(InvalidParameterException::class.java) { lifecycleService.saveProfile(candidate) }
    }

    @Test
    fun `saveProfile allows re-saving the same profile under its own name`() {
        val existing = B2bProfile().edit { id = 1L; name = "Kunde XY" }
        whenever(profileRepository.findByName("Kunde XY")).thenReturn(existing)
        whenever(profileRepository.save(existing)).thenReturn(existing)

        val saved = lifecycleService.saveProfile(existing)
        assertEquals("Kunde XY", saved.name)
    }

    @Test
    fun `findSelectableCatalogEntries excludes terms already used by other fields`() {
        val used = B2bProfileField().edit { id = 5L; term = B2bTerm.BT_19; businessDefinition = "x" }
        profileRepository.givenFields(1L, listOf(used))

        val selectable = fieldDefinitionService.findSelectableCatalogEntries(profileId = 1L)

        assertTrue(B2bTerm.BT_19 !in selectable)
        assertEquals(B2bTerm.entries.count { it.catalogEligible } - 1, selectable.size)
    }

    @Test
    fun `findSelectableCatalogEntries keeps the currently edited field's own term selectable`() {
        val editing = B2bProfileField().edit { id = 5L; term = B2bTerm.BT_19; businessDefinition = "x" }
        profileRepository.givenFields(1L, listOf(editing))

        val selectable = fieldDefinitionService.findSelectableCatalogEntries(profileId = 1L, currentFieldId = 5L)

        assertEquals(B2bTerm.entries.filter { it.catalogEligible }.toSet(), selectable.toSet())
    }

    @Test
    fun `findSelectableCatalogEntries returns the full catalog for a not-yet-persisted profile`() {
        // profileId == null (the create-new dialog, the profile does not exist yet) -- there are
        // no fields to exclude against yet, so the full catalog applies.
        val selectable = fieldDefinitionService.findSelectableCatalogEntries(profileId = null)

        assertEquals(B2bTerm.entries.filter { it.catalogEligible }.toSet(), selectable.toSet())
        verify(profileRepository, never()).findFieldsByProfileIds(any())
    }

    // ── T2 — seller fields, mandatory check, delete-guard ───────────────────

    @Test
    fun `findSellerFields keeps only SELLER placed fields`() {
        profileRepository.givenFields(1L,
            listOf(
                profileField(B2bTerm.BT_19, B2bPlacement.SELLER),
                profileField(B2bTerm.BT_56, B2bPlacement.SELLER),
                profileField(B2bTerm.BT_58, B2bPlacement.INVOICE),
            )
        )

        val sellerFields = fieldDefinitionService.findSellerFields(1L)

        assertEquals(setOf(B2bTerm.BT_19, B2bTerm.BT_56), sellerFields.map { it.term }.toSet())
    }

    @Test
    fun `missingMandatorySellerFields reports a MANDATORY FREE field with no value`() {
        profileRepository.givenFields(1L,
            listOf(profileField(B2bTerm.BT_19, B2bPlacement.SELLER, B2bFieldRequirement.MANDATORY))
        )

        val missing = fieldDefinitionService.missingMandatorySellerFields(1L, values = emptyMap())

        assertEquals(listOf("Definition BT-19"), missing)
    }

    @Test
    fun `missingMandatorySellerFields is satisfied once a non-blank value is present`() {
        profileRepository.givenFields(1L,
            listOf(profileField(B2bTerm.BT_19, B2bPlacement.SELLER, B2bFieldRequirement.MANDATORY))
        )

        val missing = fieldDefinitionService.missingMandatorySellerFields(1L, values = mapOf("BT-19" to "12345"))

        assertTrue(missing.isEmpty())
    }

    @Test
    fun `missingMandatorySellerFields ignores OPTIONAL fields`() {
        profileRepository.givenFields(1L,
            listOf(profileField(B2bTerm.BT_19, B2bPlacement.SELLER, B2bFieldRequirement.OPTIONAL))
        )

        val missing = fieldDefinitionService.missingMandatorySellerFields(1L, values = emptyMap())

        assertTrue(missing.isEmpty())
    }

    @Test
    fun `missingMandatorySellerFields skips BOUND terms - no generic value to check`() {
        profileRepository.givenFields(1L,
            listOf(profileField(B2bTerm.BT_84, B2bPlacement.SELLER, B2bFieldRequirement.MANDATORY))
        )

        val missing = fieldDefinitionService.missingMandatorySellerFields(1L, values = emptyMap())

        assertTrue(missing.isEmpty())
    }

    @Test
    fun `missingMandatorySellerFields with no profile is trivially satisfied`() {
        assertTrue(fieldDefinitionService.missingMandatorySellerFields(null, values = emptyMap()).isEmpty())
    }

    @Test
    fun `missingMandatorySellerFields ignores a MANDATORY field whose catalog term no longer exists`() {
        // A stale field left over from a since-removed catalog term (term == null, see
        // B2bTermConverter's null-safe read) -- the null-safe binding lookup must drop it rather
        // than throw or falsely report it as missing.
        profileRepository.givenFields(1L,
            listOf(profileField(null, B2bPlacement.SELLER, B2bFieldRequirement.MANDATORY))
        )

        val missing = fieldDefinitionService.missingMandatorySellerFields(1L, values = emptyMap())

        assertTrue(missing.isEmpty())
    }

    @Test
    fun `missingMandatorySellerFields treats an explicit blank value like a missing one`() {
        profileRepository.givenFields(1L,
            listOf(profileField(B2bTerm.BT_19, B2bPlacement.SELLER, B2bFieldRequirement.MANDATORY))
        )

        val missing = fieldDefinitionService.missingMandatorySellerFields(1L, values = mapOf("BT-19" to "   "))

        assertEquals(listOf("Definition BT-19"), missing)
    }

    // ── copy() and usage counting ─────────────────────────────────

    @Test
    fun `copy deep-copies every field of the source profile, including the XOR pair's condition`() {
        val source = B2bProfile().edit { id = 1L; name = "Kunde XY"; description = "Original-Beschreibung"; active = false; countries = mutableSetOf(Country.FR) }
        val bt72 = profileField(B2bTerm.BT_72, B2bPlacement.INVOICE, B2bFieldRequirement.CONDITIONAL_IF_EMPTY).edit {
            id = 10L; profile = source; conditionTerm = B2bTerm.BT_134; sortOrder = 1; defaultValue = "2026-01-01"
        }
        val bt134 = profileField(B2bTerm.BT_134, B2bPlacement.INVOICE, B2bFieldRequirement.CONDITIONAL_IF_EMPTY).edit {
            id = 11L; profile = source; conditionTerm = B2bTerm.BT_72; sortOrder = 2
        }
        profileRepository.givenFields(1L, listOf(bt72, bt134))
        whenever(profileRepository.findById(1L)).thenReturn(source)
        whenever(profileRepository.findByName("Kunde XY (Kopie)")).thenReturn(null)
        whenever(profileRepository.save(any())).thenAnswer { (it.arguments[0] as B2bProfile).edit { id = 99L } }

        val copy = lifecycleService.copy(1L)

        assertEquals("Kunde XY (Kopie)", copy.name)
        assertEquals("Original-Beschreibung", copy.description)
        assertTrue(copy.active, "a copy is always active regardless of the source's own state")
        assertEquals(setOf(Country.FR), copy.countries)

        val copiedFields = copy.fieldList
        assertEquals(2, copiedFields.size)
        assertTrue(copiedFields.all { it.profile === copy }, "every copied field points at the new profile")
        val copiedBt72 = copiedFields.first { it.term == B2bTerm.BT_72 }
        assertEquals(B2bFieldRequirement.CONDITIONAL_IF_EMPTY, copiedBt72.requirement)
        assertEquals(B2bTerm.BT_134, copiedBt72.conditionTerm)
        assertEquals("2026-01-01", copiedBt72.defaultValue)
        val copiedBt134 = copiedFields.first { it.term == B2bTerm.BT_134 }
        assertEquals(B2bTerm.BT_72, copiedBt134.conditionTerm, "the XOR pair's mutual trigger survives the copy verbatim (B2bTerm constants, not row FKs)")
    }

    @Test
    fun `copy de-duplicates the name against the unique-name constraint`() {
        val source = B2bProfile().edit { id = 1L; name = "Kunde XY" }
        profileRepository.givenFields(1L, emptyList())
        whenever(profileRepository.findById(1L)).thenReturn(source)
        whenever(profileRepository.findByName("Kunde XY (Kopie)")).thenReturn(B2bProfile().edit { id = 2L; name = "Kunde XY (Kopie)" })
        whenever(profileRepository.findByName("Kunde XY (Kopie 2)")).thenReturn(B2bProfile().edit { id = 3L; name = "Kunde XY (Kopie 2)" })
        whenever(profileRepository.findByName("Kunde XY (Kopie 3)")).thenReturn(null)
        whenever(profileRepository.save(any())).thenAnswer { (it.arguments[0] as B2bProfile).edit { id = 99L } }

        val copy = lifecycleService.copy(1L)

        assertEquals("Kunde XY (Kopie 3)", copy.name)
    }

    @Test
    fun `copy of a not-yet-persisted profile id is rejected`() {
        whenever(profileRepository.findById(1L)).thenReturn(null)

        assertThrows(InvalidParameterException::class.java) { lifecycleService.copy(1L) }
    }

    @Test
    fun `usageCounts is a single grouped query, no per-profile loop`() {
        whenever(recipientRepository.countGroupedByB2bProfile()).thenReturn(mapOf(1L to 3L, 2L to 1L))

        val counts = usageService.usageCounts()

        assertEquals(mapOf(1L to 3L, 2L to 1L), counts)
        verify(recipientRepository, org.mockito.kotlin.times(1)).countGroupedByB2bProfile()
        verify(recipientRepository, never()).findNamesByB2bProfileId(any())
    }

    @Test
    fun `deleteProfile is rejected when at least one recipient is assigned`() {
        whenever(recipientRepository.findNamesByB2bProfileId(1L)).thenReturn(listOf("Kunde A", "Kunde B"))

        val ex = assertThrows(InvalidParameterException::class.java) { lifecycleService.deleteProfile(1L) }
        assertTrue(ex.message!!.contains("Kunde A"))
        assertTrue(ex.message!!.contains("Kunde B"))
        verify(profileRepository, never()).delete(any())
    }

    @Test
    fun `deleteProfile succeeds when no recipient is assigned`() {
        whenever(recipientRepository.findNamesByB2bProfileId(1L)).thenReturn(emptyList())

        lifecycleService.deleteProfile(1L)

        verify(profileRepository).delete(1L)
    }

    @Test
    fun `saveFieldValues deletes an existing value when the submitted value is blank`() {
        val existing = B2bFieldValue().edit { id = 7L; ownerType = B2bFieldOwnerType.SENDER_PROFILE; ownerId = 1L; term = B2bTerm.BT_19; value = "old" }
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.SENDER_PROFILE, 1L, B2bTerm.BT_19)).thenReturn(existing)

        fieldValueService.saveFieldValues(B2bFieldOwnerType.SENDER_PROFILE, 1L, mapOf("BT-19" to "  "))

        verify(fieldValueRepository).delete(7L)
        verify(fieldValueRepository, never()).save(any())
    }

    @Test
    fun `saveFieldValues upserts a non-blank value`() {
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.SENDER_PROFILE, 1L, B2bTerm.BT_19)).thenReturn(null)
        whenever(fieldValueRepository.save(any())).thenAnswer { it.arguments[0] }

        fieldValueService.saveFieldValues(B2bFieldOwnerType.SENDER_PROFILE, 1L, mapOf("BT-19" to "12345"))

        verify(fieldValueRepository).save(any())
    }

    @Test
    fun `saveFieldValues rejects a value the PDF fonts cannot render and stores nothing`() {
        val ex = assertThrows(InvalidParameterException::class.java) {
            fieldValueService.saveFieldValues(B2bFieldOwnerType.SENDER_PROFILE, 1L, mapOf("BT-19" to "Nr 💩"))
        }

        assertTrue(ex.message!!.contains("BT-19") && ex.message!!.contains("💩"), ex.message)
        verify(fieldValueRepository, never()).save(any())
    }

    @Test
    fun `saveFieldValues is a true no-op for a blank submission with no prior value`() {
        // No existing row, and the value is blank -- neither delete nor save may be called, or
        // every unfilled optional free-text value would needlessly touch a repository.
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.SENDER_PROFILE, 1L, B2bTerm.BT_19)).thenReturn(null)

        fieldValueService.saveFieldValues(B2bFieldOwnerType.SENDER_PROFILE, 1L, mapOf("BT-19" to null))

        verify(fieldValueRepository, never()).delete(any())
        verify(fieldValueRepository, never()).save(any())
    }

    // ── T3 — article LINE template union + copy-to-position ─────────────────

    @Test
    fun `findLineTemplateFields keeps only LINE-level terms`() {
        val profileA = B2bProfile().edit { id = 1L; name = "A"; active = true }
        // An active profile that was never actually persisted (no id) contributes no fields at
        // all — the null-safe `profile.id?.let { ... }.orEmpty()` lookup must skip it, not throw.
        val unpersistedActiveProfile = B2bProfile().edit { name = "Unpersisted"; active = true }
        whenever(profileRepository.findAllActive()).thenReturn(listOf(profileA, unpersistedActiveProfile))
        profileRepository.givenFields(1L,
            listOf(
                profileField(B2bTerm.BT_155, B2bPlacement.INVOICE),
                profileField(B2bTerm.BT_19, B2bPlacement.SELLER),
                // No matching catalog term at all (stale field) — must be dropped, not throw.
                profileField(null, B2bPlacement.INVOICE),
            )
        )

        val fields = fieldDefinitionService.findLineTemplateFields()

        assertEquals(listOf(B2bTerm.BT_155), fields.map { it.term })
    }

    @Test
    fun `findLineTemplateFields unions across active profiles, first profile wins on duplicate terms`() {
        val profileA = B2bProfile().edit { id = 1L; name = "A"; active = true }
        val profileB = B2bProfile().edit { id = 2L; name = "B"; active = true }
        whenever(profileRepository.findAllActive()).thenReturn(listOf(profileA, profileB))
        profileRepository.givenFields(1L, listOf(profileField(B2bTerm.BT_155, B2bPlacement.INVOICE).edit { businessDefinition = "von A" }))
        profileRepository.givenFields(2L,
            listOf(
                profileField(B2bTerm.BT_155, B2bPlacement.INVOICE).edit { businessDefinition = "von B" },
                profileField(B2bTerm.BT_156, B2bPlacement.INVOICE),
            )
        )

        val fields = fieldDefinitionService.findLineTemplateFields()

        assertEquals(setOf(B2bTerm.BT_155, B2bTerm.BT_156), fields.map { it.term }.toSet())
        assertEquals("von A", fields.first { it.term == B2bTerm.BT_155 }.businessDefinition)
    }

    @Test
    fun `copyLineTemplateValuesToInvoiceItem copies only terms present in the invoice's own profile`() {
        val b2bProfile = B2bProfile().edit { id = 5L; name = "Kunde" }
        val invoice = Invoice().edit { id = 100L; senderProfile = SenderProfile(); this.b2bProfile = b2bProfile }
        whenever(invoiceRepository.findById(100L)).thenReturn(invoice)
        profileRepository.givenFields(5L,
            listOf(
                profileField(B2bTerm.BT_155, B2bPlacement.INVOICE),
                // No matching catalog term at all (stale field) — must be dropped, not throw.
                profileField(null, B2bPlacement.INVOICE),
            )
        )
        whenever(fieldValueRepository.findByOwner(B2bFieldOwnerType.ARTICLE, 42L)).thenReturn(
            listOf(
                B2bFieldValue().edit { ownerType = B2bFieldOwnerType.ARTICLE; ownerId = 42L; term = B2bTerm.BT_155; value = "SELLER-1" },
                B2bFieldValue().edit { ownerType = B2bFieldOwnerType.ARTICLE; ownerId = 42L; term = B2bTerm.BT_156; value = "BUYER-1" },
            )
        )
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE_ITEM, 200L, B2bTerm.BT_155)).thenReturn(null)
        whenever(fieldValueRepository.save(any())).thenAnswer { it.arguments[0] }

        fieldValueService.copyLineTemplateValuesToInvoiceItem(articleId = 42L, invoiceItemId = 200L, invoiceId = 100L)

        val captor = org.mockito.kotlin.argumentCaptor<B2bFieldValue>()
        verify(fieldValueRepository).save(captor.capture())
        assertEquals(B2bTerm.BT_155, captor.firstValue.term)
        assertEquals("SELLER-1", captor.firstValue.value)
        assertEquals(B2bFieldOwnerType.INVOICE_ITEM, captor.firstValue.ownerType)
        assertEquals(200L, captor.firstValue.ownerId)
    }

    @Test
    fun `copyLineTemplateValuesToInvoiceItem is a no-op when the invoice has no B2B profile`() {
        val invoice = Invoice().edit { id = 100L; senderProfile = SenderProfile() }
        whenever(invoiceRepository.findById(100L)).thenReturn(invoice)

        fieldValueService.copyLineTemplateValuesToInvoiceItem(articleId = 42L, invoiceItemId = 200L, invoiceId = 100L)

        verify(fieldValueRepository, never()).save(any())
    }

    @Test
    fun `copyLineTemplateValuesToInvoiceItem is a no-op when the invoice's profile has no LINE-level fields at all`() {
        val b2bProfile = B2bProfile().edit { id = 5L; name = "Kunde" }
        val invoice = Invoice().edit { id = 100L; senderProfile = SenderProfile(); this.b2bProfile = b2bProfile }
        whenever(invoiceRepository.findById(100L)).thenReturn(invoice)
        // The profile has only a HEADER field, no LINE field -- allowedTerms stays empty.
        profileRepository.givenFields(5L, listOf(profileField(B2bTerm.BT_19, B2bPlacement.INVOICE)))

        fieldValueService.copyLineTemplateValuesToInvoiceItem(articleId = 42L, invoiceItemId = 200L, invoiceId = 100L)

        verify(fieldValueRepository, never()).findByOwner(any(), any())
        verify(fieldValueRepository, never()).save(any())
    }

    @Test
    fun `copyLineTemplateValuesToInvoiceItem is a no-op when the article has no template value for an allowed term`() {
        val b2bProfile = B2bProfile().edit { id = 5L; name = "Kunde" }
        val invoice = Invoice().edit { id = 100L; senderProfile = SenderProfile(); this.b2bProfile = b2bProfile }
        whenever(invoiceRepository.findById(100L)).thenReturn(invoice)
        profileRepository.givenFields(5L, listOf(profileField(B2bTerm.BT_155, B2bPlacement.INVOICE)))
        // The article carries no template values at all -- allowedTerms is not empty, but the
        // filtered value set is.
        whenever(fieldValueRepository.findByOwner(B2bFieldOwnerType.ARTICLE, 42L)).thenReturn(emptyList())

        fieldValueService.copyLineTemplateValuesToInvoiceItem(articleId = 42L, invoiceItemId = 200L, invoiceId = 100L)

        verify(fieldValueRepository, never()).save(any())
    }

    // ── T4 — invoice header/item field selection + profile resolution ────────

    @Test
    fun `findInvoiceHeaderFields keeps only FREE HEADER fields placed on the invoice`() {
        // BT-19 INVOICE, BT-56 INVOICE, BT-58 SELLER (not on invoice), BT-155 LINE,
        // BT-13 BOUND (no widget) — only BT-19 and BT-56 survive.
        profileRepository.givenFields(1L,
            listOf(
                profileField(B2bTerm.BT_19, B2bPlacement.INVOICE),
                profileField(B2bTerm.BT_56, B2bPlacement.INVOICE),
                profileField(B2bTerm.BT_58, B2bPlacement.SELLER),
                profileField(B2bTerm.BT_155, B2bPlacement.INVOICE),
                profileField(B2bTerm.BT_13, B2bPlacement.INVOICE),
            )
        )

        val headerFields = fieldDefinitionService.findInvoiceHeaderFields(1L)

        assertEquals(setOf(B2bTerm.BT_19, B2bTerm.BT_56), headerFields.map { it.term }.toSet())
    }

    @Test
    fun `findInvoiceItemFields keeps only FREE LINE fields`() {
        profileRepository.givenFields(1L,
            listOf(
                profileField(B2bTerm.BT_155, B2bPlacement.INVOICE),
                profileField(B2bTerm.BT_156, B2bPlacement.INVOICE),
                profileField(B2bTerm.BT_19, B2bPlacement.INVOICE),
                profileField(B2bTerm.BT_134, B2bPlacement.INVOICE),
            )
        )

        val itemFields = fieldDefinitionService.findInvoiceItemFields(1L)

        assertEquals(setOf(B2bTerm.BT_155, B2bTerm.BT_156), itemFields.map { it.term }.toSet())
    }

    @Test
    fun `findProfileIdForInvoice resolves the invoice's own B2B profile server-side`() {
        val b2bProfile = B2bProfile().edit { id = 5L; name = "Kunde" }
        val invoice = Invoice().edit { id = 100L; senderProfile = SenderProfile(); this.b2bProfile = b2bProfile }
        whenever(invoiceRepository.findById(100L)).thenReturn(invoice)

        assertEquals(5L, fieldValueService.findProfileIdForInvoice(100L))
    }

    @Test
    fun `findProfileIdForInvoice is null when the invoice has no B2B profile`() {
        val invoice = Invoice().edit { id = 100L; senderProfile = SenderProfile() }
        whenever(invoiceRepository.findById(100L)).thenReturn(invoice)

        assertEquals(null, fieldValueService.findProfileIdForInvoice(100L))
    }

    // ── RECIPIENT-placed field selection, per-customer value CRUD, resolution chain ──

    @Test
    fun `findRecipientFields keeps only RECIPIENT-placed FREE HEADER fields`() {
        profileRepository.givenFields(1L,
            listOf(
                profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT),
                profileField(B2bTerm.BT_19, B2bPlacement.INVOICE),
                profileField(B2bTerm.BT_84, B2bPlacement.SELLER),
                // RECIPIENT-placed but LINE-level — must be dropped by the level filter itself
                // (not merely excluded by placement, like BT-19/BT-84 above).
                profileField(B2bTerm.BT_155, B2bPlacement.RECIPIENT),
                // RECIPIENT-placed, HEADER-level, but BOUND — dropped by the binding filter.
                profileField(B2bTerm.BT_13, B2bPlacement.RECIPIENT),
                // RECIPIENT-placed, no catalog term at all (stale field) — the null-safe level
                // lookup must drop it rather than throw.
                profileField(null, B2bPlacement.RECIPIENT),
            )
        )

        val fields = fieldDefinitionService.findRecipientFields(1L)

        assertEquals(listOf(B2bTerm.BT_58), fields.map { it.term })
    }

    @Test
    fun `findRecipientFieldValues resolves stored rows against the recipient's own profile fields`() {
        val field = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT).edit { id = 77L }
        profileRepository.givenFields(1L, listOf(field))
        whenever(recipientFieldValues.findValues(5L)).thenReturn(mapOf(77L to "kunde@example.com"))

        val values = fieldValueService.findRecipientFieldValues(recipientId = 5L, profileId = 1L)

        assertEquals(mapOf("BT-58" to "kunde@example.com"), values)
    }

    @Test
    fun `findRecipientFieldValues drops a stale profile field whose catalog term is gone`() {
        // Same "orphaned field" shape as findInvoiceHeaderFields/findInvoiceItemFields above,
        // but exercised through the id-plus-officialCode mapNotNull chain this method builds its
        // own btCodeByFieldId lookup from — a term-less field must not surface a "BT-null" entry.
        val orphanedField = profileField(null, B2bPlacement.RECIPIENT).edit { id = 88L }
        val realField = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT).edit { id = 77L }
        profileRepository.givenFields(1L, listOf(orphanedField, realField))
        whenever(recipientFieldValues.findValues(5L)).thenReturn(mapOf(88L to "orphan-value", 77L to "kunde@example.com"))
        val recipient = storedRecipient()

        fieldValueService.saveRecipientFieldValues(recipientId = 5L, profileId = 1L, values = mapOf("BT-58" to "kunde@example.com"))

        val row = recipient.b2bFieldValueList.single()
        assertEquals(5L, row.recipientId)
        assertEquals(77L, row.profileFieldId)
        assertEquals("kunde@example.com", row.value)
}

    @Test
    fun `saveRecipientFieldValues ignores a profile field with no catalog term at all`() {
        // fieldByBtCode's own field.term?.officialCode?.let{} chain must drop a term-less field
        // rather than crash building the by-code lookup — same orphan shape saveFieldValues and
        // the findXFields family already guard, exercised here on the RECIPIENT-scoped map.
        val orphanedField = profileField(null, B2bPlacement.RECIPIENT).edit { id = 88L }
        profileRepository.givenFields(1L, listOf(orphanedField))
        val recipient = storedRecipient()

        fieldValueService.saveRecipientFieldValues(recipientId = 5L, profileId = 1L, values = mapOf("BT-58" to "kunde@example.com"))

        assertTrue(recipient.b2bFieldValueList.isEmpty())
}

    @Test
    fun `saveRecipientFieldValues deletes an existing value when the submitted value is blank`() {
        val field = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT).edit { id = 77L }
        profileRepository.givenFields(1L, listOf(field))
        val recipient = storedRecipient(77L to "old")

        fieldValueService.saveRecipientFieldValues(recipientId = 5L, profileId = 1L, values = mapOf("BT-58" to "  "))

        assertTrue(recipient.b2bFieldValueList.isEmpty())
}

    @Test
    fun `saveRecipientFieldValues silently ignores a bt_code with no matching field on the profile`() {
        profileRepository.givenFields(1L, emptyList())
        val recipient = storedRecipient()

        fieldValueService.saveRecipientFieldValues(recipientId = 5L, profileId = 1L, values = mapOf("BT-58" to "kunde@example.com"))

        assertTrue(recipient.b2bFieldValueList.isEmpty())
}

    @Test
    fun `saveRecipientFieldValues ignores a field that was never persisted`() {
        // A profile field with no id yet (e.g. returned mid-transaction before its own insert
        // flushed) can own no stored recipient value — must be skipped, not throw.
        val unpersistedField = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT)
        profileRepository.givenFields(1L, listOf(unpersistedField))
        val recipient = storedRecipient()

        fieldValueService.saveRecipientFieldValues(recipientId = 5L, profileId = 1L, values = mapOf("BT-58" to "kunde@example.com"))

        assertTrue(recipient.b2bFieldValueList.isEmpty())
}

    @Test
    fun `saveRecipientFieldValues is a true no-op for a blank submission with no prior value`() {
        val field = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT).edit { id = 77L }
        profileRepository.givenFields(1L, listOf(field))
        val recipient = storedRecipient()

        fieldValueService.saveRecipientFieldValues(recipientId = 5L, profileId = 1L, values = mapOf("BT-58" to "  "))

        assertTrue(recipient.b2bFieldValueList.isEmpty())
}

    @Test
    fun `resolvedRecipientFieldValues chains invoice value over recipient value over profile default`() {
        val b2bProfile = B2bProfile().edit { id = 1L; name = "Nekar" }
        val recipient = aRecipient { id = 5L }
        val invoice = Invoice().edit { id = 100L; senderProfile = SenderProfile(); this.b2bProfile = b2bProfile; this.recipient = recipient }
        val field = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT).edit { id = 77L; defaultValue = "profil@example.com" }
        profileRepository.givenFields(1L, listOf(field))
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 100L, B2bTerm.BT_58)).thenReturn(null)
        whenever(recipientFieldValues.findValues(5L)).thenReturn(mapOf(77L to "kunde@example.com"))

        val resolved = fieldValueService.resolvedRecipientFieldValues(invoice)

        assertEquals(mapOf("BT-58" to "kunde@example.com"), resolved)
    }

    @Test
    fun `resolvedRecipientFieldValues falls back to the profile default when nothing else is stored`() {
        val b2bProfile = B2bProfile().edit { id = 1L; name = "Nekar" }
        val recipient = aRecipient { id = 5L }
        val invoice = Invoice().edit { id = 100L; senderProfile = SenderProfile(); this.b2bProfile = b2bProfile; this.recipient = recipient }
        val field = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT).edit { id = 77L; defaultValue = "profil@example.com" }
        profileRepository.givenFields(1L, listOf(field))
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 100L, B2bTerm.BT_58)).thenReturn(null)

        val resolved = fieldValueService.resolvedRecipientFieldValues(invoice)

        assertEquals(mapOf("BT-58" to "profil@example.com"), resolved)
    }

    @Test
    fun `resolvedRecipientFieldValues is empty when the invoice has no B2B profile`() {
        val invoice = Invoice().edit { id = 100L; senderProfile = SenderProfile() }

        assertTrue(fieldValueService.resolvedRecipientFieldValues(invoice).isEmpty())
    }

    @Test
    fun `resolvedRecipientFieldValues is empty when the invoice has a B2B profile but no id of its own`() {
        // Distinct from the "no B2B profile at all" case above: here b2bProfile IS set, only the
        // invoice itself was never persisted.
        val b2bProfile = B2bProfile().edit { id = 1L; name = "Nekar" }
        val invoice = Invoice().edit { senderProfile = SenderProfile(); this.b2bProfile = b2bProfile }

        assertTrue(fieldValueService.resolvedRecipientFieldValues(invoice).isEmpty())
    }

    @Test
    fun `resolvedRecipientFieldValues falls back to the profile default when the invoice has no recipient`() {
        val b2bProfile = B2bProfile().edit { id = 1L; name = "Nekar" }
        val invoice = Invoice().edit { id = 100L; senderProfile = SenderProfile(); this.b2bProfile = b2bProfile } // recipient left null
        val field = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT).edit { id = 77L; defaultValue = "profil@example.com" }
        profileRepository.givenFields(1L, listOf(field))
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 100L, B2bTerm.BT_58)).thenReturn(null)

        val resolved = fieldValueService.resolvedRecipientFieldValues(invoice)

        assertEquals(mapOf("BT-58" to "profil@example.com"), resolved)
        verify(recipientFieldValues, never()).findValues(any())
    }

    @Test
    fun `resolvedRecipientFieldValues falls back to the profile default for a field that was never persisted`() {
        val b2bProfile = B2bProfile().edit { id = 1L; name = "Nekar" }
        val recipient = aRecipient { id = 5L }
        val invoice = Invoice().edit { id = 100L; senderProfile = SenderProfile(); this.b2bProfile = b2bProfile; this.recipient = recipient }
        // No id yet -- can own no stored recipient-level value, so the lookup must be skipped.
        val field = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT).edit { defaultValue = "profil@example.com" }
        profileRepository.givenFields(1L, listOf(field))
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 100L, B2bTerm.BT_58)).thenReturn(null)

        val resolved = fieldValueService.resolvedRecipientFieldValues(invoice)

        assertEquals(mapOf("BT-58" to "profil@example.com"), resolved)
        verify(recipientFieldValues, never()).findValues(any())
    }

    @Test
    fun `resolvedRecipientFieldValues treats a blank invoice-level value like absent and falls through to the recipient value`() {
        val b2bProfile = B2bProfile().edit { id = 1L; name = "Nekar" }
        val recipient = aRecipient { id = 5L }
        val invoice = Invoice().edit { id = 100L; senderProfile = SenderProfile(); this.b2bProfile = b2bProfile; this.recipient = recipient }
        val field = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT).edit { id = 77L; defaultValue = "profil@example.com" }
        profileRepository.givenFields(1L, listOf(field))
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 100L, B2bTerm.BT_58)).thenReturn(
            B2bFieldValue().edit { term = B2bTerm.BT_58; value = "   " },
        )
        whenever(recipientFieldValues.findValues(5L)).thenReturn(mapOf(77L to "kunde@example.com"))

        val resolved = fieldValueService.resolvedRecipientFieldValues(invoice)

        assertEquals(mapOf("BT-58" to "kunde@example.com"), resolved)
    }

    @Test
    fun `resolvedRecipientFieldValues treats a blank recipient-level value like absent and falls through to the default`() {
        val b2bProfile = B2bProfile().edit { id = 1L; name = "Nekar" }
        val recipient = aRecipient { id = 5L }
        val invoice = Invoice().edit { id = 100L; senderProfile = SenderProfile(); this.b2bProfile = b2bProfile; this.recipient = recipient }
        val field = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT).edit { id = 77L; defaultValue = "profil@example.com" }
        profileRepository.givenFields(1L, listOf(field))
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 100L, B2bTerm.BT_58)).thenReturn(null)
        whenever(recipientFieldValues.findValues(5L)).thenReturn(mapOf(77L to "   "))

        val resolved = fieldValueService.resolvedRecipientFieldValues(invoice)

        assertEquals(mapOf("BT-58" to "profil@example.com"), resolved)
    }

    // ── the seller-fields union (decision 12 removed the seller's own profile selector) ──

    @Test
    fun `findSellerFieldsUnion unions SELLER placed fields across active profiles, dedup by term`() {
        val profileA = B2bProfile().edit { id = 1L; name = "A"; active = true }
        val profileB = B2bProfile().edit { id = 2L; name = "B"; active = true }
        // An active profile that was never actually persisted (no id) contributes no fields at
        // all — the null-safe `profile.id?.let { ... }.orEmpty()` lookup must skip it, not throw.
        val unpersistedActiveProfile = B2bProfile().edit { name = "Unpersisted"; active = true }
        whenever(profileRepository.findAll(0, Int.MAX_VALUE)).thenReturn(listOf(profileA, profileB, unpersistedActiveProfile))
        profileRepository.givenFields(1L,
            listOf(
                profileField(B2bTerm.BT_84, B2bPlacement.SELLER).edit { businessDefinition = "von A" },
                profileField(B2bTerm.BT_19, B2bPlacement.INVOICE), // placement INVOICE -- must be excluded
            )
        )
        profileRepository.givenFields(2L,
            listOf(
                profileField(B2bTerm.BT_84, B2bPlacement.SELLER).edit { businessDefinition = "von B" },
                // findSellerFieldsUnion never filters by catalogEligible — B2bTerm.BT_29
                // (SELLER_PARTY) simply stands in for "some other seller-placed term" here.
                profileField(B2bTerm.BT_29, B2bPlacement.SELLER),
            )
        )

        val fields = fieldDefinitionService.findSellerFieldsUnion()

        assertEquals(setOf(B2bTerm.BT_84, B2bTerm.BT_29), fields.map { it.field.term }.toSet())
        assertEquals("von A", fields.first { it.field.term == B2bTerm.BT_84 }.field.businessDefinition, "erstes Profil gewinnt bei Duplikaten")
        // Both contributing profiles are unscoped (§2 default) — every field renders in the
        // unscoped group.
        assertTrue(fields.all { it.group == null }, "unscoped profiles must resolve to the unscoped group")
    }

    // ── anbieter-einvoice-ergonomie §2/§4 — country-scoped union, mixed unscoped/FR/LU profiles ──

    @Test
    fun `findSellerFieldsUnion resolves a mix of unscoped, FR-scoped and LU-scoped profiles to the right groups`() {
        val unscoped = B2bProfile().edit { id = 1L; name = "Standard"; active = true }
        val frProfile = B2bProfile().edit { id = 2L; name = "Factur-X FR"; active = true; countries = mutableSetOf(Country.FR) }
        val luProfile = B2bProfile().edit { id = 3L; name = "Factur-X LU"; active = true; countries = mutableSetOf(Country.LU) }
        whenever(profileRepository.findAll(0, Int.MAX_VALUE)).thenReturn(listOf(unscoped, frProfile, luProfile))
        profileRepository.givenFields(1L, listOf(profileField(B2bTerm.BT_19, B2bPlacement.SELLER)))
        profileRepository.givenFields(2L, listOf(profileField(B2bTerm.BT_84, B2bPlacement.SELLER)))
        profileRepository.givenFields(3L, listOf(profileField(B2bTerm.BT_56, B2bPlacement.SELLER)))

        val fields = fieldDefinitionService.findSellerFieldsUnion().associateBy { it.field.term }

        assertEquals(null, fields.getValue(B2bTerm.BT_19).group, "unscoped profile's field has no group")
        assertEquals(Country.FR, fields.getValue(B2bTerm.BT_84).group)
        assertEquals(setOf(Country.FR), fields.getValue(B2bTerm.BT_84).triggerCountries)
        assertEquals(Country.LU, fields.getValue(B2bTerm.BT_56).group)
        assertEquals(setOf(Country.LU), fields.getValue(B2bTerm.BT_56).triggerCountries)
    }

    @Test
    fun `findSellerFieldsUnion - a term contributed by both an unscoped and a scoped profile always resolves to the unscoped group`() {
        val unscoped = B2bProfile().edit { id = 1L; name = "Standard"; active = true }
        val frProfile = B2bProfile().edit { id = 2L; name = "Factur-X FR"; active = true; countries = mutableSetOf(Country.FR) }
        whenever(profileRepository.findAll(0, Int.MAX_VALUE)).thenReturn(listOf(unscoped, frProfile))
        profileRepository.givenFields(1L, listOf(profileField(B2bTerm.BT_84, B2bPlacement.SELLER)))
        profileRepository.givenFields(2L, listOf(profileField(B2bTerm.BT_84, B2bPlacement.SELLER)))

        val fields = fieldDefinitionService.findSellerFieldsUnion()

        assertEquals(1, fields.size)
        assertEquals(null, fields.single().group, "an unscoped contributor always wins — unscoped sorts first")
    }

    @Test
    fun `finding EP4-F2 (fixed) - findSellerFieldsUnion still includes a deactivated profile still assigned to a recipient`() {
        val deactivated = B2bProfile().edit { id = 1L; name = "Deactivated"; active = false }
        whenever(profileRepository.findAll(0, Int.MAX_VALUE)).thenReturn(listOf(deactivated))
        profileRepository.givenFields(1L, listOf(profileField(B2bTerm.BT_84, B2bPlacement.SELLER)))
        whenever(recipientRepository.findNamesByB2bProfileId(1L)).thenReturn(listOf("Kunde GmbH"))
        whenever(invoiceLookup.existsByB2bProfileId(1L)).thenReturn(false)

        val fields = fieldDefinitionService.findSellerFieldsUnion()

        assertEquals(setOf(B2bTerm.BT_84), fields.map { it.field.term }.toSet(), "a still-referenced deactivated profile must keep contributing")
    }

    @Test
    fun `finding EP4-F2 (fixed) - findSellerFieldsUnion still includes a deactivated profile still pinned on an invoice's own override`() {
        val deactivated = B2bProfile().edit { id = 1L; name = "Deactivated"; active = false }
        whenever(profileRepository.findAll(0, Int.MAX_VALUE)).thenReturn(listOf(deactivated))
        profileRepository.givenFields(1L, listOf(profileField(B2bTerm.BT_84, B2bPlacement.SELLER)))
        whenever(recipientRepository.findNamesByB2bProfileId(1L)).thenReturn(emptyList())
        whenever(invoiceLookup.existsByB2bProfileId(1L)).thenReturn(true)

        val fields = fieldDefinitionService.findSellerFieldsUnion()

        assertEquals(setOf(B2bTerm.BT_84), fields.map { it.field.term }.toSet(), "an invoice-pinned deactivated profile must keep contributing")
    }

    @Test
    fun `finding EP4-F2 (fixed) - findSellerFieldsUnion drops a deactivated profile with no recipient or invoice reference at all`() {
        val deactivated = B2bProfile().edit { id = 1L; name = "Deactivated"; active = false }
        whenever(profileRepository.findAll(0, Int.MAX_VALUE)).thenReturn(listOf(deactivated))
        whenever(recipientRepository.findNamesByB2bProfileId(1L)).thenReturn(emptyList())
        whenever(invoiceLookup.existsByB2bProfileId(1L)).thenReturn(false)

        val fields = fieldDefinitionService.findSellerFieldsUnion()

        assertTrue(fields.isEmpty(), "an unreferenced deactivated profile must not contribute, same as before EP4-F2")
    }

    // ─── saveRecipientFieldValues ─────────────────────────────────

    @Test
    fun `saveRecipientFieldValues creates a new value when blank becomes non-blank`() {
        val field = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT).edit { id = 77L }
        profileRepository.givenFields(1L, listOf(field))
        val recipient = storedRecipient()

        fieldValueService.saveRecipientFieldValues(5L, 1L, mapOf("BT-58" to "neuer@wert.de"))

        assertEquals("neuer@wert.de", recipient.b2bFieldValueList.single().value)
}

    @Test
    fun `saveRecipientFieldValues deletes an existing value when it becomes blank`() {
        val field = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT).edit { id = 77L }
        profileRepository.givenFields(1L, listOf(field))
        val recipient = storedRecipient(77L to "alt@wert.de")

        fieldValueService.saveRecipientFieldValues(5L, 1L, mapOf("BT-58" to "   "))

        assertTrue(recipient.b2bFieldValueList.isEmpty())
}

    @Test
    fun `saveRecipientFieldValues updates an existing value`() {
        val field = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT).edit { id = 77L }
        profileRepository.givenFields(1L, listOf(field))
        val recipient = storedRecipient(77L to "alt@wert.de")

        fieldValueService.saveRecipientFieldValues(5L, 1L, mapOf("BT-58" to "neu@wert.de"))

        assertEquals("neu@wert.de", recipient.b2bFieldValueList.single().value)
}

    @Test
    fun `saveRecipientFieldValues ignores unknown bt_code`() {
        profileRepository.givenFields(1L, emptyList())
        val recipient = storedRecipient()

        fieldValueService.saveRecipientFieldValues(5L, 1L, mapOf("BT-UNKNOWN" to "wert"))

        assertTrue(recipient.b2bFieldValueList.isEmpty())
}

    // ─── findRecipientFieldValues ────────────────────────────────

    @Test
    fun `findRecipientFieldValues returns empty map when no values stored`() {
        val field = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT).edit { id = 77L }
        profileRepository.givenFields(1L, listOf(field))
        whenever(recipientFieldValues.findValues(5L)).thenReturn(emptyMap())

        val values = fieldValueService.findRecipientFieldValues(5L, 1L)

        assertTrue(values.isEmpty())
    }

    @Test
    fun `findRecipientFieldValues maps field IDs to bt_codes correctly`() {
        val field1 = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT).edit { id = 77L }
        val field2 = profileField(B2bTerm.BT_19, B2bPlacement.RECIPIENT).edit { id = 88L }
        // A field that was never persisted (no id) can never own a stored value — it must be
        // skipped by the null-safe `field.id?.let { ... }` lookup, not throw.
        val unpersistedField = profileField(B2bTerm.BT_84, B2bPlacement.RECIPIENT)
        profileRepository.givenFields(1L, listOf(field1, field2, unpersistedField))
        whenever(recipientFieldValues.findValues(5L)).thenReturn(mapOf(77L to "test@example.com", 88L to "test-value", 999L to "orphaned"))

        val values = fieldValueService.findRecipientFieldValues(5L, 1L)

        assertEquals(mapOf("BT-58" to "test@example.com", "BT-19" to "test-value"), values)
    }

    // ─── resolvedRecipientFieldValues — fallback chain ──────────────────

    @Test
    fun `resolvedRecipientFieldValues prefers invoice-level value over recipient-level and default`() {
        val b2bProfile = B2bProfile().edit { id = 1L; name = "Nekar" }
        val recipient = aRecipient { id = 5L }
        val invoice = Invoice().edit { id = 100L; senderProfile = SenderProfile(); this.b2bProfile = b2bProfile; this.recipient = recipient }
        val field = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT).edit { id = 77L; defaultValue = "profil@example.com" }
        profileRepository.givenFields(1L, listOf(field))
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 100L, B2bTerm.BT_58)).thenReturn(
            B2bFieldValue().edit { term = B2bTerm.BT_58; value = "invoice@example.com" }
        )
        whenever(recipientFieldValues.findValues(5L)).thenReturn(mapOf(77L to "kunde@example.com"))

        val resolved = fieldValueService.resolvedRecipientFieldValues(invoice)

        assertEquals(mapOf("BT-58" to "invoice@example.com"), resolved)
    }

    @Test
    fun `resolvedRecipientFieldValues returns empty map when invoice has no id`() {
        val invoice = Invoice().edit { senderProfile = SenderProfile() } // id is null

        assertTrue(fieldValueService.resolvedRecipientFieldValues(invoice).isEmpty())
    }

    // ─── resolvedGuidedFieldValues (fix-einvoice-guided-profile-party-values, Required change 1) ───
    // The single chain the guided entry-pane step 02 reads from: invoice override > party value
    // (seller/recipient) > profile default, keyed by bt_code, tagged with the tier it came from.

    @Test
    fun `resolvedGuidedFieldValues is empty when the invoice has no B2B profile`() {
        val invoice = Invoice().edit { id = 100L; senderProfile = SenderProfile() }
        whenever(invoiceRepository.findById(100L)).thenReturn(invoice)

        assertTrue(fieldValueService.resolvedGuidedFieldValues(100L).isEmpty())
    }

    @Test
    fun `resolvedGuidedFieldValues is empty when the invoice itself is not found`() {
        whenever(invoiceRepository.findById(999L)).thenReturn(null)

        assertTrue(fieldValueService.resolvedGuidedFieldValues(999L).isEmpty())
    }

    @Test
    fun `resolvedGuidedFieldValues resolves an INVOICE-placed field from the stored invoice value`() {
        val sender = aSenderProfile { id = 1L }
        val invoice = Invoice().edit { id = 100L; senderProfile = sender; b2bProfile = B2bProfile().edit { id = 1L } }
        whenever(invoiceRepository.findById(100L)).thenReturn(invoice)
        val field = profileField(B2bTerm.BT_19, B2bPlacement.INVOICE, requirement = B2bFieldRequirement.OPTIONAL).edit {
            id = 1L; defaultValue = "default-kst"
        }
        profileRepository.givenFields(1L, listOf(field))
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 100L, B2bTerm.BT_19)).thenReturn(
            B2bFieldValue().edit { term = B2bTerm.BT_19; value = "invoice-kst" },
        )

        val resolved = fieldValueService.resolvedGuidedFieldValues(100L)

        assertEquals("invoice-kst", resolved.getValue("BT-19").value)
        assertEquals(de.littletrotter.domain.reference.B2bFieldValueOrigin.INVOICE, resolved.getValue("BT-19").origin)
        assertEquals("default-kst", resolved.getValue("BT-19").inheritedValue, "inherited must ignore the invoice tier")
    }

    @Test
    fun `resolvedGuidedFieldValues resolves a SELLER-placed field from the seller's own SENDER_PROFILE value`() {
        val sender = aSenderProfile { id = 1L }
        val invoice = Invoice().edit { id = 100L; senderProfile = sender; b2bProfile = B2bProfile().edit { id = 1L } }
        whenever(invoiceRepository.findById(100L)).thenReturn(invoice)
        val field = profileField(B2bTerm.BT_58, B2bPlacement.SELLER).edit { id = 1L }
        profileRepository.givenFields(1L, listOf(field))
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 100L, B2bTerm.BT_58)).thenReturn(null)
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.SENDER_PROFILE, 1L, B2bTerm.BT_58)).thenReturn(
            B2bFieldValue().edit { term = B2bTerm.BT_58; value = "anbieter@example.com" },
        )

        val resolved = fieldValueService.resolvedGuidedFieldValues(100L)

        assertEquals("anbieter@example.com", resolved.getValue("BT-58").value)
        assertEquals(de.littletrotter.domain.reference.B2bFieldValueOrigin.PARTY, resolved.getValue("BT-58").origin)
        assertEquals("anbieter@example.com", resolved.getValue("BT-58").inheritedValue)
    }

    @Test
    fun `resolvedGuidedFieldValues prefers an invoice-level override over the seller's own value`() {
        val sender = aSenderProfile { id = 1L }
        val invoice = Invoice().edit { id = 100L; senderProfile = sender; b2bProfile = B2bProfile().edit { id = 1L } }
        whenever(invoiceRepository.findById(100L)).thenReturn(invoice)
        val field = profileField(B2bTerm.BT_58, B2bPlacement.SELLER).edit { id = 1L }
        profileRepository.givenFields(1L, listOf(field))
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 100L, B2bTerm.BT_58)).thenReturn(
            B2bFieldValue().edit { term = B2bTerm.BT_58; value = "rechnung-spezifisch@example.com" },
        )
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.SENDER_PROFILE, 1L, B2bTerm.BT_58)).thenReturn(
            B2bFieldValue().edit { term = B2bTerm.BT_58; value = "anbieter@example.com" },
        )

        val resolved = fieldValueService.resolvedGuidedFieldValues(100L)

        assertEquals("rechnung-spezifisch@example.com", resolved.getValue("BT-58").value)
        assertEquals(de.littletrotter.domain.reference.B2bFieldValueOrigin.INVOICE, resolved.getValue("BT-58").origin)
        // The write-back comparison target still names the seller's own value, not the override —
        // an edit back to "anbieter@example.com" must delete the invoice row (§3).
        assertEquals("anbieter@example.com", resolved.getValue("BT-58").inheritedValue)
    }

    @Test
    fun `resolvedGuidedFieldValues excludes a BOUND SELLER-placed term - no widget, no resolution`() {
        val sender = aSenderProfile { id = 1L }
        val invoice = Invoice().edit { id = 100L; senderProfile = sender; b2bProfile = B2bProfile().edit { id = 1L } }
        whenever(invoiceRepository.findById(100L)).thenReturn(invoice)
        // BT-84 (IBAN) is BOUND — B2bProfileFieldsSection never renders a widget for it.
        val field = profileField(B2bTerm.BT_84, B2bPlacement.SELLER).edit { id = 1L }
        profileRepository.givenFields(1L, listOf(field))

        val resolved = fieldValueService.resolvedGuidedFieldValues(100L)

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun `resolvedGuidedFieldValues resolves a RECIPIENT-placed field from the recipient's stored value`() {
        val invoice = Invoice().edit {
            id = 100L; senderProfile = SenderProfile(); b2bProfile = B2bProfile().edit { id = 1L }
            recipient = aRecipient { id = 5L }
        }
        whenever(invoiceRepository.findById(100L)).thenReturn(invoice)
        val field = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT).edit { id = 77L }
        profileRepository.givenFields(1L, listOf(field))
        whenever(recipientFieldValues.findValues(5L)).thenReturn(mapOf(77L to "kunde@example.com"))

        val resolved = fieldValueService.resolvedGuidedFieldValues(100L)

        assertEquals("kunde@example.com", resolved.getValue("BT-58").value)
        assertEquals(de.littletrotter.domain.reference.B2bFieldValueOrigin.PARTY, resolved.getValue("BT-58").origin)
    }

    @Test
    fun `resolvedGuidedFieldValues falls back to the profile default when no invoice or party value is stored`() {
        val invoice = Invoice().edit {
            id = 100L; senderProfile = SenderProfile(); b2bProfile = B2bProfile().edit { id = 1L }
            recipient = aRecipient { id = 5L }
        }
        whenever(invoiceRepository.findById(100L)).thenReturn(invoice)
        val field = profileField(B2bTerm.BT_58, B2bPlacement.RECIPIENT).edit { id = 77L; defaultValue = "profil@example.com" }
        profileRepository.givenFields(1L, listOf(field))

        val resolved = fieldValueService.resolvedGuidedFieldValues(100L)

        assertEquals("profil@example.com", resolved.getValue("BT-58").value)
        assertEquals(de.littletrotter.domain.reference.B2bFieldValueOrigin.DEFAULT, resolved.getValue("BT-58").origin)
    }

    @Test
    fun `resolvedGuidedFieldValues treats a blank invoice-level value like absent and falls through to the party value`() {
        val sender = aSenderProfile { id = 1L }
        val invoice = Invoice().edit { id = 100L; senderProfile = sender; b2bProfile = B2bProfile().edit { id = 1L } }
        whenever(invoiceRepository.findById(100L)).thenReturn(invoice)
        val field = profileField(B2bTerm.BT_58, B2bPlacement.SELLER).edit { id = 1L }
        profileRepository.givenFields(1L, listOf(field))
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 100L, B2bTerm.BT_58)).thenReturn(
            B2bFieldValue().edit { term = B2bTerm.BT_58; value = "   " },
        )
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.SENDER_PROFILE, 1L, B2bTerm.BT_58)).thenReturn(
            B2bFieldValue().edit { term = B2bTerm.BT_58; value = "anbieter@example.com" },
        )

        val resolved = fieldValueService.resolvedGuidedFieldValues(100L)

        assertEquals("anbieter@example.com", resolved.getValue("BT-58").value)
    }

    // ─── copyLineTemplateValuesToInvoiceItem ───────────────

    @Test
    fun `copyLineTemplateValuesToInvoiceItem copies article template values to invoice item`() {
        val invoice = Invoice().edit { id = 100L; b2bProfile = B2bProfile().edit { id = 1L } }
        whenever(invoiceRepository.findById(100L)).thenReturn(invoice)
        profileRepository.givenFields(1L,
            listOf(profileField(B2bTerm.BT_155, B2bPlacement.INVOICE).edit { id = 55L })
        )
        whenever(fieldValueRepository.findByOwner(B2bFieldOwnerType.ARTICLE, 200L)).thenReturn(
            listOf(B2bFieldValue().edit { ownerId = 200L; term = B2bTerm.BT_155; value = "ART-123" })
        )
        whenever(fieldValueRepository.findByOwners(B2bFieldOwnerType.INVOICE_ITEM, listOf(300L))).thenReturn(emptyList())

        fieldValueService.copyLineTemplateValuesToInvoiceItem(200L, 300L, 100L)

        verify(fieldValueRepository).findByOwner(B2bFieldOwnerType.ARTICLE, 200L)
    }

    @Test
    fun `copyLineTemplateValuesToInvoiceItem returns early when invoice has no profile`() {
        val invoice = Invoice().edit { id = 100L } // b2bProfile is null
        whenever(invoiceRepository.findById(100L)).thenReturn(invoice)

        fieldValueService.copyLineTemplateValuesToInvoiceItem(200L, 300L, 100L)

        verify(fieldValueRepository, never()).findByOwner(any(), any())
    }

    @Test
    fun `copyLineTemplateValuesToInvoiceItem returns early when profile has no LINE fields`() {
        val invoice = Invoice().edit { id = 100L; b2bProfile = B2bProfile().edit { id = 1L } }
        whenever(invoiceRepository.findById(100L)).thenReturn(invoice)
        profileRepository.givenFields(1L,
            listOf(profileField(B2bTerm.BT_58, B2bPlacement.INVOICE)) // HEADER, not LINE
        )

        fieldValueService.copyLineTemplateValuesToInvoiceItem(200L, 300L, 100L)

        verify(fieldValueRepository, never()).findByOwner(B2bFieldOwnerType.ARTICLE, 200L)
    }

    // ─── deleteProfile guard ────────────────────────────────────────

    @Test
    fun `deleteProfile rejects when profile is assigned to recipients`() {
        whenever(recipientRepository.findNamesByB2bProfileId(1L)).thenReturn(listOf("Nekar-Supermarkt", "Edeka X"))

        val exception = assertThrows(InvalidParameterException::class.java) {
            lifecycleService.deleteProfile(1L)
        }
        assertTrue(exception.message?.contains("Nekar-Supermarkt") ?: false)
        verify(profileRepository, never()).delete(any())
    }

    @Test
    fun `deleteProfile proceeds when no recipients are assigned`() {
        whenever(recipientRepository.findNamesByB2bProfileId(1L)).thenReturn(emptyList())

        lifecycleService.deleteProfile(1L)

        verify(profileRepository).delete(1L)
    }

    // ─── copy ───────────────────────────────────────────────────

    @Test
    fun `copy throws when source profile not found`() {
        whenever(profileRepository.findById(999L)).thenReturn(null)

        val exception = assertThrows(InvalidParameterException::class.java) {
            lifecycleService.copy(999L)
        }
        assertTrue(exception.message?.contains("999") ?: false)
    }

    @Test
    fun `copy creates a new profile with (Kopie) suffix`() {
        val source = B2bProfile().edit { id = 1L; name = "Original"; description = "Original Description"; active = false }
        profileRepository.givenFields(1L, emptyList())
        whenever(profileRepository.findById(1L)).thenReturn(source)
        whenever(profileRepository.findByName("Original (Kopie)")).thenReturn(null)
        whenever(profileRepository.save(any())).thenAnswer { it.arguments[0] }

        val copy = lifecycleService.copy(1L)

        assertEquals("Original (Kopie)", copy.name)
        assertEquals("Original Description", copy.description)
        assertTrue(copy.active, "copy must always be active")
    }

    @Test
    fun `copy deduplicates names when (Kopie) already exists`() {
        val source = B2bProfile().edit { id = 1L; name = "Original"; description = "Desc"; active = false }
        val existing = B2bProfile().edit { id = 2L; name = "Original (Kopie)" }
        profileRepository.givenFields(1L, emptyList())
        whenever(profileRepository.findById(1L)).thenReturn(source)
        whenever(profileRepository.findByName("Original (Kopie)")).thenReturn(existing)
        whenever(profileRepository.findByName("Original (Kopie 2)")).thenReturn(null)
        whenever(profileRepository.save(any())).thenAnswer { it.arguments[0] }

        val copy = lifecycleService.copy(1L)

        assertEquals("Original (Kopie 2)", copy.name)
    }

    @Test
    fun `copy preserves country scope`() {
        val source = B2bProfile().edit {
            id = 1L; name = "EU"; description = "EU Scope"; active = true
            countries = mutableSetOf(Country.FR, Country.DE)
        }
        profileRepository.givenFields(1L, emptyList())
        whenever(profileRepository.findById(1L)).thenReturn(source)
        whenever(profileRepository.findByName("EU (Kopie)")).thenReturn(null)
        whenever(profileRepository.save(any())).thenAnswer { it.arguments[0] }

        val copy = lifecycleService.copy(1L)

        assertEquals(setOf(Country.FR, Country.DE), copy.countries)
    }

    @Test
    fun `copy duplicates all fields from source`() {
        val source = B2bProfile().edit { id = 1L; name = "Original"; active = false }
        val sourceField1 = profileField(B2bTerm.BT_58, B2bPlacement.INVOICE).edit { id = 10L; defaultValue = "default1" }
        val sourceField2 = profileField(B2bTerm.BT_19, B2bPlacement.RECIPIENT).edit { id = 11L; defaultValue = null }
        profileRepository.givenFields(1L, listOf(sourceField1, sourceField2))
        whenever(profileRepository.findById(1L)).thenReturn(source)
        whenever(profileRepository.findByName("Original (Kopie)")).thenReturn(null)
        val savedCopy = B2bProfile().edit { id = 2L; name = "Original (Kopie)"; active = true }
        whenever(profileRepository.save(any())).thenReturn(savedCopy)

        lifecycleService.copy(1L)

        verify(profileRepository).save(argThat<B2bProfile> { fieldList.size == 2 })
    }

    // ─── missingMandatorySellerFields ──────────────────────────────────────

    @Test
    fun `missingMandatorySellerFields with null profileId returns empty list`() {
        val missing = fieldDefinitionService.missingMandatorySellerFields(null, mapOf("BT-19" to "value"))

        assertTrue(missing.isEmpty())
    }

    @Test
    fun `missingMandatorySellerFields identifies missing MANDATORY fields`() {
        val field1 = profileField(B2bTerm.BT_56, B2bPlacement.SELLER, B2bFieldRequirement.MANDATORY)
        val field2 = profileField(B2bTerm.BT_19, B2bPlacement.SELLER, B2bFieldRequirement.OPTIONAL)
        profileRepository.givenFields(1L, listOf(field1, field2))

        val missing = fieldDefinitionService.missingMandatorySellerFields(1L, mapOf("BT-19" to "provided"))

        assertEquals(listOf("Definition BT-56"), missing)
    }

    @Test
    fun `missingMandatorySellerFields ignores BOUND terms`() {
        val field = profileField(B2bTerm.BT_84, B2bPlacement.SELLER, B2bFieldRequirement.MANDATORY)
        profileRepository.givenFields(1L, listOf(field))

        val missing = fieldDefinitionService.missingMandatorySellerFields(1L, emptyMap())

        assertTrue(missing.isEmpty(), "BOUND terms should not appear in mandatory checks")
    }

    // ─── findSelectableCatalogEntries ──────────────────────────────────────

    @Test
    fun `findSelectableCatalogEntries with null profileId returns the full catalog`() {
        val selectable = fieldDefinitionService.findSelectableCatalogEntries(null)

        assertEquals(B2bTerm.entries.filter { it.catalogEligible }.toSet(), selectable.toSet())
    }

    @Test
    fun `findSelectableCatalogEntries excludes terms used by other fields`() {
        val used = profileField(B2bTerm.BT_19, B2bPlacement.INVOICE).edit { id = 10L }
        profileRepository.givenFields(1L, listOf(used))

        val selectable = fieldDefinitionService.findSelectableCatalogEntries(1L)

        assertTrue(B2bTerm.BT_19 !in selectable)
        assertEquals(B2bTerm.entries.count { it.catalogEligible } - 1, selectable.size)
    }

    @Test
    fun `findSelectableCatalogEntries includes the term being edited when currentFieldId is set`() {
        val field = profileField(B2bTerm.BT_19, B2bPlacement.INVOICE).edit { id = 10L }
        profileRepository.givenFields(1L, listOf(field))

        val selectable = fieldDefinitionService.findSelectableCatalogEntries(1L, currentFieldId = 10L)

        assertTrue(B2bTerm.BT_19 in selectable, "BT-19 must be selectable when editing field 10")
        assertEquals(B2bTerm.entries.count { it.catalogEligible }, selectable.size)
    }

    // ─── findInvoiceHeaderFields ────────────────────────────────────────────

    @Test
    fun `findInvoiceHeaderFields filters by HEADER level and FREE binding`() {
        val headerFree = profileField(B2bTerm.BT_58, B2bPlacement.INVOICE)
        val lineField = profileField(B2bTerm.BT_155, B2bPlacement.INVOICE)
        val headerBound = profileField(B2bTerm.BT_13, B2bPlacement.INVOICE)
        // A field left over from a since-removed catalog term (term == null) must be dropped,
        // not crash the null-safe level lookup.
        val orphanedField = profileField(null, B2bPlacement.INVOICE)
        profileRepository.givenFields(1L, listOf(headerFree, lineField, headerBound, orphanedField))

        val headers = fieldDefinitionService.findInvoiceHeaderFields(1L)

        assertEquals(1, headers.size)
        assertEquals(B2bTerm.BT_58, headers.single().term)
    }

    // ─── findInvoiceItemFields ─────────────────────────────────────────────

    @Test
    fun `findInvoiceItemFields filters by LINE level and FREE binding`() {
        val lineField = profileField(B2bTerm.BT_155, B2bPlacement.INVOICE)
        val headerField = profileField(B2bTerm.BT_58, B2bPlacement.INVOICE)
        // No matching catalog term at all (stale field, term removed from the catalog since) —
        // the null-safe level lookup must drop it rather than throw.
        val orphanedField = profileField(null, B2bPlacement.INVOICE)
        profileRepository.givenFields(1L, listOf(lineField, headerField, orphanedField))

        val items = fieldDefinitionService.findInvoiceItemFields(1L)

        assertEquals(1, items.size)
        assertEquals(B2bTerm.BT_155, items.single().term)
    }

    @Test
    fun `findFieldValues keeps only rows with a resolvable catalog term`() {
        whenever(fieldValueRepository.findByOwner(B2bFieldOwnerType.INVOICE, 100L)).thenReturn(
            listOf(
                B2bFieldValue().edit { ownerType = B2bFieldOwnerType.INVOICE; ownerId = 100L; term = B2bTerm.BT_58; value = "kunde@example.com" },
                // A row whose term is gone must be dropped, not crash the mapping.
                B2bFieldValue().edit { ownerType = B2bFieldOwnerType.INVOICE; ownerId = 100L; term = null; value = "orphan" },
            ),
        )

        val values = fieldValueService.findFieldValues(B2bFieldOwnerType.INVOICE, 100L)

        assertEquals(mapOf("BT-58" to "kunde@example.com"), values)
    }

    @Test
    fun `findFieldValuesByOwners groups per owner and skips a row without a term`() {
        whenever(fieldValueRepository.findByOwners(B2bFieldOwnerType.INVOICE_ITEM, listOf(1L, 2L, 3L))).thenReturn(
            listOf(
                B2bFieldValue().edit { ownerType = B2bFieldOwnerType.INVOICE_ITEM; ownerId = 1L; term = B2bTerm.BT_155; value = "SELLER-1" },
                B2bFieldValue().edit { ownerType = B2bFieldOwnerType.INVOICE_ITEM; ownerId = 1L; term = B2bTerm.BT_156; value = "BUYER-1" },
                // A row whose term is gone must be dropped, not crash the whole page mapping.
                B2bFieldValue().edit { ownerType = B2bFieldOwnerType.INVOICE_ITEM; ownerId = 2L; term = null; value = "orphan" },
            ),
        )

        val byOwner = fieldValueService.findFieldValuesByOwners(B2bFieldOwnerType.INVOICE_ITEM, listOf(1L, 2L, 3L))

        assertEquals(mapOf("BT-155" to "SELLER-1", "BT-156" to "BUYER-1"), byOwner[1L])
        assertTrue(byOwner.getValue(2L).isEmpty(), "the orphaned row contributes no entry")
        assertTrue(3L !in byOwner, "an owner without stored values is absent from the result")
    }

    // ─── thin pass-through query methods, no dedicated test elsewhere ──────────────────

    @Test
    fun `boundary - findProfiles delegates paging, filter and sort to the repository`() {
        val profile = B2bProfile().edit { id = 1L; name = "Standard" }
        org.mockito.kotlin.whenever(profileRepository.findAll(0, 60, "Std", de.littletrotter.domain.shared.SortDirection.ASC)).thenReturn(listOf(profile))

        assertEquals(listOf(profile), lifecycleService.findProfiles(filterText = "Std"))
    }

    @Test
    fun `boundary - countProfiles and countInactiveProfiles delegate to the repository`() {
        org.mockito.kotlin.whenever(profileRepository.count(null)).thenReturn(3L)
        org.mockito.kotlin.whenever(profileRepository.countInactive(null)).thenReturn(1L)

        assertEquals(3L, lifecycleService.countProfiles())
        assertEquals(1L, lifecycleService.countInactiveProfiles())
    }

    @Test
    fun `boundary - findProfileById and findAllActiveProfiles delegate to the repository`() {
        val profile = B2bProfile().edit { id = 5L; name = "Aktiv" }
        org.mockito.kotlin.whenever(profileRepository.findById(5L)).thenReturn(profile)
        org.mockito.kotlin.whenever(profileRepository.findAllActive()).thenReturn(listOf(profile))

        assertEquals(profile, lifecycleService.findProfileById(5L))
        assertEquals(listOf(profile), lifecycleService.findAllActiveProfiles())
    }

    @Test
    fun `boundary - usageNames delegates to the recipient repository`() {
        org.mockito.kotlin.whenever(recipientRepository.findNamesByB2bProfileId(5L)).thenReturn(listOf("Kunde A", "Kunde B"))

        assertEquals(listOf("Kunde A", "Kunde B"), usageService.usageNames(5L))
    }

    @Test
    fun `boundary - deleteField drops the field from its owning profile`() {
        val field = profileField(B2bTerm.BT_58, B2bPlacement.INVOICE).edit { id = 9L }
        val owner = B2bProfile().edit { id = 1L }.also { it.defineField(field) }
        whenever(profileRepository.findByFieldId(9L)).thenReturn(owner)

        fieldDefinitionService.deleteField(9L)

        assertTrue(owner.fieldList.isEmpty())
        org.mockito.kotlin.verify(profileRepository).saveAndFlush(owner)
    }

    @Test
    fun `boundary - deleteProfile rejects a profile still individually assigned to an invoice`() {
        // recipientRepository.findNamesByB2bProfileId defaults to an empty list (mockito-kotlin
        // mock default), so the recipient-assignment guard above this one passes through.
        org.mockito.kotlin.whenever(invoiceLookup.existsByB2bProfileId(5L)).thenReturn(true)

        assertThrows(InvalidParameterException::class.java) { lifecycleService.deleteProfile(5L) }
        org.mockito.kotlin.verify(profileRepository, org.mockito.kotlin.never()).delete(5L)
    }
}
