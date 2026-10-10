package de.littletrotter.ui.view

import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.ui.view.invoice.allowedTargetStatuses
import de.littletrotter.ui.view.invoice.buildCheckRow
import de.littletrotter.ui.view.invoice.checklistStateIcon
import de.littletrotter.ui.view.invoice.checklistStateTextClass
import de.littletrotter.ui.view.invoice.visibleStatusActionsFor
import de.littletrotter.ui.view.invoice.STATUS_ACTIONS
import de.littletrotter.ui.view.invoice.validateDateGuard
import de.littletrotter.ui.view.invoice.parseGenerationJobObjectKey
import de.littletrotter.ui.view.invoice.parseGenerationJobResultRef
import de.littletrotter.ui.view.invoice.getDownloadResourceName
import de.littletrotter.ui.view.invoice.cockpitHeroStatusClass
import de.littletrotter.ui.view.invoice.resolveRecipientAddresses
import de.littletrotter.ui.view.invoice.applyRecipientAddressChecks
import de.littletrotter.ui.view.invoice.RecipientAddressLookup
import de.littletrotter.ui.view.invoice.ResolvedRecipientAddresses
import de.littletrotter.ui.view.invoice.InvoiceCompletenessCheck
import de.littletrotter.application.dto.AddressDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class InvoiceDetailViewTest {

    private fun address(street: String): AddressDto =
        AddressDto(id = null, street = street, postalCode = "12345", city = "Koeln", countryAlpha2 = "DE")

    @Test
    fun `resolveRecipientAddresses uses current addresses when recipient id is missing`() {
        val currentBilling = address("Billing 1")
        var billingCalls = 0
        var deliveryCalls = 0

        val resolved = resolveRecipientAddresses(
            recipientId = null,
            current = ResolvedRecipientAddresses(billing = currentBilling, deliveryForDisplay = null),
            lookup = RecipientAddressLookup(
                findBilling = { billingCalls += 1; null },
                findDelivery = { deliveryCalls += 1; null },
            ),
        )

        assertEquals(currentBilling, resolved.billing)
        assertEquals(currentBilling, resolved.deliveryForDisplay)
        assertEquals(0, billingCalls)
        assertEquals(0, deliveryCalls)
    }

    @Test
    fun `resolveRecipientAddresses prefers facade addresses when recipient id is present`() {
        val facadeBilling = address("Billing Facade")
        val facadeDelivery = address("Delivery Facade")

        val resolved = resolveRecipientAddresses(
            recipientId = 7L,
            current = ResolvedRecipientAddresses(billing = address("Billing Current"), deliveryForDisplay = address("Delivery Current")),
            lookup = RecipientAddressLookup(findBilling = { facadeBilling }, findDelivery = { facadeDelivery }),
        )

        assertEquals(facadeBilling, resolved.billing)
        assertEquals(facadeDelivery, resolved.deliveryForDisplay)
    }

    @Test
    fun `resolveRecipientAddresses falls back to current addresses when facade returns null`() {
        val currentBilling = address("Billing Current")
        val currentDelivery = address("Delivery Current")

        val resolved = resolveRecipientAddresses(
            recipientId = 9L,
            current = ResolvedRecipientAddresses(billing = currentBilling, deliveryForDisplay = currentDelivery),
            lookup = RecipientAddressLookup(findBilling = { null }, findDelivery = { null }),
        )

        assertEquals(currentBilling, resolved.billing)
        assertEquals(currentDelivery, resolved.deliveryForDisplay)
    }

    @Test
    fun `resolveRecipientAddresses uses billing as delivery fallback when no delivery exists`() {
        val facadeBilling = address("Billing Only")

        val resolved = resolveRecipientAddresses(
            recipientId = 11L,
            current = ResolvedRecipientAddresses(billing = null, deliveryForDisplay = null),
            lookup = RecipientAddressLookup(findBilling = { facadeBilling }, findDelivery = { null }),
        )

        assertEquals(facadeBilling, resolved.billing)
        assertEquals(facadeBilling, resolved.deliveryForDisplay)
    }

    @Test
    fun `applyRecipientAddressChecks marks recipient billing checks complete when resolved billing exists`() {
        val checks = listOf(
            InvoiceCompletenessCheck("invoiceDetail.summary.checklist.recipientAddress", false),
            InvoiceCompletenessCheck("invoiceDetail.summary.checklist.recipientBillingAddress", false),
            InvoiceCompletenessCheck("invoiceDetail.summary.checklist.recipientDeliveryAddress", false),
        )
        val resolved = ResolvedRecipientAddresses(
            billing = address("Billing 1"),
            deliveryForDisplay = address("Billing 1"),
        )

        val updated = applyRecipientAddressChecks(checks, resolved).associateBy { it.translationKey }

        assertTrue(updated.getValue("invoiceDetail.summary.checklist.recipientAddress").isComplete)
        assertTrue(updated.getValue("invoiceDetail.summary.checklist.recipientBillingAddress").isComplete)
        assertTrue(updated.getValue("invoiceDetail.summary.checklist.recipientDeliveryAddress").isComplete)
    }

    @Test
    fun `applyRecipientAddressChecks keeps recipient checks incomplete when resolved addresses are missing`() {
        val checks = listOf(
            InvoiceCompletenessCheck("invoiceDetail.summary.checklist.recipientAddress", true),
            InvoiceCompletenessCheck("invoiceDetail.summary.checklist.recipientBillingAddress", true),
            InvoiceCompletenessCheck("invoiceDetail.summary.checklist.recipientDeliveryAddress", true),
        )

        val updated = applyRecipientAddressChecks(checks, ResolvedRecipientAddresses(null, null)).associateBy { it.translationKey }

        assertFalse(updated.getValue("invoiceDetail.summary.checklist.recipientAddress").isComplete)
        assertFalse(updated.getValue("invoiceDetail.summary.checklist.recipientBillingAddress").isComplete)
        assertFalse(updated.getValue("invoiceDetail.summary.checklist.recipientDeliveryAddress").isComplete)
    }

    @Test
    fun `incomplete checklist items render as errors`() {
        assertEquals(VaadinIcon.CLOSE_CIRCLE, checklistStateIcon(false))
        assertEquals("text-error", checklistStateTextClass(false))
    }

    @Test
    fun `completed checklist items stay positive`() {
        assertEquals(VaadinIcon.CHECK_CIRCLE, checklistStateIcon(true))
        assertEquals(null, checklistStateTextClass(true))
    }

    @Test
    fun `draft allows review and void`() {
        val targets = allowedTargetStatuses(InvoiceStatus.DRAFT)
        assertEquals(setOf(InvoiceStatus.IN_REVIEW, InvoiceStatus.VOIDED), targets)
    }

    @Test
    fun `in review allows finalize draft and void`() {
        val targets = allowedTargetStatuses(InvoiceStatus.IN_REVIEW)
        assertEquals(
            setOf(InvoiceStatus.DRAFT, InvoiceStatus.SENT, InvoiceStatus.VOIDED),
            targets,
        )
    }

    @Test
    fun `finalized allows only the storno transition`() {
        // ST-1 — SENT is no longer terminal: it keeps exactly one outgoing edge, the storno of a
        // sent invoice. It stays read-only in every other respect (Invoice.isReadOnly).
        val targets = allowedTargetStatuses(InvoiceStatus.SENT)
        assertEquals(setOf(InvoiceStatus.CANCELLED), targets)
    }

    @Test
    fun `generation job objectKey extraction from result refs works`() {
        val payload = "{\"objectKey\": \"invoices/2025-123.pdf\"}"
        assertEquals("invoices/2025-123.pdf", parseGenerationJobObjectKey(payload))
        assertEquals(null, parseGenerationJobObjectKey(null))
        assertEquals(null, parseGenerationJobObjectKey("{}"))
    }

    @Test
    fun `generation job download resource name derives invoice number`() {
        val job = de.littletrotter.domain.invoice.GenerationJob().apply {
            id = 123L
            invoice = de.littletrotter.domain.invoice.Invoice().apply { invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("INV-2026-03-00001") }
        }
        assertEquals("INV-2026-03-00001.pdf", getDownloadResourceName(job))
    }

    @Test
    fun `generation job download resource name fallback to job id when invoice number missing`() {
        val job = de.littletrotter.domain.invoice.GenerationJob().apply {
            id = 123L
            invoice = null
        }
        assertEquals("job-123.pdf", getDownloadResourceName(job))
    }

    @Test
    fun `generation job download resource name supports legacy invoice number format`() {
        val job = de.littletrotter.domain.invoice.GenerationJob().apply {
            id = 42L
            invoice = de.littletrotter.domain.invoice.Invoice().apply {
                invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("INV-2026-0042")
            }
        }
        assertEquals("INV-2026-0042.pdf", getDownloadResourceName(job))
    }

    @Test
    fun `generation job download resource name fallback to job id when invoice number is blank`() {
        val job = de.littletrotter.domain.invoice.GenerationJob().apply {
            id = 99L
            invoice = de.littletrotter.domain.invoice.Invoice().apply {
                invoiceNumber = de.littletrotter.domain.invoice.InvoiceNumber("")
            }
        }
        assertEquals("job-99.pdf", getDownloadResourceName(job))
    }

    @Test
    fun `generation job objectKey extraction works with compact JSON (no spaces)`() {
        val payload = "{\"objectKey\":\"invoices/compact.pdf\"}"
        assertEquals("invoices/compact.pdf", parseGenerationJobObjectKey(payload))
    }

    @Test
    fun `generation job objectKey extraction works when extra whitespace surrounds colon`() {
        val payload = """{"objectKey"  :  "invoices/spaced.pdf"}"""
        assertEquals("invoices/spaced.pdf", parseGenerationJobObjectKey(payload))
    }

    @Test
    fun `generation job objectKey extraction returns first match when multiple keys present`() {
        val payload = """{"objectKey": "invoices/first.pdf", "downloadUrl": "https://host/invoice.pdf"}"""
        assertEquals("invoices/first.pdf", parseGenerationJobObjectKey(payload))
    }

    @Test
    fun `generation job zugferd objectKey extraction uses fallback keys`() {
        val payload = """{"xmlObjectKey":"invoices/first.xml"}"""
        assertEquals("invoices/first.xml", parseGenerationJobResultRef(payload, "zugferdObjectKey", "xmlObjectKey"))
    }

    @Test
    fun `visible actions for draft are review and void`() {
        val actions = visibleStatusActionsFor(InvoiceStatus.DRAFT)
        assertEquals(
            listOf(InvoiceStatus.IN_REVIEW, InvoiceStatus.VOIDED),
            actions.map { it.target },
        )
        assertEquals(
            listOf("invoiceDetail.review", "invoiceDetail.voidInvoice"),
            actions.map { it.labelKey },
        )
        assertEquals(
            listOf(10, 30),
            actions.map { it.order },
        )
    }

    @Test
    fun `visible actions for in review are back to draft finalize and void`() {
        val actions = visibleStatusActionsFor(InvoiceStatus.IN_REVIEW)
        assertEquals(
            listOf(InvoiceStatus.DRAFT, InvoiceStatus.SENT, InvoiceStatus.VOIDED),
            actions.map { it.target },
        )
        assertEquals(
            listOf("invoiceDetail.backToDraft", "invoiceDetail.finalize", "invoiceDetail.voidInvoice"),
            actions.map { it.labelKey },
        )
        assertEquals(
            listOf(15, 20, 30),
            actions.map { it.order },
        )
    }

    @Test
    fun `visible actions for finalized are empty`() {
        val actions = visibleStatusActionsFor(InvoiceStatus.SENT)
        assertEquals(emptyList<InvoiceStatus>(), actions.map { it.target })
    }

    @Test
    fun `action css classes are correct`() {
        val review = STATUS_ACTIONS.first { it.target == InvoiceStatus.IN_REVIEW }
        val backToDraft = STATUS_ACTIONS.first { it.target == InvoiceStatus.DRAFT }
        val finalize = STATUS_ACTIONS.first { it.target == InvoiceStatus.SENT }
        val void = STATUS_ACTIONS.first { it.target == InvoiceStatus.VOIDED }

        assertEquals("ch-btn--review", review.cssClass)
        assertEquals("ch-btn--draft", backToDraft.cssClass)
        assertEquals("ch-btn--finalize", finalize.cssClass)
        assertEquals("ch-btn--void", void.cssClass)
    }

    @Test
    fun `status action definitions are valid`() {
        assertTrue(STATUS_ACTIONS.all { it.labelKey.isNotBlank() })
        assertEquals(STATUS_ACTIONS.size, STATUS_ACTIONS.map { it.order }.toSet().size)
    }

    @Test
    fun `date guard accepts valid chronology`() {
        val error = validateDateGuard(
            serviceFrom = LocalDate.of(2026, 3, 1),
            serviceTo = LocalDate.of(2026, 3, 5),
        )
        assertEquals(null, error)
    }

    @Test
    fun `date guard rejects service period inversion`() {
        val error = validateDateGuard(
            serviceFrom = LocalDate.of(2026, 3, 6),
            serviceTo = LocalDate.of(2026, 3, 5),
        )
        assertEquals("invoiceDetail.validation.servicePeriod.order", error)
    }

    // DATE: rule 3 (PO decision 2026-09-01) downgraded "due
    // date before invoice date"/"due date before service end" from this guard's hard block to a
    // non-blocking WARNING (de.littletrotter.domain.compliance.DueDateRules) — see
    // InvoiceDateRulesTest for that WARNING's own coverage. This guard no longer takes an
    // invoiceDate/dueDate at all, so the two tests that used to pin those rejections here are
    // gone; the case below pins that a service-period-only guard no longer has anything to say
    // about due dates.
    @Test
    fun `date guard no longer considers due date at all`() {
        val error = validateDateGuard(
            serviceFrom = LocalDate.of(2026, 3, 2),
            serviceTo = LocalDate.of(2026, 3, 20),
        )
        assertEquals(null, error)
    }

    @Test
    fun `date guard accepts open service period boundaries`() {
        val onlyFrom = validateDateGuard(
            serviceFrom = LocalDate.of(2026, 3, 1),
            serviceTo = null,
        )
        val onlyTo = validateDateGuard(
            serviceFrom = null,
            serviceTo = LocalDate.of(2026, 3, 5),
        )

        assertEquals(null, onlyFrom)
        assertEquals(null, onlyTo)
    }

    @Test
    fun `visible actions remain subset of allowed transitions and are strictly ordered`() {
        InvoiceStatus.entries.forEach { status ->
            val actions = visibleStatusActionsFor(status)
            val actionTargets = actions.map { it.target }.toSet()
            val allowed = allowedTargetStatuses(status)

            assertTrue(allowed.containsAll(actionTargets), "Visible actions must be allowed for status $status")

            val orders = actions.map { it.order }
            assertEquals(orders.sorted(), orders, "Actions must be sorted by order for status $status")
            assertEquals(orders.toSet().size, orders.size, "Action orders must be unique for status $status")
        }
    }

    @Test
    fun `draft action bar has no finalize action but has one void action`() {
        val actions = visibleStatusActionsFor(InvoiceStatus.DRAFT)

        val finalizeCount = actions.count { it.cssClass == "ch-btn--finalize" }
        val voidCount = actions.count { it.cssClass == "ch-btn--void" }

        assertEquals(0, finalizeCount)
        assertEquals(1, voidCount)
    }

    @Test
    fun `in review action bar has exactly one primary finalize action`() {
        val actions = visibleStatusActionsFor(InvoiceStatus.IN_REVIEW)

        val primaryActions = actions.filter { it.cssClass == "ch-btn--finalize" }

        assertEquals(1, primaryActions.size)
        assertEquals(InvoiceStatus.SENT, primaryActions.single().target)
    }

    @Test
    fun `read-only statuses expose no visible actions`() {
        // ST-1 — SENT is read-only but no longer terminal (it has the storno edge to CANCELLED);
        // STATUS_ACTIONS carries no entry for that edge, so the action bar still renders empty.
        val finalizedActions = visibleStatusActionsFor(InvoiceStatus.SENT)
        val cancelledActions = visibleStatusActionsFor(InvoiceStatus.CANCELLED)
        val voidedActions = visibleStatusActionsFor(InvoiceStatus.VOIDED)

        assertTrue(finalizedActions.isEmpty())
        assertTrue(cancelledActions.isEmpty())
        assertTrue(voidedActions.isEmpty())
    }

    @Test
    fun `visible actions follow STATUS_ACTIONS order and metadata contract`() {
        InvoiceStatus.entries.forEach { status ->
            val expected = STATUS_ACTIONS
                .filter { it.target in allowedTargetStatuses(status) }
                .sortedBy { it.order }
            val actual = visibleStatusActionsFor(status)

            assertEquals(expected.map { it.target }, actual.map { it.target }, "Target order mismatch for $status")
            assertEquals(expected.map { it.labelKey }, actual.map { it.labelKey }, "Label keys mismatch for $status")
            assertEquals(expected.map { it.cssClass }, actual.map { it.cssClass }, "CSS class mismatch for $status")
            assertEquals(expected.map { it.order }, actual.map { it.order }, "Order metadata mismatch for $status")
        }
    }

    // ── cockpitHeroStatusClass ────────────────────────────────────────────────

    @Test
    fun `cockpitHeroStatusClass maps every InvoiceStatus to a distinct non-blank CSS class`() {
        val classes = InvoiceStatus.entries.map { cockpitHeroStatusClass(it) }
        assertTrue(classes.all { it.isNotBlank() })
        assertEquals(classes.size, classes.toSet().size, "Every status must map to a unique class")
    }

    @Test
    fun `cockpitHeroStatusClass returns expected class per status`() {
        assertEquals("cockpit-hero--draft",     cockpitHeroStatusClass(InvoiceStatus.DRAFT))
        assertEquals("cockpit-hero--review",    cockpitHeroStatusClass(InvoiceStatus.IN_REVIEW))
        assertEquals("cockpit-hero--sent",      cockpitHeroStatusClass(InvoiceStatus.SENT))
        assertEquals("cockpit-hero--cancelled", cockpitHeroStatusClass(InvoiceStatus.CANCELLED))
        assertEquals("cockpit-hero--voided",    cockpitHeroStatusClass(InvoiceStatus.VOIDED))
    }

    // ── StatusAction icon/cssClass contract ──��────────────────────────────────

    @Test
    fun `every StatusAction carries a non-null icon`() {
        STATUS_ACTIONS.forEach { action ->
            assertTrue(action.icon != null, "Icon missing for action ${action.labelKey}")
        }
    }

    @Test
    fun `all cssClass values are non-blank and prefixed with ch-btn--`() {
        STATUS_ACTIONS.forEach { action ->
            assertTrue(action.cssClass.startsWith("ch-btn--"),
                "cssClass '${action.cssClass}' for ${action.labelKey} must start with 'ch-btn--'")
        }
    }

    @Test
    fun `cssClass values are unique across all actions`() {
        val classes = STATUS_ACTIONS.map { it.cssClass }
        assertEquals(classes.size, classes.toSet().size, "Duplicate cssClass values in STATUS_ACTIONS")
    }

    // ── buildCheckRow — the actual row builder, not just the two pure mapping
    // functions it composes (already pinned above); UI-5 slice 5. ─────────────

    @Test
    fun `buildCheckRow for a satisfied check shows the ok icon class and no error text class`() {
        val row = buildCheckRow("Alles da", ok = true)

        val icon = row.children.toList().filterIsInstance<Icon>().single()
        assertTrue(icon.element.classList.contains("checklist-icon--ok"))
        assertFalse(icon.element.classList.contains("checklist-icon--missing"))

        val label = row.children.toList().filterIsInstance<Span>().single()
        assertFalse(label.element.classList.contains("text-error"))
    }

    @Test
    fun `buildCheckRow for a missing check shows the missing icon class and the error text class`() {
        val row = buildCheckRow("Fehlt noch", ok = false)

        val icon = row.children.toList().filterIsInstance<Icon>().single()
        assertTrue(icon.element.classList.contains("checklist-icon--missing"))
        assertFalse(icon.element.classList.contains("checklist-icon--ok"))

        val label = row.children.toList().filterIsInstance<Span>().single()
        assertTrue(label.element.classList.contains("text-error"))
    }
}
