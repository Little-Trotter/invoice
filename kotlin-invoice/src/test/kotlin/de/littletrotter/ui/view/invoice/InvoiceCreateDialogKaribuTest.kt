package de.littletrotter.ui.view.invoice

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.getNotifications
import com.github.mvysny.kaributesting.v10._get
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.dom.Element
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.dto.RecipientSummaryDto
import de.littletrotter.application.dto.SenderSummaryDto
import de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase
import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.exception.InvoiceValidationError
import de.littletrotter.domain.exception.InvoiceValidationException
import de.littletrotter.domain.exception.RecipientNotFoundException
import de.littletrotter.domain.exception.SenderProfileNotFoundException
import de.littletrotter.domain.invoice.InvoiceStatus
import de.littletrotter.domain.masterdata.Address
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.ReverseChargePreference
import de.littletrotter.domain.masterdata.SellerVatRegistrationCommand
import de.littletrotter.shared.Money
import de.littletrotter.ui.i18n.UiTranslations
import java.time.LocalDate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY

/**
 * invoice-from-customer-row — [InvoiceCreateDialog] on its own, extracted out of
 * [InvoiceListView]. Same construction/reflection idioms as [InvoiceCreateDialogSellerSelectionTest]
 * (the class needs no CDI container to build), plus the extraction's own new surface: item-label
 * shortHash, [preselectedRecipient][InvoiceCreateDialog] wiring, and the [onCreated] callback firing
 * instead of a hard-wired navigation.
 */
class InvoiceCreateDialogKaribuTest {

    private val recipientQuery: RecipientQueryPort = mock()
    private val senderQuery: SenderQueryPort = mock()
    private val createInvoiceUseCase: CreateInvoiceUseCase = mock()

    private val defaultSender = aSenderProfile {
        id = 1L
        name = "Haupt-Anbieter GmbH"
        isDefault = true
    }
    private val recipient = aRecipient {
        id = 10L
        name = "Testkunde AG"
        customerNumber = CustomerNumber("KD-0001")
    }

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try {
            MockVaadin.tearDown()
        } catch (_: Exception) {
        }
    }

    private fun dialogForm(dialog: Dialog): FormLayout =
        dialog.children.toList().filterIsInstance<FormLayout>().first()

    private fun sellerCombo(dialog: Dialog): ComboBox<SenderSummaryDto> =
        dialogForm(dialog).children.toList().filterIsInstance<ComboBox<*>>()[0].requireAs()

    private fun recipientCombo(dialog: Dialog): ComboBox<RecipientSummaryDto> =
        dialogForm(dialog).children.toList().filterIsInstance<ComboBox<*>>()[1].requireAs()

    private fun hintParagraph(dialog: Dialog): Paragraph =
        dialogForm(dialog).children.toList().filterIsInstance<Paragraph>().first()

    private fun createButton(dialog: Dialog): Button =
        dialog._get<Button> { text = UiTranslations.tr("invoice.dialog.create") }

    private fun reverseChargeDecision(dialog: Dialog): RadioButtonGroup<Boolean> =
        dialogForm(dialog).children.toList().filterIsInstance<RadioButtonGroup<*>>().single().requireAs()

    private fun minimalCreatedDto(id: Long = 77L) = InvoiceDetailDto(
        id = id,
        invoiceNumber = "INV-2026-08-00077",
        status = InvoiceStatus.DRAFT.ordinal,
        invoiceDate = LocalDate.now(),
        netAmount = Money.ZERO,
        vatAmount = Money.ZERO,
        grossAmount = Money.ZERO,
        sender = null,
        recipient = null,
        items = emptyList(),
        latestGenerationJob = null,
    )

    // Whole-tree walk (see ReceiptDialogActionsKaribuTest) — a notification is not necessarily
    // parented under the dialog, so the whole UI tree is searched, not just the dialog subtree.
    private fun allComponents(): List<Component> {
        val found = mutableListOf<Component>()
        fun walk(element: Element) {
            element.component.ifPresent { found.add(it) }
            element.children.forEach(::walk)
        }
        walk(UI.getCurrent().element)
        return found
    }

    private fun assertNotificationShown(expectedText: String) {
        MockVaadin.clientRoundtrip()
        val found = allComponents().filterIsInstance<Span>().any { it.text == expectedText } ||
            allComponents().filterIsInstance<Notification>().any { de.littletrotter.ui.toastText(it) == expectedText }
        assertTrue(found, "expected an open notification with text: $expectedText")
    }

    // ── no-seller blocking path ──────────────────────────────────────────────────

    @Test
    fun `no seller at all shows the blocking message and never renders the form`() {
        whenever(senderQuery.findAllSenders()).thenReturn(emptyList())

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()

        val paragraph = dialog.children.toList().filterIsInstance<Paragraph>().first()
        assertEquals(UiTranslations.tr("invoice.dialog.noSeller"), paragraph.text)
        assertTrue(dialog.children.toList().none { it is FormLayout })
    }

    // ── validation error branches ────────────────────────────────────────────────

    @Test
    fun `create without a seller shows the seller-required notification`() {
        val other = aSenderProfile { id = 2L; name = "Zweit-Anbieter e.K." }
        val second = aSenderProfile { id = 3L; name = "Dritt-Anbieter e.K." }
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(other.toSenderSummaryDto(), second.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()

        assertNull(sellerCombo(dialog).value, "no default and several senders: nothing preselected")
        createButton(dialog).click()

        assertTrue(sellerCombo(dialog).isInvalid)
        assertEquals(UiTranslations.tr("invoice.dialog.seller.required"), sellerCombo(dialog).errorMessage)
        assertTrue(getNotifications().isEmpty(), "an input error belongs on the field, not in a toast")
        verify(createInvoiceUseCase, never()).createInvoiceWithRecipient(any(), any(), anyOrNull())
    }

    @Test
    fun `create without a recipient shows the recipient-required notification`() {
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()

        createButton(dialog).click()

        assertTrue(recipientCombo(dialog).isInvalid)
        assertEquals(UiTranslations.tr("invoice.dialog.recipient.required"), recipientCombo(dialog).errorMessage)
        assertTrue(getNotifications().isEmpty(), "an input error belongs on the field, not in a toast")
        verify(createInvoiceUseCase, never()).createInvoiceWithRecipient(any(), any(), anyOrNull())
    }

    // ── successful create ────────────────────────────────────────────────────────

    @Test
    fun `a successful create closes the dialog and fires onCreated with the new id`() {
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)
        // A domestic recipient's default ASK preference resolves to Resolved(false): the
        // dialog now always supplies an explicit answer while invisible, not null.
        whenever(createInvoiceUseCase.createInvoiceWithRecipient(1L, 10L, false)).thenReturn(minimalCreatedDto(id = 555L))
        var createdId: Long? = null

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { id -> createdId = id }
        dialog.open()
        recipientCombo(dialog).value = recipient.toRecipientSummaryDto()

        createButton(dialog).click()

        verify(createInvoiceUseCase).createInvoiceWithRecipient(1L, 10L, false)
        assertEquals(555L, createdId)
        assertFalse(dialog.isOpened, "the dialog must close itself before firing onCreated")
    }

    @Test
    fun `cross VAT country creation requires explicit reverse charge yes or no`() {
        defaultSender.edit { vatId = VatId("DE123456789") }
        recipient.edit { vatId = VatId("LU12345678") }
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)
        whenever(createInvoiceUseCase.createInvoiceWithRecipient(1L, 10L, true)).thenReturn(minimalCreatedDto())

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()
        recipientCombo(dialog).value = recipient.toRecipientSummaryDto()

        val decision = reverseChargeDecision(dialog)
        assertTrue(decision.isVisible)
        assertFalse(createButton(dialog).isEnabled)
        decision.value = true
        assertTrue(createButton(dialog).isEnabled)

        createButton(dialog).click()
        verify(createInvoiceUseCase).createInvoiceWithRecipient(1L, 10L, true)
    }

    // ── recipient reverseChargePreference wiring ────────────────────────────

    @Test
    fun `ALWAYS preference with a cross-border VAT-ID never shows the dialog and submits true`() {
        defaultSender.edit { vatId = VatId("DE123456789") }
        recipient.edit {
            vatId = VatId("LU12345678")
            reverseChargePreference = ReverseChargePreference.ALWAYS
        }
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)
        whenever(createInvoiceUseCase.createInvoiceWithRecipient(1L, 10L, true)).thenReturn(minimalCreatedDto())

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()
        recipientCombo(dialog).value = recipient.toRecipientSummaryDto()

        assertFalse(reverseChargeDecision(dialog).isVisible, "ALWAYS never shows the radio group")
        assertTrue(createButton(dialog).isEnabled)

        createButton(dialog).click()
        verify(createInvoiceUseCase).createInvoiceWithRecipient(1L, 10L, true)
    }

    @Test
    fun `NEVER preference with a cross-border VAT-ID never shows the dialog and submits false`() {
        defaultSender.edit { vatId = VatId("DE123456789") }
        recipient.edit {
            vatId = VatId("LU12345678")
            reverseChargePreference = ReverseChargePreference.NEVER
        }
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)
        whenever(createInvoiceUseCase.createInvoiceWithRecipient(1L, 10L, false)).thenReturn(minimalCreatedDto())

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()
        recipientCombo(dialog).value = recipient.toRecipientSummaryDto()

        assertFalse(reverseChargeDecision(dialog).isVisible, "NEVER never shows the radio group")
        assertTrue(createButton(dialog).isEnabled)

        createButton(dialog).click()
        verify(createInvoiceUseCase).createInvoiceWithRecipient(1L, 10L, false)
    }

    @Test
    fun `ASK preference with a matching seller registration shows the dialog with Yes disabled`() {
        defaultSender.edit { vatId = VatId("DE123456789") }
        defaultSender.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU99998888"))), today = FIXED_TODAY)
        recipient.edit { vatId = VatId("LU12345678") }
        // Fix H — the matching-registration check is address-based (deriveSellerVatRegistrationCountry),
        // not VAT-ID-based; the recipient needs a real billing address for this scenario to trigger.
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Rue Exemple 5", "1000", "Luxembourg", Country.LU, true))
        recipient.edit { reverseChargePreference = ReverseChargePreference.ASK }
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()
        recipientCombo(dialog).value = recipient.toRecipientSummaryDto()

        val decision = reverseChargeDecision(dialog)
        assertTrue(decision.isVisible, "ASK forces the dialog open even though a matching seller registration would otherwise resolve it silently")
        assertFalse(decision.itemEnabledProvider.test(true), "\"Yes\" is guaranteed-ineligible while a matching seller registration is active")
        assertTrue(decision.itemEnabledProvider.test(false), "\"No\" stays selectable")
        assertNull(decision.value, "no answer is pre-selected — the office manager must actively choose \"No\"")
    }

    @Test
    fun `ASK preference with a matching seller registration still disables Yes when the VAT-ID alone would have hidden it`() {
        // Fix H's exact original fixture shape (SystemWorkflowCrossBorderVatIT): a DE-format
        // VAT-ID that was never updated to match a country change, while the recipient's real,
        // billed country is LU. Before Fix H reached resolveReverseChargeCreationDecision too,
        // deriveSellerVatRegistrationCountry("DE111111111", ...) would have derived Country.DE
        // (rejected outright) and never noticed the seller's real LU registration — this dialog
        // would have wrongly left "Yes" enabled.
        defaultSender.edit { vatId = VatId("DE123456789") }
        defaultSender.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU99998888"))), today = FIXED_TODAY)
        recipient.edit { vatId = VatId("DE111111111") }
        recipient.addAddress(Address.forRecipient(recipient, Address.AddressType.BILLING, "Rue Exemple 5", "1000", "Luxembourg", Country.LU, true))
        recipient.edit { reverseChargePreference = ReverseChargePreference.ASK }
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()
        recipientCombo(dialog).value = recipient.toRecipientSummaryDto()

        val decision = reverseChargeDecision(dialog)
        // Both VAT-IDs read as DE here (requiresReverseChargeDecision alone would say "no
        // question needed") — it is entirely hasMatchingRegistration, driven by the recipient's
        // real LU address, that forces this dialog open at all.
        assertTrue(decision.isVisible, "a matching seller registration for the recipient's real country still forces ASK open")
        assertFalse(decision.itemEnabledProvider.test(true), "the seller's real LU registration matches the recipient's real (address) country, not its VAT-ID")
        assertTrue(decision.itemEnabledProvider.test(false), "\"No\" stays selectable")
    }

    @Test
    fun `switching the recipient combo from an ALWAYS recipient to an ASK recipient re-shows the radio group`() {
        defaultSender.edit { vatId = VatId("DE123456789") }
        recipient.edit {
            vatId = VatId("LU12345678")
            reverseChargePreference = ReverseChargePreference.ALWAYS
        }
        val askRecipient = aRecipient {
            id = 11L
            name = "Ask-Kunde AG"
            customerNumber = CustomerNumber("KD-0011")
            vatId = VatId("LU87654321")
            reverseChargePreference = ReverseChargePreference.ASK
        }
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()
        recipientCombo(dialog).value = recipient.toRecipientSummaryDto()
        assertFalse(reverseChargeDecision(dialog).isVisible, "ALWAYS recipient: no dialog yet")

        recipientCombo(dialog).value = askRecipient.toRecipientSummaryDto()

        val decision = reverseChargeDecision(dialog)
        assertTrue(decision.isVisible, "switching to an ASK recipient re-evaluates and shows the radio group")
        assertNull(decision.value, "the previous ALWAYS-supplied answer must not leak into the new ASK state")
        assertTrue(decision.itemEnabledProvider.test(true), "no matching seller registration here — both options stay selectable")
    }

    // ── create-click exception branches ──────────────────────────────────────────

    @Test
    fun `a RecipientNotFoundException from create shows its own message`() {
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)
        whenever(createInvoiceUseCase.createInvoiceWithRecipient(any(), any(), anyOrNull())).thenThrow(RecipientNotFoundException(10L))

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()
        recipientCombo(dialog).value = recipient.toRecipientSummaryDto()

        createButton(dialog).click()

        assertNotificationShown("Recipient not found: 10")
    }

    @Test
    fun `a SenderProfileNotFoundException from create shows its own message`() {
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)
        whenever(createInvoiceUseCase.createInvoiceWithRecipient(any(), any(), anyOrNull())).thenThrow(SenderProfileNotFoundException(1L))

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()
        recipientCombo(dialog).value = recipient.toRecipientSummaryDto()

        createButton(dialog).click()

        assertNotificationShown("Sender profile not found: 1")
    }

    @Test
    fun `an IllegalArgumentException from create shows its own message`() {
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)
        whenever(createInvoiceUseCase.createInvoiceWithRecipient(any(), any(), anyOrNull())).thenThrow(IllegalArgumentException("boom"))

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()
        recipientCombo(dialog).value = recipient.toRecipientSummaryDto()

        createButton(dialog).click()

        assertNotificationShown("boom")
    }

    @Test
    fun `an unexpected exception from create falls back to the generic error message`() {
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)
        whenever(createInvoiceUseCase.createInvoiceWithRecipient(any(), any(), anyOrNull())).thenThrow(RuntimeException("oops"))

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()
        recipientCombo(dialog).value = recipient.toRecipientSummaryDto()

        createButton(dialog).click()

        assertNotificationShown(UiTranslations.tr("invoice.error.create", UiTranslations.tr("message.error.generic")))
    }

    @Test
    fun `an InvoiceValidationException from create surfaces the resolver's own reason, not a generic toast`() {
        // The case an ALWAYS-implied "Yes" (or a matching-registration-guarded ASK "Yes") turns
        // out ineligible: the office manager didn't click anything this time, so the toast must
        // explain why, not show a blank "creation failed".
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)
        val reason = "Empfänger erfüllt die Voraussetzungen für Reverse Charge nicht mehr."
        whenever(createInvoiceUseCase.createInvoiceWithRecipient(any(), any(), anyOrNull())).thenThrow(
            InvoiceValidationException(InvoiceValidationError.REVERSE_CHARGE_RECIPIENT_NOT_ELIGIBLE, listOf(reason)),
        )

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()
        recipientCombo(dialog).value = recipient.toRecipientSummaryDto()

        createButton(dialog).click()

        assertNotificationShown(UiTranslations.tr("domain.validation.reverseChargeRecipientNotEligible", reason))
    }

    // ── recipient preselection ───────────────────────────────────────────────────

    @Test
    fun `a preselected recipient lands in the combo even as a detached instance`() {
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)
        // Detached instance carrying only the id — Recipient.equals is id-based (see its own
        // KDoc), so this must still select the same combo item as the identical-by-id recipient
        // the data provider would have loaded.
        val detachedSameId = aRecipient { id = 10L; name = "Stale copy" }

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase, preselectedRecipient = detachedSameId.toRecipientSummaryDto()) { }
        dialog.open()

        // Structural DTO equality no longer applies -- the assertion is the shared id.
        assertEquals(recipient.id, recipientCombo(dialog).value?.id)
    }

    // ── hint text for zero vs. n recipients ──────────────────────────────────────

    @Test
    fun `hint text and disabled controls when no recipients exist`() {
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(0L)

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()

        assertEquals(UiTranslations.tr("invoice.dialog.recipient.none"), hintParagraph(dialog).text)
        assertFalse(recipientCombo(dialog).isEnabled)
        assertFalse(createButton(dialog).isEnabled)
    }

    @Test
    fun `hint text and enabled controls when recipients exist`() {
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()

        assertEquals(UiTranslations.tr("invoice.dialog.recipient.available", 1), hintParagraph(dialog).text)
        assertTrue(recipientCombo(dialog).isEnabled)
        assertTrue(createButton(dialog).isEnabled)
    }

    // ── recipient item label — with/without shortHash, with/without city ────────

    @Test
    fun `item label without shortHash and without city omits both`() {
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()

        val label = recipientCombo(dialog).itemLabelGenerator.apply(recipient.toRecipientSummaryDto())

        assertEquals(UiTranslations.tr("invoice.dialog.recipient.itemLabel", "Testkunde AG", "", "KD-0001"), label)
    }

    @Test
    fun `item label includes the billing city when present`() {
        val withCity = aRecipient { id = 11L; name = "Kunde Berlin GmbH"; customerNumber = CustomerNumber("KD-0011") }
        withCity.addAddress(Address.forRecipient(withCity, Address.AddressType.BILLING, "Str. 1", "10115", "Berlin", Country.DE, true))
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()

        val label = recipientCombo(dialog).itemLabelGenerator.apply(withCity.toRecipientSummaryDto())

        assertEquals(UiTranslations.tr("invoice.dialog.recipient.itemLabel", "Kunde Berlin GmbH", "Berlin", "KD-0011"), label)
    }

    @Test
    fun `item label appends the shortHash when present`() {
        val withHash = aRecipient { id = 12L; name = "Kurzname AG"; customerNumber = CustomerNumber("KD-0012"); shortHash = "aBcDeFgHiJ" }
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()

        val label = recipientCombo(dialog).itemLabelGenerator.apply(withHash.toRecipientSummaryDto())

        val base = UiTranslations.tr("invoice.dialog.recipient.itemLabel", "Kurzname AG", "", "KD-0012")
        assertEquals("$base · aBcDeFgHiJ", label)
    }

    @Test
    fun `item label ignores a blank shortHash the same as a missing one`() {
        val blankHash = aRecipient { id = 13L; name = "Leere Kennung AG"; customerNumber = CustomerNumber("KD-0013"); shortHash = "   " }
        whenever(senderQuery.findAllSenders()).thenReturn(listOf(defaultSender.toSenderSummaryDto()))
        whenever(recipientQuery.countRecipients(null)).thenReturn(1L)

        val dialog = InvoiceCreateDialog(recipientQuery, senderQuery, createInvoiceUseCase) { }
        dialog.open()

        val label = recipientCombo(dialog).itemLabelGenerator.apply(blankHash.toRecipientSummaryDto())

        assertEquals(UiTranslations.tr("invoice.dialog.recipient.itemLabel", "Leere Kennung AG", "", "KD-0013"), label)
    }
}
