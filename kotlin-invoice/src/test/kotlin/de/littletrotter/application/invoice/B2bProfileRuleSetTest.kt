package de.littletrotter.application.invoice

import de.littletrotter.domain.compliance.ComplianceLevel
import de.littletrotter.domain.compliance.ComplianceSubject
import de.littletrotter.domain.compliance.FindingTarget
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bFieldValue
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.application.reference.B2bPartyFieldValues
import de.littletrotter.domain.reference.RecipientB2bFieldValue
import de.littletrotter.domain.repository.B2bFieldValueRepository
import de.littletrotter.domain.repository.B2bProfileFieldRepository
import de.littletrotter.domain.repository.RecipientB2bFieldValueRepository
import de.littletrotter.domain.value.Iban
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.test.FIXED_TODAY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [B2bProfileRuleSet]: does it turn a profile + entity/`b2b_field_value` state
 * into the right [B2bProfileRules] input? [B2bProfileRulesTest] already covers the
 * mandatory/conditional *logic* in isolation; this file is only about the resolution this adapter
 * adds on top — `BOUND` vs `FREE`, header vs line, and the "no profile" short-circuit.
 *
 * Fixtures now pick real [B2bTerm] constants instead of hand-rolled catalog entries with
 * arbitrary (level, binding, boundProperty) combinations. Every catalog-eligible [B2bTerm]
 * (`catalogEligible == true`, the only ones [de.littletrotter.application.reference
 * .B2bProfileService.saveField] ever lets into a real profile) is HEADER/SELLER_PARTY/LINE —
 * there is no BUYER_PARTY or SELLER_PARTY+FREE catalog term today, so a handful of
 * level×binding combinations this file used to synthesize freely (a BOUND BUYER_PARTY term, a
 * FREE SELLER_PARTY term) can no longer be constructed even as a raw in-memory fixture in a way
 * that matches real data; the corresponding branches in [B2bProfileRuleSet] (documented at each
 * deletion below) are exhaustiveness/future-proofing, not currently reachable production code.
 */
class B2bProfileRuleSetTest {

    private val profileFieldRepository = mock<B2bProfileFieldRepository>()
    private val fieldValueRepository = mock<B2bFieldValueRepository>()
    private val recipientFieldValueRepository = mock<RecipientB2bFieldValueRepository>()
    private val partyFieldValues = B2bPartyFieldValues(fieldValueRepository, recipientFieldValueRepository)
    private val ruleSet = B2bProfileRuleSet(profileFieldRepository, fieldValueRepository, partyFieldValues)

    private fun profileField(
        term: B2bTerm,
        requirement: B2bFieldRequirement,
        placement: B2bPlacement = B2bPlacement.INVOICE,
        conditionTerm: B2bTerm? = null,
        definition: String = "Definition of ${term.officialCode}",
    ) = B2bProfileField().apply {
        this.term = term
        this.requirement = requirement
        this.placement = placement
        this.conditionTerm = conditionTerm
        this.businessDefinition = definition
    }

    private fun sender(id: Long = 1L) = SenderProfile().apply {
        this.id = id
        name = "Anbieter GmbH"
        invoicePrefix = "AN-"
    }

    /** The B2B profile now anchors on the invoice itself, not the seller (decision 12);
     *  [profileId] defaults to `10L` to match the earlier fixture value every test below still
     *  stubs its repositories against. */
    private fun invoiceWithSeller(seller: SenderProfile, id: Long = 100L, profileId: Long? = 10L): Invoice = Invoice().apply {
        this.id = id
        senderProfile = seller
        b2bProfile = profileId?.let { B2bProfile().apply { this.id = it; name = "Standard" } }
    }

    // ─── no profile -> empty list ────────────────────────────────────────────

    @Test
    fun `no B2B profile assigned to the invoice yields an empty list without touching a repository`() {
        val invoice = Invoice().apply { id = 1L; senderProfile = SenderProfile().apply { id = 1L } }

        val findings = ruleSet.evaluate(invoice)

        assertTrue(findings.isEmpty())
        org.mockito.kotlin.verifyNoInteractions(profileFieldRepository)
    }

    @Test
    fun `an invoice without any seller yields an empty list`() {
        assertTrue(ruleSet.evaluate(Invoice().apply { id = 1L }).isEmpty())
    }

    // ─── BOUND: header/seller ──────────────────────────────────────────────────

    @Test
    fun `a missing BOUND header field is reported`() {
        val seller = sender()
        val invoice = invoiceWithSeller(seller) // purchaseOrderReference stays null

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_13, B2bFieldRequirement.MANDATORY)),
        )

        val findings = ruleSet.evaluate(invoice)

        assertEquals(1, findings.size)
        assertEquals(FindingTarget.Header, findings.single().target)
        assertEquals(listOf(B2bTerm.BT_13), findings.single().businessTerms)
    }

    @Test
    fun `a present BOUND header field is not reported`() {
        val seller = sender()
        val invoice = invoiceWithSeller(seller).apply { references.purchaseOrderReference = "PO-4711" }

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_13, B2bFieldRequirement.MANDATORY)),
        )

        assertTrue(ruleSet.evaluate(invoice).isEmpty())
    }

    @Test
    fun `a BOUND seller-party value wrapper (Iban) is read through its value property`() {
        val seller = sender().apply { iban = Iban.ofNullable("DE02120300000000202051") }
        val invoice = invoiceWithSeller(seller)

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            // SELLER placement is out of scope for this rule set — INVOICE keeps it in scope here too.
            listOf(profileField(B2bTerm.BT_84, B2bFieldRequirement.MANDATORY, placement = B2bPlacement.INVOICE)),
        )

        assertTrue(ruleSet.evaluate(invoice).isEmpty())
    }

    @Test
    fun `a BOUND SELLER-placed term (BT-84 IBAN) stays out of scope for this rule set - F5`() {
        // fix-einvoice-guided-profile-party-values (F5) — only a FREE SELLER-placed term now
        // participates (see the tests further below): BT-84 gets no widget in the guided step
        // either (B2bProfileFieldsSection's FREE-only filter), and its own bound property is
        // already enforced by SenderProfile.validate(), not this rule set.
        val seller = sender()
        val invoice = invoiceWithSeller(seller) // no IBAN -> would fail if in scope

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_84, B2bFieldRequirement.MANDATORY, placement = B2bPlacement.SELLER)),
        )

        assertTrue(ruleSet.evaluate(invoice).isEmpty(), "SELLER-Placement gehört dem Anbieterformular, nicht dieser Regel")
    }

    // ─── BOUND: BT-11 — the resolved projectNumber overlap ─────────────────────────────────────

    /**
     * BT-11 is `BOUND` on `projectNumber`, resolved through the same
     * `Invoice.references` reflection fallback as BT-13/BT-72/BT-73/BT-74 (see
     * `B2bProfileRuleSet.readBoundProperty`): a profile can mark it `MANDATORY` and the rule set
     * reads the value straight off the property, no `b2b_field_value` row, no second input field.
     */
    @Test
    fun `BT-11 MANDATORY is reported missing without a captured project reference`() {
        val seller = sender()
        val invoice = invoiceWithSeller(seller) // references.projectNumber stays null

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_11, B2bFieldRequirement.MANDATORY)),
        )

        val findings = ruleSet.evaluate(invoice)

        assertEquals(1, findings.size)
        assertEquals(FindingTarget.Header, findings.single().target)
        assertEquals(listOf(B2bTerm.BT_11), findings.single().businessTerms)
    }

    @Test
    fun `BT-11 MANDATORY is satisfied once a project reference is captured`() {
        val seller = sender()
        val invoice = invoiceWithSeller(seller).apply { references.projectNumber = "PRJ-2026-0099" }

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_11, B2bFieldRequirement.MANDATORY)),
        )

        assertTrue(ruleSet.evaluate(invoice).isEmpty())
    }

    // ─── Representative newly BOUND terms (one header, one seller party, one line) ─────────────

    /** BT-10 — HEADER, resolved through the same `Invoice.references` nested fallback as BT-11/
     *  BT-13/BT-72/BT-73/BT-74, now for a term that was a reference term before it became BOUND. */
    @Test
    fun `BT-10 (buyer reference) MANDATORY is reported missing without a captured value`() {
        val invoice = invoiceWithSeller(sender()) // references.buyerReference stays null

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_10, B2bFieldRequirement.MANDATORY)),
        )

        val findings = ruleSet.evaluate(invoice)
        assertEquals(1, findings.size)
        assertEquals(FindingTarget.Header, findings.single().target)
        assertEquals(listOf(B2bTerm.BT_10), findings.single().businessTerms)
    }

    @Test
    fun `BT-10 (buyer reference) MANDATORY is satisfied once captured`() {
        val invoice = invoiceWithSeller(sender()).apply { references.buyerReference = "04011000-1234512345-06" }

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_10, B2bFieldRequirement.MANDATORY)),
        )

        assertTrue(ruleSet.evaluate(invoice).isEmpty())
    }

    /** BT-27 — SELLER_PARTY, resolved through `invoice.effectiveSender()`. */
    @Test
    fun `BT-27 (seller name) MANDATORY is satisfied by the existing sender name`() {
        val invoice = invoiceWithSeller(sender()) // sender().name = "Anbieter GmbH"

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_27, B2bFieldRequirement.MANDATORY)),
        )

        assertTrue(ruleSet.evaluate(invoice).isEmpty())
    }

    @Test
    fun `BT-27 (seller name) MANDATORY is reported missing for a blank sender name`() {
        val invoice = invoiceWithSeller(sender().apply { name = "" })

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_27, B2bFieldRequirement.MANDATORY)),
        )

        val findings = ruleSet.evaluate(invoice)
        assertEquals(1, findings.size)
        assertEquals(FindingTarget.Seller, findings.single().target)
    }

    /** BT-153 — LINE, resolved off `InvoiceItem.description`. */
    @Test
    fun `BT-153 (item name) MANDATORY is satisfied by every line's existing description`() {
        val invoice = invoiceWithSeller(sender(), id = 42L)
        invoice.addItem(InvoiceItem().apply { positionId = "1"; description = "Beratung"; id = 5L })

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_153, B2bFieldRequirement.MANDATORY)),
        )

        assertTrue(ruleSet.evaluate(invoice).isEmpty())
    }

    // ─── FREE: header ───────────────────────────────────────────────────────────

    @Test
    fun `a missing FREE header field is resolved via the field-value repository`() {
        val seller = sender()
        val invoice = invoiceWithSeller(seller, id = 42L)

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_19, B2bFieldRequirement.MANDATORY)),
        )
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 42L, B2bTerm.BT_19)).thenReturn(null)

        val findings = ruleSet.evaluate(invoice)

        assertEquals(1, findings.size)
        assertEquals("Definition of BT-19", findings.single().args[1])
    }

    @Test
    fun `a present FREE header field is not reported`() {
        val seller = sender()
        val invoice = invoiceWithSeller(seller, id = 42L)

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_19, B2bFieldRequirement.MANDATORY)),
        )
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 42L, B2bTerm.BT_19))
            .thenReturn(B2bFieldValue().apply { ownerType = B2bFieldOwnerType.INVOICE; ownerId = 42L; term = B2bTerm.BT_19; value = "K-4711" })

        assertTrue(ruleSet.evaluate(invoice).isEmpty())
    }

    // ─── RECIPIENT placement resolution (invoice > recipient > profile default) ────────────────

    /** [invoiceWithSeller] plus a recipient id — the RECIPIENT-placement resolution chain needs
     *  one to look up a `recipient_b2b_field_value` row against. */
    private fun invoiceWithRecipient(seller: SenderProfile, recipientId: Long, id: Long = 42L, profileId: Long? = 10L): Invoice =
        invoiceWithSeller(seller, id, profileId).apply {
            recipient = Recipient().apply { this.id = recipientId }
        }

    @Test
    fun `a RECIPIENT-placed field with no invoice, recipient or default value is reported missing`() {
        val invoice = invoiceWithRecipient(sender(), recipientId = 5L)

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_58, B2bFieldRequirement.MANDATORY, placement = B2bPlacement.RECIPIENT).apply { id = 77L }),
        )
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 42L, B2bTerm.BT_58)).thenReturn(null)
        whenever(recipientFieldValueRepository.findByRecipientIdAndProfileFieldId(5L, 77L)).thenReturn(null)

        val findings = ruleSet.evaluate(invoice)

        assertEquals(1, findings.size)
        assertEquals(listOf(B2bTerm.BT_58), findings.single().businessTerms)
    }

    @Test
    fun `a RECIPIENT-placed field falls back to the recipient's own stored value`() {
        val invoice = invoiceWithRecipient(sender(), recipientId = 5L)

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_58, B2bFieldRequirement.MANDATORY, placement = B2bPlacement.RECIPIENT).apply { id = 77L }),
        )
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 42L, B2bTerm.BT_58)).thenReturn(null)
        whenever(recipientFieldValueRepository.findByRecipientIdAndProfileFieldId(5L, 77L)).thenReturn(
            RecipientB2bFieldValue().apply { recipientId = 5L; profileFieldId = 77L; value = "kunde@example.com" },
        )

        assertTrue(ruleSet.evaluate(invoice).isEmpty(), "the recipient's own value satisfies the requirement")
    }

    @Test
    fun `a RECIPIENT-placed field falls back to the profile default once neither invoice nor recipient carry a value`() {
        val invoice = invoiceWithRecipient(sender(), recipientId = 5L)

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(
                profileField(B2bTerm.BT_58, B2bFieldRequirement.MANDATORY, placement = B2bPlacement.RECIPIENT).apply {
                    id = 77L
                    defaultValue = "rechnung@nekar-supermarkt.de"
                },
            ),
        )
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 42L, B2bTerm.BT_58)).thenReturn(null)
        whenever(recipientFieldValueRepository.findByRecipientIdAndProfileFieldId(5L, 77L)).thenReturn(null)

        assertTrue(ruleSet.evaluate(invoice).isEmpty(), "the profile default is the last resolution tier")
    }

    @Test
    fun `a RECIPIENT-placed field's stored invoice value wins over both the recipient value and the profile default`() {
        val invoice = invoiceWithRecipient(sender(), recipientId = 5L)

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(
                profileField(B2bTerm.BT_58, B2bFieldRequirement.MANDATORY, placement = B2bPlacement.RECIPIENT).apply {
                    id = 77L
                    defaultValue = "profil@example.com"
                },
            ),
        )
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 42L, B2bTerm.BT_58))
            .thenReturn(B2bFieldValue().apply { ownerType = B2bFieldOwnerType.INVOICE; ownerId = 42L; term = B2bTerm.BT_58; value = "rechnung@example.com" })

        assertTrue(ruleSet.evaluate(invoice).isEmpty())
        // the recipient repository is never even consulted once the invoice-level value already resolves it
        org.mockito.kotlin.verify(recipientFieldValueRepository, org.mockito.kotlin.never())
            .findByRecipientIdAndProfileFieldId(org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    // ─── fix-einvoice-guided-profile-party-values (F5) — a FREE SELLER-placed field now resolves
    // through the same invoice > party > default chain, once the guided step 02 makes an
    // invoice-level override of it possible. A BOUND SELLER-placed term (BT-84) stays out of
    // scope entirely, see the test above.

    @Test
    fun `a FREE SELLER-placed field with no invoice, seller or default value is reported missing`() {
        val invoice = invoiceWithSeller(sender(id = 1L))

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_58, B2bFieldRequirement.MANDATORY, placement = B2bPlacement.SELLER).apply { id = 88L }),
        )
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 100L, B2bTerm.BT_58)).thenReturn(null)
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.SENDER_PROFILE, 1L, B2bTerm.BT_58)).thenReturn(null)

        val findings = ruleSet.evaluate(invoice)

        assertEquals(1, findings.size)
        assertEquals(listOf(B2bTerm.BT_58), findings.single().businessTerms)
    }

    @Test
    fun `a FREE SELLER-placed field falls back to the seller's own stored SENDER_PROFILE value`() {
        val invoice = invoiceWithSeller(sender(id = 1L))

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_58, B2bFieldRequirement.MANDATORY, placement = B2bPlacement.SELLER).apply { id = 88L }),
        )
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 100L, B2bTerm.BT_58)).thenReturn(null)
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.SENDER_PROFILE, 1L, B2bTerm.BT_58)).thenReturn(
            B2bFieldValue().apply { ownerType = B2bFieldOwnerType.SENDER_PROFILE; ownerId = 1L; term = B2bTerm.BT_58; value = "anbieter@example.com" },
        )

        assertTrue(ruleSet.evaluate(invoice).isEmpty(), "the seller's own captured value satisfies the requirement")
    }

    @Test
    fun `a FREE SELLER-placed field falls back to the profile default once neither invoice nor seller carry a value`() {
        val invoice = invoiceWithSeller(sender(id = 1L))

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(
                profileField(B2bTerm.BT_58, B2bFieldRequirement.MANDATORY, placement = B2bPlacement.SELLER).apply {
                    id = 88L
                    defaultValue = "profil@example.com"
                },
            ),
        )
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 100L, B2bTerm.BT_58)).thenReturn(null)
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.SENDER_PROFILE, 1L, B2bTerm.BT_58)).thenReturn(null)

        assertTrue(ruleSet.evaluate(invoice).isEmpty(), "the profile default is the last resolution tier")
    }

    @Test
    fun `a FREE SELLER-placed field's stored invoice value wins over both the seller's value and the profile default`() {
        val invoice = invoiceWithSeller(sender(id = 1L))

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(
                profileField(B2bTerm.BT_58, B2bFieldRequirement.MANDATORY, placement = B2bPlacement.SELLER).apply {
                    id = 88L
                    defaultValue = "profil@example.com"
                },
            ),
        )
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 100L, B2bTerm.BT_58))
            .thenReturn(B2bFieldValue().apply { ownerType = B2bFieldOwnerType.INVOICE; ownerId = 100L; term = B2bTerm.BT_58; value = "rechnung@example.com" })

        assertTrue(ruleSet.evaluate(invoice).isEmpty())
        // the seller's own SENDER_PROFILE value is never even consulted once the invoice-level
        // value already resolves it.
        org.mockito.kotlin.verify(fieldValueRepository, org.mockito.kotlin.never())
            .findByOwnerAndBtCode(B2bFieldOwnerType.SENDER_PROFILE, 1L, B2bTerm.BT_58)
    }

    // ─── A defaulted MANDATORY field passes the finalization gate ───────────────
    //
    // T5 point 5: "no change needed — a defaulted MANDATORY field passes because the value is
    // present like any user-entered one". This rule set never reads B2bProfileField.defaultValue
    // at all (defaults only ever affect a *new* widget's initial UI state, applied by
    // B2bProfileFieldsSection.applyDefaults, never the stored value) — proven here by evaluating a
    // MANDATORY field that carries a default once the accepted-default value has actually been
    // saved into b2b_field_value, exactly as if the user had typed it themselves.

    @Test
    fun `a MANDATORY FREE field with a default value still passes once that value is saved`() {
        val seller = sender()
        val invoice = invoiceWithSeller(seller, id = 42L)

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_58, B2bFieldRequirement.MANDATORY).apply { defaultValue = "rechnung@nekar-supermarkt.de" }),
        )
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 42L, B2bTerm.BT_58))
            .thenReturn(B2bFieldValue().apply {
                ownerType = B2bFieldOwnerType.INVOICE
                ownerId = 42L
                term = B2bTerm.BT_58
                value = "rechnung@nekar-supermarkt.de"
            })

        assertTrue(ruleSet.evaluate(invoice).isEmpty(), "the accepted default counts exactly like a manually entered value")
    }

    @Test
    fun `a MANDATORY FREE field with a default value is still reported missing before it is saved`() {
        // The default only ever pre-fills the widget — it is never itself a stored value, so a
        // profile field carrying a defaultValue but no actual b2b_field_value row yet still fails
        // finalization, same as any other MANDATORY FREE field never filled in.
        val seller = sender()
        val invoice = invoiceWithSeller(seller, id = 42L)

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_58, B2bFieldRequirement.MANDATORY).apply { defaultValue = "rechnung@nekar-supermarkt.de" }),
        )
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 42L, B2bTerm.BT_58)).thenReturn(null)

        val findings = ruleSet.evaluate(invoice)
        assertEquals(1, findings.size)
        assertEquals(listOf(B2bTerm.BT_58), findings.single().businessTerms)
    }

    // ─── LINE: per position ──────────────────────────────────────────────────────

    @Test
    fun `LINE fields are evaluated per position, targeting the right position`() {
        val seller = sender()
        val invoice = invoiceWithSeller(seller, id = 42L)
        val complete = InvoiceItem().apply { positionId = "1"; description = "A" }
        val incomplete = InvoiceItem().apply { positionId = "2"; description = "B" }
        invoice.addItem(complete)
        invoice.addItem(incomplete)

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_155, B2bFieldRequirement.MANDATORY)),
        )
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE_ITEM, 1L, B2bTerm.BT_155))
            .thenReturn(B2bFieldValue().apply { ownerType = B2bFieldOwnerType.INVOICE_ITEM; ownerId = 1L; term = B2bTerm.BT_155; value = "ART-1" })
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE_ITEM, 2L, B2bTerm.BT_155)).thenReturn(null)
        complete.id = 1L
        incomplete.id = 2L

        val findings = ruleSet.evaluate(invoice)

        assertEquals(1, findings.size)
        assertEquals(FindingTarget.Position("2"), findings.single().target)
    }

    @Test
    fun `a LINE field whose item has no id yet (unsaved position) is reported as missing, not resolved`() {
        val seller = sender()
        val invoice = invoiceWithSeller(seller, id = 42L)
        invoice.addItem(InvoiceItem().apply { positionId = "1"; description = "A" }) // no id

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_155, B2bFieldRequirement.MANDATORY)),
        )

        val findings = ruleSet.evaluate(invoice)

        assertEquals(1, findings.size)
        assertEquals(FindingTarget.Position("1"), findings.single().target)
    }

    // ─── Conditional requirement across levels ────────────────────────────────────

    @Test
    fun `a LINE field conditioned on a HEADER field resolves correctly per profile data`() {
        val seller = sender()
        val invoice = invoiceWithSeller(seller, id = 42L).apply { references.purchaseOrderReference = "PO-1" }
        val item = InvoiceItem().apply { positionId = "1"; description = "A"; id = 5L }
        invoice.addItem(item)

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(
                profileField(B2bTerm.BT_13, B2bFieldRequirement.OPTIONAL),
                profileField(B2bTerm.BT_155, B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_13),
            ),
        )
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE_ITEM, 5L, B2bTerm.BT_155)).thenReturn(null)

        val findings = ruleSet.evaluate(invoice)

        assertEquals(1, findings.size, "BT-13 ist gesetzt -> BT-155 ist Pflicht: $findings")
        assertEquals(FindingTarget.Position("1"), findings.single().target)
    }

    // ─── PROFIL-1 — the condition on BT-13 now actually behaves conditionally ────────

    /**
     * In the shipped "B2B-Standard" profile, BT-155/BT-156 are `CONDITIONAL` with BT-13 as
     * trigger. As long as BT-13 was bound to `buyerReference` (BT-10), the condition was
     * **always** satisfied at check time: `ensureBuyerReferenceForFinalization()` enforces
     * BT-10 before every finalization (BR-DE-15) and fills it from the Leitweg-ID if necessary.
     * The two terms therefore behaved like `MANDATORY`, and `CONDITIONAL` promised something
     * that never actually happened.
     *
     * The two cases below are deliberately twins: **the same** catalog, **the same** profile,
     * **the same** Leitweg-ID set -- the only difference is the captured purchase order number.
     * Only this way is it proven that the outcome hinges on BT-13, not on anything else in the
     * setup.
     */
    private fun profil1Setup(invoice: Invoice) {
        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(
                profileField(B2bTerm.BT_13, B2bFieldRequirement.OPTIONAL),
                profileField(B2bTerm.BT_155, B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_13),
                profileField(B2bTerm.BT_156, B2bFieldRequirement.CONDITIONAL, conditionTerm = B2bTerm.BT_13),
            ),
        )
        val itemId = invoice.itemList.single().id!!
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE_ITEM, itemId, B2bTerm.BT_155)).thenReturn(null)
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE_ITEM, itemId, B2bTerm.BT_156)).thenReturn(null)
    }

    private fun profil1Invoice(purchaseOrderReference: String?): Invoice =
        invoiceWithSeller(sender(), id = 42L).apply {
            // A public-sector buyer's Leitweg-ID, set in both cases.
            references.buyerReference = "04011000-1234512345-06"
            this.references.purchaseOrderReference = purchaseOrderReference
            addItem(InvoiceItem().apply { positionId = "1"; description = "A"; id = 5L })
        }

    @Test
    fun `PROFIL-1 - with a purchase order reference captured, BT-155 and BT-156 are required`() {
        val invoice = profil1Invoice(purchaseOrderReference = "BST-2026-0042")
        profil1Setup(invoice)

        val findings = ruleSet.evaluate(invoice)

        assertEquals(
            listOf("BT-155", "BT-156"),
            findings.flatMap { it.businessTerms }.map { it.officialCode }.sorted(),
            "BT-13 ist erfasst -> beide Positionsterme sind Pflicht: $findings",
        )
        findings.forEach { assertEquals(FindingTarget.Position("1"), it.target) }
    }

    @Test
    fun `PROFIL-1 - without one, they are not required even though the Leitweg-ID is set`() {
        val invoice = profil1Invoice(purchaseOrderReference = null)
        profil1Setup(invoice)

        val findings = ruleSet.evaluate(invoice)

        assertTrue(
            findings.isEmpty(),
            "Ohne erfasste Bestellnummer darf die Bedingung nicht greifen — auch nicht, weil BT-10 " +
                "(Leitweg-ID) gesetzt ist. Befunde: $findings",
        )
    }

    // ─── CONDITIONAL_IF_EMPTY through the real BOUND resolution ─────────────────
    //
    // The Nekar either-or pair: BT-72 (header, bound to Invoice.deliveryDate) required unless
    // BT-134 (line, bound to InvoiceItem.servicePeriodFrom) is filled on every line, and vice
    // versa. [B2bProfileRulesTest] already covers the pure predicate on synthetic B2bFieldState —
    // this exercises the same case through the real BOUND-property resolution this adapter adds.

    private fun nekarPeriodXorSetup() {
        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(
                profileField(B2bTerm.BT_72, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, conditionTerm = B2bTerm.BT_134),
                profileField(B2bTerm.BT_134, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, conditionTerm = B2bTerm.BT_72),
            ),
        )
    }

    @Test
    fun `neither BT-72 nor the line period is filled - both are reported missing`() {
        val invoice = invoiceWithSeller(sender(), id = 42L)
        invoice.addItem(InvoiceItem().apply { positionId = "1"; description = "A"; id = 5L })
        nekarPeriodXorSetup()

        val findings = ruleSet.evaluate(invoice)

        assertEquals(listOf("BT-134", "BT-72"), findings.map { it.businessTerms.first().officialCode }.sorted())
    }

    @Test
    fun `BT-72 filled, no line period - the either-or pair is already satisfied, no findings`() {
        val invoice = invoiceWithSeller(sender(), id = 42L).apply { deliveryDate = java.time.LocalDate.of(2026, 8, 25) }
        invoice.addItem(InvoiceItem().apply { positionId = "1"; description = "A"; id = 5L })
        nekarPeriodXorSetup()

        assertTrue(ruleSet.evaluate(invoice).isEmpty(), "filling BT-72 alone satisfies the XOR pair")
    }

    @Test
    fun `line period filled on every line, no BT-72 - also already satisfied, no findings`() {
        val invoice = invoiceWithSeller(sender(), id = 42L)
        invoice.addItem(InvoiceItem().apply { positionId = "1"; description = "A"; id = 5L; servicePeriodFrom = java.time.LocalDate.of(2026, 8, 1) })
        nekarPeriodXorSetup()

        assertTrue(ruleSet.evaluate(invoice).isEmpty(), "filling every line's BT-134 alone satisfies the XOR pair")
    }

    @Test
    fun `both BT-72 and the line period are filled - neither is required`() {
        val invoice = invoiceWithSeller(sender(), id = 42L).apply { deliveryDate = java.time.LocalDate.of(2026, 8, 25) }
        invoice.addItem(InvoiceItem().apply { positionId = "1"; description = "A"; id = 5L; servicePeriodFrom = java.time.LocalDate.of(2026, 8, 1) })
        nekarPeriodXorSetup()

        assertTrue(ruleSet.evaluate(invoice).isEmpty())
    }

    @Test
    fun `no BT-72, line period filled on one line but missing on another - both terms surface`() {
        // BT-72's own condition reads BT-134 as "empty on at least one line" -> BT-72 required.
        // The incomplete line's own BT-134 is missing while its trigger (BT-72) is also empty ->
        // that line's BT-134 is required too.
        val invoice = invoiceWithSeller(sender(), id = 42L)
        invoice.addItem(InvoiceItem().apply { positionId = "1"; description = "A"; id = 5L; servicePeriodFrom = java.time.LocalDate.of(2026, 8, 1) })
        invoice.addItem(InvoiceItem().apply { positionId = "2"; description = "B"; id = 6L })
        nekarPeriodXorSetup()

        val findings = ruleSet.evaluate(invoice)

        assertEquals(2, findings.size)
        assertTrue(findings.any { it.target == FindingTarget.Header && it.businessTerms.first() == B2bTerm.BT_72 })
        assertTrue(findings.any { it.target == FindingTarget.Position("2") && it.businessTerms.first() == B2bTerm.BT_134 })
    }

    // ─── D-P1 — cross-level XOR pin: BT-19 (HEADER) / BT-133 (LINE) degenerates to MANDATORY ────
    //
    // Companion to Ep6ConfigRejectionTest.NekarBt19Bt133CrossLevelXorPin (finding D-P1-F): BT-133
    // is a pure reference term (binding == null), so B2bProfileRuleSet.hasValue short-circuits to
    // `false` for it unconditionally — a CONDITIONAL_IF_EMPTY on BT-19 triggered by BT-133 can
    // therefore never see its trigger "filled", regardless of what (if anything) is stored for
    // BT-133 on any line. The pair never behaves like an XOR at runtime; BT-19 ends up required
    // exactly as if it had been declared plain MANDATORY.

    private fun nekarBt19Bt133Setup(bt133AsSibling: Boolean) {
        val fields = buildList {
            add(profileField(B2bTerm.BT_19, B2bFieldRequirement.CONDITIONAL_IF_EMPTY, conditionTerm = B2bTerm.BT_133))
            if (bt133AsSibling) add(profileField(B2bTerm.BT_133, B2bFieldRequirement.OPTIONAL))
        }
        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(fields)
    }

    @Test
    fun `D-P1-F - BT-19 is reported required even though BT-133 has a stored value on every line`() {
        val invoice = invoiceWithSeller(sender(), id = 42L)
        val item = InvoiceItem().apply { positionId = "1"; description = "A"; id = 5L }
        invoice.addItem(item)
        nekarBt19Bt133Setup(bt133AsSibling = true)
        // A value genuinely exists for BT-133 on this line — if the pair behaved like the BT-72/
        // BT-134 XOR above, this alone would satisfy BT-19's condition and produce no finding.
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE_ITEM, 5L, B2bTerm.BT_133))
            .thenReturn(B2bFieldValue().apply { value = "KST-4711" })

        val findings = ruleSet.evaluate(invoice)

        assertEquals(1, findings.size, "BT-133's stored value must NOT satisfy the condition: $findings")
        assertEquals(B2bTerm.BT_19, findings.single().businessTerms.first())
        assertEquals(FindingTarget.Header, findings.single().target)
    }

    @Test
    fun `D-P1-F - BT-19 is reported required even without any BT-133 field in the profile at all`() {
        val invoice = invoiceWithSeller(sender(), id = 42L)
        invoice.addItem(InvoiceItem().apply { positionId = "1"; description = "A"; id = 5L })
        nekarBt19Bt133Setup(bt133AsSibling = false)

        val findings = ruleSet.evaluate(invoice)

        assertEquals(1, findings.size)
        assertEquals(B2bTerm.BT_19, findings.single().businessTerms.first())
    }

    @Test
    fun `D-P1-F - filling BT-19 itself is the only way to satisfy it - the LINE side of the pair is dead code`() {
        val invoice = invoiceWithSeller(sender(), id = 42L)
        invoice.addItem(InvoiceItem().apply { positionId = "1"; description = "A"; id = 5L })
        nekarBt19Bt133Setup(bt133AsSibling = true)
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 42L, B2bTerm.BT_19))
            .thenReturn(B2bFieldValue().apply { value = "KST-4711" })

        assertTrue(ruleSet.evaluate(invoice).isEmpty(), "BT-19 filled directly satisfies its own requirement, same as plain MANDATORY would")
    }

    // ─── Placement filter ───────────────────────────────────────────────────────

    @Test
    fun `a SELLER-placed profile field with no term is silently skipped - the term-null disjunct itself`() {
        // Every other "no term" fixture uses the default INVOICE placement, which
        // short-circuits the filter on its first disjunct without ever evaluating
        // `it.term?.binding == FREE`. A SELLER placement forces evaluation all the way to that
        // third disjunct, with term == null.
        val seller = sender()
        val invoice = invoiceWithSeller(seller)

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(B2bProfileField().apply { requirement = B2bFieldRequirement.MANDATORY; placement = B2bPlacement.SELLER; businessDefinition = "x" }),
        )

        assertTrue(ruleSet.evaluate(invoice).isEmpty())
    }

    @Test
    fun `a profile field with no term is silently skipped`() {
        // The former "no matching catalog entry" scenario (a profile field naming an
        // unknown/removed bt_code) cannot occur anymore: a persisted field's term is either a
        // real B2bTerm or null (a data defect), never an unresolvable code — mapNotNull's null
        // check in B2bProfileRuleSet.evaluate is the remaining defensive branch this proves.
        val seller = sender()
        val invoice = invoiceWithSeller(seller)

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(B2bProfileField().apply { requirement = B2bFieldRequirement.MANDATORY; businessDefinition = "x" }),
        )

        assertTrue(ruleSet.evaluate(invoice).isEmpty())
    }

    // ─── ComplianceRuleSet contract ──────────────────────────────────────────────

    @Test
    fun `only the INVOICE level is declared`() {
        assertEquals(setOf(ComplianceLevel.INVOICE), ruleSet.levels)
    }

    @Test
    fun `a non-invoice subject yields an empty list`() {
        assertTrue(ruleSet.evaluate(ComplianceSubject.OfSeller(SenderProfile()), FIXED_TODAY).isEmpty())
    }

    // ─── Coverage: hasValue's `null -> false` branch (a reference term in memory) ───────────────

    @Test
    fun `a reference term (binding = null) always reports missing`() {
        // Every former BUYER_PARTY reference term was upgraded to BOUND (BT-44/46/48/49), so a
        // real profile field can no longer carry a BUYER_PARTY reference term at all — see the
        // BOUND BUYER_PARTY tests below for that now-real, no-longer-synthetic scenario. BT-16
        // remains the one HEADER-level reference term still available to exercise hasValue's
        // defensive `null -> false` branch directly (B2bProfileService.saveField would reject it
        // before persistence; a raw fixture bypasses that guard on purpose, to test this function
        // in isolation).
        val seller = sender()
        val invoice = invoiceWithSeller(seller)

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_16, B2bFieldRequirement.MANDATORY)),
        )

        val findings = ruleSet.evaluate(invoice)
        assertEquals(1, findings.size)
        assertEquals(FindingTarget.Header, findings.single().target)
    }

    // ─── BOUND BUYER_PARTY term (globalState's BUYER_PARTY target dispatch, now real) ──────────

    @Test
    fun `BT-44 (buyer name) MANDATORY is reported missing when the recipient has no name`() {
        val invoice = invoiceWithSeller(sender()).apply { recipient = Recipient().apply { id = 1L } } // name stays ""

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_44, B2bFieldRequirement.MANDATORY)),
        )

        val findings = ruleSet.evaluate(invoice)
        assertEquals(1, findings.size)
        assertEquals(FindingTarget.Buyer, findings.single().target)
        assertEquals(listOf(B2bTerm.BT_44), findings.single().businessTerms)
    }

    @Test
    fun `BT-44 (buyer name) MANDATORY is satisfied once the recipient carries a name`() {
        val invoice = invoiceWithSeller(sender()).apply { recipient = Recipient().apply { id = 1L; name = "Nekar Supermarkt" } }

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_44, B2bFieldRequirement.MANDATORY)),
        )

        assertTrue(ruleSet.evaluate(invoice).isEmpty())
    }

    // ─── Coverage: hasFreeValue branches (LINE-level) ────────────────────────────

    @Test
    fun `a FREE LINE-level field without an invoice item ID returns false`() {
        val invoice = invoiceWithSeller(sender(), id = 42L)
        invoice.addItem(InvoiceItem().apply { positionId = "1"; id = null; description = "no id" })

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_155, B2bFieldRequirement.MANDATORY)),
        )

        val findings = ruleSet.evaluate(invoice)
        assertEquals(1, findings.size)
    }

    // ─── Coverage: readBoundProperty nested property access ─────────────────────

    @Test
    fun `a nested BOUND property (in references embeddable) is successfully read`() {
        val seller = sender()
        val invoice = invoiceWithSeller(seller)
        invoice.references.purchaseOrderReference = "PO-4711"

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_13, B2bFieldRequirement.MANDATORY)),
        )

        assertTrue(ruleSet.evaluate(invoice).isEmpty())
    }

    // ─── Coverage: isPresent with value wrappers ────────────────────────────────

    @Test
    fun `isPresent correctly handles a value wrapper with null inner value`() {
        // When an object has getValue() that returns null
        val seller = sender().apply { iban = Iban.ofNullable(null) }
        val invoice = invoiceWithSeller(seller)

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(
            listOf(profileField(B2bTerm.BT_84, B2bFieldRequirement.MANDATORY)),
        )

        val findings = ruleSet.evaluate(invoice)
        assertEquals(1, findings.size)
    }

    // Coverage: evaluate() from ComplianceSubject bridge

    @Test
    fun `evaluate from ComplianceSubject OfInvoice correctly delegates`() {
        val seller = sender()
        val invoice = invoiceWithSeller(seller)

        whenever(profileFieldRepository.findByProfileId(10L)).thenReturn(emptyList())

        assertTrue(ruleSet.evaluate(ComplianceSubject.OfInvoice(invoice), FIXED_TODAY).isEmpty())
    }

    // ─── Coverage: isPresent branch matrix (direct, via reflection) ─────────────
    // The wrapper cascade (`inner == null || inner === value || isPresent(inner)`) has branch
    // sides no domain fixture reaches: a getValue() returning the receiver itself, an object
    // without getValue() at all, and a wrapper around a blank string. Pinned directly, same
    // reflection pattern as ReceiptServicePdfSignatureTest / StatsEntityContractTest.

    private fun isPresent(value: Any?): Boolean {
        val m = B2bProfileRuleSet::class.java.getDeclaredMethod("isPresent", Any::class.java)
        m.isAccessible = true
        return m.invoke(ruleSet, value) as Boolean
    }

    class SelfReturningWrapper { fun getValue(): Any = this }
    class BlankWrapper { fun getValue(): String = "   " }
    class FilledWrapper { fun getValue(): String = "DE00 1234" }

    @Test
    fun `isPresent branch matrix - strings, wrappers, plain objects`() {
        assertFalse(isPresent(null))
        assertFalse(isPresent("   "))
        assertTrue(isPresent("BT-10"))
        // getValue() returning the receiver itself must not recurse - counts as present.
        assertTrue(isPresent(SelfReturningWrapper()))
        // A wrapper around a blank string is absent, one around content is present.
        assertFalse(isPresent(BlankWrapper()))
        assertTrue(isPresent(FilledWrapper()))
        // No getValue() at all (a date, a number, an enum): present as-is.
        assertTrue(isPresent(42))
    }

    // ─── Coverage: hasFreeValue / readBoundProperty level dispatch ──────────────
    // The SELLER_PARTY/LINE arms of hasFreeValue's owner dispatch have no FREE catalog term
    // seeded today, so no evaluate() fixture reaches them — pinned directly so the dispatch
    // stays correct for the first future FREE seller/line term.

    private fun hasFreeValue(invoice: Invoice, item: InvoiceItem?, field: B2bProfileField, term: B2bTerm): Boolean {
        val m = B2bProfileRuleSet::class.java.getDeclaredMethod(
            "hasFreeValue", Invoice::class.java, InvoiceItem::class.java, B2bProfileField::class.java, B2bTerm::class.java,
        )
        m.isAccessible = true
        return m.invoke(ruleSet, invoice, item, field, term) as Boolean
    }

    @Test
    fun `hasFreeValue is false for an unsaved invoice`() {
        val field = profileField(B2bTerm.BT_12, B2bFieldRequirement.MANDATORY)
        assertFalse(hasFreeValue(Invoice(), null, field, B2bTerm.BT_12))
    }

    @Test
    fun `hasFreeValue seller-party level resolves through the effective sender`() {
        val field = profileField(B2bTerm.BT_84, B2bFieldRequirement.MANDATORY)
        // No sender at all: the owner id cannot be resolved.
        assertFalse(hasFreeValue(invoiceWithSeller(sender(), profileId = null).apply { senderProfile = null }, null, field, B2bTerm.BT_84))
        // Sender present but no stored value row and no RECIPIENT fallback: absent.
        assertFalse(hasFreeValue(invoiceWithSeller(sender()), null, field, B2bTerm.BT_84))
    }

    @Test
    fun `hasFreeValue line level needs a persisted item`() {
        val field = profileField(B2bTerm.BT_155, B2bFieldRequirement.MANDATORY)
        val invoice = invoiceWithSeller(sender())
        assertFalse(hasFreeValue(invoice, InvoiceItem(), field, B2bTerm.BT_155))
        assertFalse(hasFreeValue(invoice, InvoiceItem().apply { id = 5L }, field, B2bTerm.BT_155))
    }

    @Test
    fun `hasFreeValue recipient placement falls back to the profile default`() {
        val withDefault = profileField(B2bTerm.BT_12, B2bFieldRequirement.MANDATORY, placement = B2bPlacement.RECIPIENT)
            .apply { defaultValue = "Vertrag-4711" }
        val withoutDefault = profileField(B2bTerm.BT_12, B2bFieldRequirement.MANDATORY, placement = B2bPlacement.RECIPIENT)
        // No recipient on the invoice: the per-customer lookup is skipped, the default decides.
        val invoice = invoiceWithSeller(sender())
        assertTrue(hasFreeValue(invoice, null, withDefault, B2bTerm.BT_12))
        assertFalse(hasFreeValue(invoice, null, withoutDefault, B2bTerm.BT_12))
    }

    @Test
    fun `readBoundProperty is null for a term without a bound property`() {
        val m = B2bProfileRuleSet::class.java.getDeclaredMethod(
            "readBoundProperty", Invoice::class.java, InvoiceItem::class.java, B2bTerm::class.java,
        )
        m.isAccessible = true
        // BT-16 is a reference term (left as the sole HEADER-level one after BOUND upgrades): no
        // boundProperty, so there is nothing to read.
        assertEquals(null, m.invoke(ruleSet, invoiceWithSeller(sender()), null, B2bTerm.BT_16))
    }

    // ─── Coverage: hasFreeValue/hasPartyChainValue exhaustiveness-only arms ─────
    // T1 gap (BUYER_PARTY has no B2bFieldOwnerType) and the INVOICE placement inside
    // hasPartyChainValue (only ever called for RECIPIENT/SELLER placements from hasFreeValue,
    // see that call site's own guard) are both compiler-mandated `when` arms with no real seed
    // data reaching them today -- same "pinned directly for the first future term" discipline
    // as the SELLER_PARTY/LINE dispatch tests above.

    @Test
    fun `boundary - hasFreeValue for a BUYER_PARTY-level term is false, no owner type exists for it yet`() {
        // BT-45 is a real FREE BUYER_PARTY-level term; placement INVOICE keeps it out of
        // hasPartyChainValue's RECIPIENT/SELLER guard so the level dispatch itself is exercised.
        val field = profileField(B2bTerm.BT_45, B2bFieldRequirement.MANDATORY, placement = B2bPlacement.INVOICE)

        assertFalse(hasFreeValue(invoiceWithSeller(sender()), null, field, B2bTerm.BT_45))
    }

    @Test
    fun `boundary - readBoundProperty returns null when the LINE-level target item itself is null`() {
        val m = B2bProfileRuleSet::class.java.getDeclaredMethod(
            "readBoundProperty", Invoice::class.java, InvoiceItem::class.java, B2bTerm::class.java,
        )
        m.isAccessible = true
        // BT-155 is LINE-level and BOUND - with a null item the level dispatch itself resolves to
        // a null target, short-circuiting before any reflection lookup is attempted.
        assertEquals(null, m.invoke(ruleSet, invoiceWithSeller(sender()), null, B2bTerm.BT_155))
    }

    @Test
    fun `boundary - hasPartyChainValue for a SELLER-placed field whose sender has no id yet cannot chain to it`() {
        val invoice = Invoice().apply {
            id = 42L
            senderProfile = SenderProfile().apply { id = null; name = "Unsaved Anbieter" }
            b2bProfile = de.littletrotter.domain.reference.B2bProfile().apply { id = 10L; name = "Standard" }
        }
        val field = profileField(B2bTerm.BT_58, B2bFieldRequirement.MANDATORY, placement = B2bPlacement.SELLER).apply {
            defaultValue = "profil@example.com"
        }
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 42L, B2bTerm.BT_58)).thenReturn(null)

        // An unsaved sender (no id) cannot be looked up in SENDER_PROFILE at all - the chain
        // must still fall through to the profile default rather than crash or short-circuit true.
        assertTrue(hasFreeValue(invoice, null, field, B2bTerm.BT_58))
        org.mockito.kotlin.verify(fieldValueRepository, org.mockito.kotlin.never())
            .findByOwnerAndBtCode(org.mockito.kotlin.eq(B2bFieldOwnerType.SENDER_PROFILE), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `boundary - hasPartyChainValue for a SELLER-placed field with no effective sender at all falls back to the default`() {
        // Distinct from the "sender has no id yet" case above: here effectiveSender() itself is
        // null (neither selectedSeller nor senderProfile set), so the SELLER arm's `?.id?.let{}`
        // short-circuits one level earlier.
        val invoice = Invoice().apply { id = 42L }
        val field = profileField(B2bTerm.BT_58, B2bFieldRequirement.MANDATORY, placement = B2bPlacement.SELLER).apply {
            defaultValue = "profil@example.com"
        }
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 42L, B2bTerm.BT_58)).thenReturn(null)

        assertTrue(hasFreeValue(invoice, null, field, B2bTerm.BT_58))
        org.mockito.kotlin.verify(fieldValueRepository, org.mockito.kotlin.never())
            .findByOwnerAndBtCode(org.mockito.kotlin.eq(B2bFieldOwnerType.SENDER_PROFILE), org.mockito.kotlin.any(), org.mockito.kotlin.any())
    }

    @Test
    fun `boundary - hasPartyChainValue for a RECIPIENT-placed field with an unsaved profile field skips the per-customer lookup`() {
        // recipientId is present, but field.id (the profile field's own persistence id) is not -
        // the compound recipientId/fieldId guard must short-circuit on the second operand too, not
        // just the first (already covered by the "no recipient at all" case elsewhere).
        val invoice = invoiceWithRecipient(sender(), recipientId = 5L)
        val field = profileField(B2bTerm.BT_58, B2bFieldRequirement.MANDATORY, placement = B2bPlacement.RECIPIENT).apply {
            defaultValue = "profil@example.com" // id stays null: this profile field was never saved
        }
        whenever(fieldValueRepository.findByOwnerAndBtCode(B2bFieldOwnerType.INVOICE, 42L, B2bTerm.BT_58)).thenReturn(null)

        assertTrue(hasFreeValue(invoice, null, field, B2bTerm.BT_58))
        org.mockito.kotlin.verifyNoInteractions(recipientFieldValueRepository)
    }

    @Test
    fun `boundary - readBoundProperty returns null for a SELLER_PARTY term with no effective sender`() {
        val m = B2bProfileRuleSet::class.java.getDeclaredMethod(
            "readBoundProperty", Invoice::class.java, InvoiceItem::class.java, B2bTerm::class.java,
        )
        m.isAccessible = true
        // Neither selectedSeller nor senderProfile is set -> effectiveSender() is null, so the
        // level-dispatch `when` itself resolves to a null target before any reflection is attempted.
        val invoice = Invoice().apply { id = 42L }
        assertEquals(null, m.invoke(ruleSet, invoice, null, B2bTerm.BT_84))
    }

    @Test
    fun `boundary - readBoundProperty returns null for a BUYER_PARTY term with no recipient`() {
        val m = B2bProfileRuleSet::class.java.getDeclaredMethod(
            "readBoundProperty", Invoice::class.java, InvoiceItem::class.java, B2bTerm::class.java,
        )
        m.isAccessible = true
        // invoiceWithSeller never sets a recipient -> BUYER_PARTY dispatch resolves to null too.
        val invoice = invoiceWithSeller(sender())
        assertEquals(null, m.invoke(ruleSet, invoice, null, B2bTerm.BT_44))
    }

    @Test
    fun `boundary - hasPartyChainValue for an INVOICE-placed field has no party to chain to`() {
        val m = B2bProfileRuleSet::class.java.getDeclaredMethod(
            "hasPartyChainValue", Invoice::class.java, Long::class.java, B2bProfileField::class.java, B2bTerm::class.java,
        )
        m.isAccessible = true
        val invoice = invoiceWithSeller(sender(), id = 42L)
        val field = profileField(B2bTerm.BT_84, B2bFieldRequirement.MANDATORY, placement = B2bPlacement.INVOICE)

        val result = m.invoke(ruleSet, invoice, 42L, field, B2bTerm.BT_84) as Boolean

        assertFalse(result, "INVOICE placement has neither a SENDER_PROFILE nor a recipient value row to chain to")
    }
}
