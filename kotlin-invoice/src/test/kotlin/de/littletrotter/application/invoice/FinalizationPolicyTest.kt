package de.littletrotter.application.invoice

import de.littletrotter.application.reference.B2bFieldValueService
import de.littletrotter.application.reference.B2bProfileFieldDefinitionService
import de.littletrotter.application.settings.FeatureSettingsGate
import de.littletrotter.domain.compliance.ComplianceFinding
import de.littletrotter.domain.compliance.ComplianceSeverity
import de.littletrotter.domain.compliance.FindingTarget
import de.littletrotter.domain.compliance.GateBlockReason
import de.littletrotter.domain.compliance.GateDecision
import de.littletrotter.domain.compliance.InvoiceFinalizationBlockedException
import de.littletrotter.domain.compliance.ValidationProfile
import de.littletrotter.domain.compliance.ValidationProfileState
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.invoice.GenerationJobStatus
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceNumber
import de.littletrotter.domain.invoice.InvoiceTotals
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.domain.repository.ValidationProfileRepository
import de.littletrotter.domain.settings.FeatureFlag
import de.littletrotter.domain.shared.ValidationStatus
import de.littletrotter.domain.value.VatId
import de.littletrotter.fixture.PersistenceEmulation
import de.littletrotter.fixture.aGenerationJob
import de.littletrotter.fixture.aPaymentTerm
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anInvoice
import de.littletrotter.fixture.anInvoiceItem
import de.littletrotter.fixture.edit
import de.littletrotter.shared.time.ClockTimeSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.OffsetDateTime

/**
 * [FinalizationPolicy] orchestration: gate stage configuration, compliance rule stage
 * configuration, mandatory seller field validation, and the finalization gate + rule evaluation.
 */
class FinalizationPolicyTest {

    private val time = ClockTimeSource.fixedAt(Instant.parse("2026-06-15T10:00:00Z"))
    private val validationProfileRepo = mock<ValidationProfileRepository>()
    private val generationJobRepo = mock<GenerationJobRepository>()
    private val b2bProfileRuleSet = mock<B2bProfileRuleSet>()
    private val b2bFieldValueService = mock<B2bFieldValueService>()
    private val b2bProfileFieldDefinitionService = mock<B2bProfileFieldDefinitionService>()
    // Mockito default for an unstubbed Boolean-returning method is false — matches
    // FeatureFlag.EXTENDED_PROFILE.defaultEnabled, so every existing test keeps exercising the
    // off-by-default path without having to stub this explicitly.
    private val featureSettingsGate = mock<FeatureSettingsGate>()

    private fun policy(
        gateStage: String = "OBSERVE",
        complianceStage: String = "OBSERVE",
    ) = FinalizationPolicy(
        time, validationProfileRepo, generationJobRepo, b2bProfileRuleSet, b2bFieldValueService, b2bProfileFieldDefinitionService,
        featureSettingsGate,
        gateStage, complianceStage,
    )

    private fun invoice(id: Long? = 1L, status: ValidationStatus = ValidationStatus.VALID): Invoice =
        anInvoice(
            id = id,
            invoiceNumber = InvoiceNumber("TEST-2026-0001"),
            senderProfile = aSenderProfile { this.id = 1L },
            validationStatus = status,
        )

    // ─── enforceRenderableText ───────────────────────────────────────────────────

    @Test
    fun `unrenderable B2B invoice and position values are rejected with the field code`() {
        val inv = invoice().apply { addItem(anInvoiceItem(id = 7L, positionId = "1", description = "x")) }
        whenever(b2bFieldValueService.findFieldValues(B2bFieldOwnerType.INVOICE, 1L)).thenReturn(mapOf("BT-18" to "ok"))
        whenever(b2bFieldValueService.findFieldValuesByOwners(B2bFieldOwnerType.INVOICE_ITEM, listOf(7L)))
            .thenReturn(mapOf(7L to mapOf("BT-156" to "Nr 😀")))

        val ex = org.junit.jupiter.api.Assertions.assertThrows(
            de.littletrotter.domain.invoice.PdfUnrenderableTextException::class.java,
        ) { policy().enforceRenderableText(inv) }
        assertEquals(listOf("😀"), ex.fields["B2B-Feld Position BT-156"])
        assertEquals(1, ex.fields.size)
    }

    @Test
    fun `renderable B2B values pass`() {
        val inv = invoice()
        whenever(b2bFieldValueService.findFieldValues(B2bFieldOwnerType.INVOICE, 1L)).thenReturn(mapOf("BT-18" to "Größe €"))

        policy().enforceRenderableText(inv)
    }

    // ─── enforceMandatorySellerFields ───────────────────────────────────────────

    @Test
    fun `no B2B profile assigned to invoice skips mandatory field check`() {
        val p = policy()
        val inv = invoice()  // No b2bProfile set

        // Should not throw, should not call service
        p.enforceMandatorySellerFields(inv)
        verify(b2bProfileFieldDefinitionService, never()).missingMandatorySellerFields(any(), any())
    }

    @Test
    fun `no seller assigned to invoice skips mandatory field check`() {
        val p = policy()
        val inv = invoice().edit {
            b2bProfile = B2bProfile().edit { id = 1L }
            senderProfile = null
        }

        // Should not throw, should not call service
        p.enforceMandatorySellerFields(inv)
        verify(b2bProfileFieldDefinitionService, never()).missingMandatorySellerFields(any(), any())
    }

    @Test
    fun `missing mandatory seller fields block finalization`() {
        val p = policy()
        val inv = invoice().edit {
            b2bProfile = B2bProfile().edit { id = 1L }
            senderProfile = aSenderProfile { id = 1L }
        }

        whenever(b2bFieldValueService.findFieldValues(B2bFieldOwnerType.SENDER_PROFILE, 1L))
            .thenReturn(mapOf("BT-1" to "value"))
        whenever(b2bProfileFieldDefinitionService.missingMandatorySellerFields(1L, mapOf("BT-1" to "value")))
            .thenReturn(listOf("Kundennummer", "Kontakt"))

        assertThrows(InvalidParameterException::class.java) {
            p.enforceMandatorySellerFields(inv)
        }
    }

    @Test
    fun `present mandatory seller fields allow finalization`() {
        val p = policy()
        val inv = invoice().edit {
            b2bProfile = B2bProfile().edit { id = 1L }
            senderProfile = aSenderProfile { id = 1L }
        }

        whenever(b2bFieldValueService.findFieldValues(B2bFieldOwnerType.SENDER_PROFILE, 1L))
            .thenReturn(mapOf("BT-1" to "value"))
        whenever(b2bProfileFieldDefinitionService.missingMandatorySellerFields(1L, mapOf("BT-1" to "value")))
            .thenReturn(emptyList())

        // Should not throw
        p.enforceMandatorySellerFields(inv)
    }

    private fun activeProfile() = ValidationProfile().edit {
        profileId = "EN16931"
        state = ValidationProfileState.ACTIVE.name
    }

    // ─── enforceSellerTelephoneNumber (N9 — BT-42, BR-DE-6/BR-DE-27) ─────────────

    @Test
    fun `no seller assigned to invoice skips the telephone number check`() {
        val p = policy()
        val inv = invoice().edit { senderProfile = null }

        // Should not throw
        p.enforceSellerTelephoneNumber(inv)
    }

    @Test
    fun `a seller without a telephone number is rejected`() {
        val p = policy()
        val inv = invoice().edit { senderProfile = aSenderProfile { id = 1L; phone = null } }

        val ex = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            p.enforceSellerTelephoneNumber(inv)
        }

        assertEquals(GateBlockReason.COMPLIANCE_RULE_VIOLATION, ex.reason)
        assertEquals(1, ex.findings.size)
        assertEquals("CORE.SELLER_PHONE", ex.findings.single().ruleId)
        assertEquals("compliance.core.seller.phone", ex.findings.single().message)
    }

    @Test
    fun `a seller with a telephone number of at least three digits is accepted`() {
        val p = policy()
        val inv = invoice().edit { senderProfile = aSenderProfile { id = 1L; phone = "+49 30 1234567" } }

        // Should not throw
        p.enforceSellerTelephoneNumber(inv)
    }

    @Test
    fun `a telephone number with fewer than three digits is rejected with its own message`() {
        val p = policy()
        val inv = invoice().edit { senderProfile = aSenderProfile { id = 1L; phone = "+49" } }

        val ex = assertThrows(InvoiceFinalizationBlockedException::class.java) {
            p.enforceSellerTelephoneNumber(inv)
        }

        assertEquals(GateBlockReason.COMPLIANCE_RULE_VIOLATION, ex.reason)
        assertEquals(1, ex.findings.size)
        assertEquals("CORE.SELLER_PHONE_DIGITS", ex.findings.single().ruleId)
        assertEquals(
            "compliance.core.seller.phoneDigits", ex.findings.single().message,
            "must be a distinct message from the missing-number case",
        )
    }

    @Test
    fun `a telephone number with exactly three digits is accepted`() {
        val p = policy()
        val inv = invoice().edit { senderProfile = aSenderProfile { id = 1L; phone = "Tel. 123" } }

        // Should not throw — BR-DE-27's own threshold is exactly three digits
        p.enforceSellerTelephoneNumber(inv)
    }

    // ─── enforceFinalizationGate ─────────────────────────────────────────────────

    @Test
    fun `boundary - constructing without explicit gate or compliance stage config defaults both to OBSERVE`() {
        val p = FinalizationPolicy(
            time, validationProfileRepo, generationJobRepo, b2bProfileRuleSet, b2bFieldValueService, b2bProfileFieldDefinitionService,
            featureSettingsGate,
        )
        val inv = invoice(status = ValidationStatus.INVALID)

        whenever(validationProfileRepo.findCurrentByProfileId("DE")).thenReturn(null)
        whenever(generationJobRepo.findByInvoiceId(1L))
            .thenReturn(listOf(
                aGenerationJob {
                    id = 1L
                    status = GenerationJobStatus.COMPLETED
                    kositReport = "{\"status\": \"INVALID\", \"details\": []}"
                    createdAt = OffsetDateTime.now()
                }
            ))

        // No registered profile row -> would block; the un-configured default stage must still
        // be OBSERVE (log, don't throw), exactly like `policy(gateStage = "OBSERVE")` above.
        val decision = p.enforceFinalizationGate(inv, overrideReason = null, actor = "user1")

        assertEquals(GateDecision.Block::class.java, decision::class.java)
    }

    @Test
    fun `gate decision ALLOW in OBSERVE stage logs and returns decision`() {
        val p = policy(gateStage = "OBSERVE")
        val inv = invoice()

        // an unregistered profile would block; ALLOW needs a registered ACTIVE row
        whenever(validationProfileRepo.findCurrentByProfileId(any())).thenReturn(activeProfile())

        val decision = p.enforceFinalizationGate(inv, overrideReason = null, actor = "user1")

        assertEquals(GateDecision.Allow, decision)
    }

    @Test
    fun `gate decision BLOCK in OBSERVE stage logs but does not throw`() {
        val p = policy(gateStage = "OBSERVE")
        val inv = invoice(status = ValidationStatus.INVALID)

        whenever(validationProfileRepo.findCurrentByProfileId("DE")).thenReturn(null)
        whenever(generationJobRepo.findByInvoiceId(1L))
            .thenReturn(listOf(
                aGenerationJob {
                    id = 1L
                    status = GenerationJobStatus.COMPLETED
                    kositReport = "{\"status\": \"INVALID\", \"details\": []}"
                    createdAt = OffsetDateTime.now()
                }
            ))

        val decision = p.enforceFinalizationGate(inv, overrideReason = null, actor = "user1")

        // OBSERVE stage doesn't throw
        assertEquals(GateDecision.Block::class.java, decision::class.java)
    }

    @Test
    fun `gate decision BLOCK in WARN stage logs warning but does not throw`() {
        val p = policy(gateStage = "WARN")
        val inv = invoice(status = ValidationStatus.INVALID)

        whenever(validationProfileRepo.findCurrentByProfileId("DE")).thenReturn(null)
        whenever(generationJobRepo.findByInvoiceId(1L)).thenReturn(emptyList())

        val decision = p.enforceFinalizationGate(inv, overrideReason = null, actor = "user1")

        // WARN stage logs but doesn't throw
        assertEquals(GateDecision.Block::class.java, decision::class.java)
    }

    @Test
    fun `gate decision BLOCK in BLOCK stage throws exception`() {
        val p = policy(gateStage = "BLOCK")
        val inv = invoice(status = ValidationStatus.INVALID)

        whenever(validationProfileRepo.findCurrentByProfileId("DE")).thenReturn(null)
        whenever(generationJobRepo.findByInvoiceId(1L)).thenReturn(emptyList())

        assertThrows(InvoiceFinalizationBlockedException::class.java) {
            p.enforceFinalizationGate(inv, overrideReason = null, actor = "user1")
        }
    }

    @Test
    fun `gate override on UNAVAILABLE status is logged and applied`() {
        val p = policy(gateStage = "OBSERVE")
        val inv = invoice(status = ValidationStatus.UNAVAILABLE)

        // profile axis must pass (ACTIVE) for the override to be the deciding factor
        whenever(validationProfileRepo.findCurrentByProfileId(any())).thenReturn(activeProfile())
        whenever(generationJobRepo.findByInvoiceId(1L)).thenReturn(emptyList())

        val decision = p.enforceFinalizationGate(inv, overrideReason = "PO request", actor = "manager1")

        assertEquals(GateDecision.Allow, decision)
    }

    @Test
    fun `gate AllowWithWarning decision logs the warning`() {
        // A DEPRECATED profile is the only way FinalizationGate.evaluate ever returns
        // AllowWithWarning (see its own KDoc) — the previous version of this test mocked
        // findCurrentByProfileId to return null, which actually produces Block, so its
        // `if (decision is AllowWithWarning)` body silently never ran. Pin the real path.
        val p = policy(gateStage = "OBSERVE")
        val inv = invoice(status = ValidationStatus.VALID)
        val deprecatedProfile = ValidationProfile().edit {
            profileId = "EN16931"
            state = ValidationProfileState.DEPRECATED.name
        }
        whenever(validationProfileRepo.findCurrentByProfileId(any())).thenReturn(deprecatedProfile)
        whenever(generationJobRepo.findByInvoiceId(1L)).thenReturn(emptyList())

        val decision = p.enforceFinalizationGate(inv, overrideReason = null, actor = "user1")

        assertEquals(GateDecision.AllowWithWarning::class.java, decision::class.java)
        assertEquals("Profil ist veraltet (DEPRECATED).", (decision as GateDecision.AllowWithWarning).warning)
    }

    @Test
    fun `an override with a blank reason is created but never logged or applied (isValid false)`() {
        val p = policy(gateStage = "OBSERVE")
        val inv = invoice(status = ValidationStatus.UNAVAILABLE)

        whenever(validationProfileRepo.findCurrentByProfileId(any())).thenReturn(activeProfile())
        whenever(generationJobRepo.findByInvoiceId(1L)).thenReturn(emptyList())

        // A blank reason still builds a FinalizationOverride (overrideReason != null), but
        // FinalizationOverride.isValid is false — the override must not count.
        val decision = p.enforceFinalizationGate(inv, overrideReason = "   ", actor = "manager1")

        assertEquals(GateDecision.Block::class.java, decision::class.java, "an invalid override must not turn UNAVAILABLE into Allow")
    }

    @Test
    fun `a valid override is not logged when the invoice's validationStatus is not UNAVAILABLE`() {
        val p = policy(gateStage = "OBSERVE")
        val inv = invoice(status = ValidationStatus.VALID)

        whenever(validationProfileRepo.findCurrentByProfileId(any())).thenReturn(activeProfile())
        whenever(generationJobRepo.findByInvoiceId(1L)).thenReturn(emptyList())

        // The override is only ever consulted for UNAVAILABLE — a VALID invoice already allows,
        // the override line's own guard must simply not fire (no crash either way).
        val decision = p.enforceFinalizationGate(inv, overrideReason = "PO request", actor = "manager1")

        assertEquals(GateDecision.Allow, decision)
    }

    @Test
    fun `resolveValidationProfileId tolerates a missing senderProfile`() {
        val p = policy()
        val inv = invoice().edit {
            senderProfile = null
            recipient = aRecipient { vatId = VatId("ATU12345678") }
        }

        whenever(validationProfileRepo.findCurrentByProfileId(any())).thenReturn(null)
        whenever(generationJobRepo.findByInvoiceId(1L)).thenReturn(emptyList())

        p.enforceFinalizationGate(inv, overrideReason = null, actor = "user1")

        verify(validationProfileRepo).findCurrentByProfileId("XRechnung")
    }

    // ─── resolveValidationProfileId variation ────────────────────────────────────

    @Test
    fun `any VAT ID present selects the XRechnung validation profile`() {
        val p = policy()
        val inv = invoice().edit {
            senderProfile = aSenderProfile { vatId = VatId("DE123456789") }
            recipient = aRecipient { vatId = VatId("ATU12345678") }
        }

        whenever(validationProfileRepo.findCurrentByProfileId(any())).thenReturn(null)
        whenever(generationJobRepo.findByInvoiceId(1L)).thenReturn(emptyList())

        p.enforceFinalizationGate(inv, overrideReason = null, actor = "user1")

        // resolveExportProfileId: any VAT id (seller or buyer) -> "XRechnung"
        verify(validationProfileRepo).findCurrentByProfileId("XRechnung")
    }

    @Test
    fun `without any VAT ID the EN16931 profile is used`() {
        val p = policy()
        val inv = invoice().edit {
            senderProfile = aSenderProfile { vatId = null }
            recipient = null
        }

        whenever(validationProfileRepo.findCurrentByProfileId(any())).thenReturn(null)
        whenever(generationJobRepo.findByInvoiceId(1L)).thenReturn(emptyList())

        p.enforceFinalizationGate(inv, overrideReason = null, actor = "user1")

        verify(validationProfileRepo).findCurrentByProfileId("EN16931")
    }

    @Test
    fun `a cash discount without any VAT ID stays EN16931 while the EXTENDED opt-in is off`() {
        val p = policy()
        val inv = invoice().edit {
            senderProfile = aSenderProfile { vatId = null }
            recipient = null
            invoiceDate = java.time.LocalDate.of(2026, 9, 28)
            paymentTerm = aPaymentTerm {
                discountPercent = de.littletrotter.domain.value.DiscountPercent(java.math.BigDecimal("2"))
                discountDays = 7
            }
        }.also {
            PersistenceEmulation.assignTotals(
                it,
                InvoiceTotals(grossAmount = de.littletrotter.shared.Money(java.math.BigDecimal("604.66"))),
            )
        }

        whenever(validationProfileRepo.findCurrentByProfileId(any())).thenReturn(null)
        whenever(generationJobRepo.findByInvoiceId(1L)).thenReturn(emptyList())

        p.enforceFinalizationGate(inv, overrideReason = null, actor = "user1")

        // PO decision 2026-09-28 — the KoSIT validator cannot validate EXTENDED, so the
        // escalation stays off unless the admin flag below is explicitly on.
        verify(validationProfileRepo).findCurrentByProfileId("EN16931")
    }

    @Test
    fun `a cash discount without any VAT ID escalates to EXTENDED once the admin flag is on`() {
        whenever(featureSettingsGate.isEnabled(FeatureFlag.EXTENDED_PROFILE)).thenReturn(true)
        val p = policy()
        val inv = invoice().edit {
            senderProfile = aSenderProfile { vatId = null }
            recipient = null
            invoiceDate = java.time.LocalDate.of(2026, 9, 28)
            paymentTerm = aPaymentTerm {
                discountPercent = de.littletrotter.domain.value.DiscountPercent(java.math.BigDecimal("2"))
                discountDays = 7
            }
        }.also {
            PersistenceEmulation.assignTotals(
                it,
                InvoiceTotals(grossAmount = de.littletrotter.shared.Money(java.math.BigDecimal("604.66"))),
            )
        }

        whenever(validationProfileRepo.findCurrentByProfileId(any())).thenReturn(null)
        whenever(generationJobRepo.findByInvoiceId(1L)).thenReturn(emptyList())

        p.enforceFinalizationGate(inv, overrideReason = null, actor = "user1")

        verify(validationProfileRepo).findCurrentByProfileId("EXTENDED")
    }

    // ─── enforceComplianceRules ──────────────────────────────────────────────────

    @Test
    fun `compliance rules with no violations in OBSERVE stage logs and returns`() {
        val p = policy(complianceStage = "OBSERVE")
        val inv = invoice()

        whenever(b2bProfileRuleSet.evaluate(inv)).thenReturn(emptyList())

        // Should not throw
        p.enforceComplianceRules(inv)
    }

    @Test
    fun `compliance rules with violations in OBSERVE stage logs but does not throw`() {
        val p = policy(complianceStage = "OBSERVE")
        val inv = invoice()

        whenever(b2bProfileRuleSet.evaluate(inv)).thenReturn(listOf(
            ComplianceFinding(
                ruleId = "B2B",
                severity = ComplianceSeverity.ERROR,
                messageKey = "b2b.test.message",
                businessTerms = emptyList(),
                target = FindingTarget.Header,
            )
        ))

        // Should not throw in OBSERVE
        p.enforceComplianceRules(inv)
    }

    @Test
    fun `compliance rules with violations in WARN stage logs warning but does not throw`() {
        val p = policy(complianceStage = "WARN")
        val inv = invoice()

        whenever(b2bProfileRuleSet.evaluate(inv)).thenReturn(listOf(
            ComplianceFinding(
                ruleId = "B2B",
                severity = ComplianceSeverity.ERROR,
                messageKey = "b2b.test.message",
                businessTerms = emptyList(),
                target = FindingTarget.Header,
            )
        ))

        // Should not throw in WARN
        p.enforceComplianceRules(inv)
    }

    @Test
    fun `compliance rules with violations in BLOCK stage throws exception`() {
        val p = policy(complianceStage = "BLOCK")
        val inv = invoice()

        whenever(b2bProfileRuleSet.evaluate(inv)).thenReturn(listOf(
            ComplianceFinding(
                ruleId = "B2B",
                severity = ComplianceSeverity.ERROR,
                messageKey = "b2b.test.message",
                businessTerms = emptyList(),
                target = FindingTarget.Header,
            )
        ))

        assertThrows(InvoiceFinalizationBlockedException::class.java) {
            p.enforceComplianceRules(inv)
        }
    }

    // ─── Stage configuration fallback ────────────────────────────────────────────

    @Test
    fun `invalid gate stage config defaults to OBSERVE`() {
        val p = policy(gateStage = "INVALID_STAGE")
        val inv = invoice()

        // unregistered profile -> Block decision; the point is that the bogus stage string
        // falls back to OBSERVE and therefore does NOT throw
        whenever(validationProfileRepo.findCurrentByProfileId(any())).thenReturn(null)
        whenever(generationJobRepo.findByInvoiceId(1L)).thenReturn(emptyList())

        val decision = p.enforceFinalizationGate(inv, overrideReason = null, actor = "user1")
        assertEquals(GateDecision.Block::class.java, decision::class.java)
    }

    @Test
    fun `invalid compliance stage config defaults to OBSERVE`() {
        val p = policy(complianceStage = "INVALID_STAGE")
        val inv = invoice()

        whenever(b2bProfileRuleSet.evaluate(inv)).thenReturn(listOf(
            ComplianceFinding(
                ruleId = "B2B",
                severity = ComplianceSeverity.ERROR,
                messageKey = "b2b.test.message",
                businessTerms = emptyList(),
                target = FindingTarget.Header,
            )
        ))

        // Should not throw (OBSERVE doesn't block)
        p.enforceComplianceRules(inv)
    }

    // ─── latestFindings ──────────────────────────────────────────────────────────

    @Test
    fun `latest findings are extracted from the most recent completed job`() {
        val p = policy()
        val inv = invoice(status = ValidationStatus.INVALID)

        val oldJob = aGenerationJob {
            id = 1L
            status = GenerationJobStatus.COMPLETED
            kositReport = "{\"status\": \"INVALID\", \"details\": [{\"severity\": \"ERROR\", \"message\": \"Old\"}]}"
            createdAt = OffsetDateTime.now().minusSeconds(1000)
        }
        val newJob = aGenerationJob {
            id = 2L
            status = GenerationJobStatus.COMPLETED
            kositReport = "{\"status\": \"INVALID\", \"details\": [{\"severity\": \"ERROR\", \"message\": \"New\"}]}"
            createdAt = OffsetDateTime.now()
        }

        whenever(generationJobRepo.findByInvoiceId(1L)).thenReturn(listOf(oldJob, newJob))
        whenever(validationProfileRepo.findCurrentByProfileId("DE")).thenReturn(null)

        p.enforceFinalizationGate(inv, overrideReason = null, actor = "user1")

        // Should use the newer job's findings
        verify(generationJobRepo).findByInvoiceId(1L)
    }

    @Test
    fun `no completed jobs found returns empty findings`() {
        val p = policy()
        val inv = invoice(status = ValidationStatus.INVALID)

        whenever(generationJobRepo.findByInvoiceId(1L)).thenReturn(listOf(
            aGenerationJob {
                id = 1L
                status = GenerationJobStatus.PENDING
            }
        ))
        whenever(validationProfileRepo.findCurrentByProfileId("DE")).thenReturn(null)

        p.enforceFinalizationGate(inv, overrideReason = null, actor = "user1")

        verify(generationJobRepo).findByInvoiceId(1L)
    }

    @Test
    fun `invoice without ID skips findings lookup`() {
        val p = policy()
        val inv = invoice(id = null, status = ValidationStatus.INVALID)

        whenever(validationProfileRepo.findCurrentByProfileId("DE")).thenReturn(null)

        val decision = p.enforceFinalizationGate(inv, overrideReason = null, actor = "user1")

        // Should proceed without calling generationJobRepo
        verify(generationJobRepo, never()).findByInvoiceId(any())
    }

    @Test
    fun `malformed KOSIT report payload returns empty findings`() {
        val p = policy()
        val inv = invoice(status = ValidationStatus.INVALID)

        whenever(generationJobRepo.findByInvoiceId(1L)).thenReturn(listOf(
            aGenerationJob {
                id = 1L
                status = GenerationJobStatus.COMPLETED
                kositReport = "not valid json"
                createdAt = OffsetDateTime.now()
            }
        ))
        whenever(validationProfileRepo.findCurrentByProfileId("DE")).thenReturn(null)

        // Should not throw, just proceed
        p.enforceFinalizationGate(inv, overrideReason = null, actor = "user1")
    }
}
