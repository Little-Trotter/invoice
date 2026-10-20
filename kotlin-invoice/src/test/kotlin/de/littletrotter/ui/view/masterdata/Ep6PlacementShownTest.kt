package de.littletrotter.ui.view.masterdata

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.formlayout.FormLayout
import com.vaadin.flow.component.textfield.TextField
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.application.dto.B2bSellerFieldGroupDto
import de.littletrotter.application.dto.RecipientDetailDto
import de.littletrotter.application.masterdata.AddressManagementService
import de.littletrotter.application.masterdata.PartyFormExtensionRegistry
import de.littletrotter.application.masterdata.RecipientB2bFieldsQueries
import de.littletrotter.application.masterdata.RecipientDetailMapper
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.toDto
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.value.CustomerNumber
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.edit
import de.littletrotter.shared.b2b.B2bTerm
import de.littletrotter.test.FIXED_TIME_SOURCE
import de.littletrotter.ui.component.B2bProfileFieldsSection
import de.littletrotter.ui.component.ScopedB2bProfileFieldsSection
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.application.masterdata.port.inbound.VerifyVatIdUseCase

/**
 * Shown for the two placements no existing suite drives a real view for across every
 * requirement: [de.littletrotter.application.reference.B2bPlacement.RECIPIENT] ([RecipientView])
 * and [de.littletrotter.application.reference.B2bPlacement.SELLER] ([SenderProfileView]).
 * [RecipientViewKaribuTest]/[SenderProfileViewKaribuTest] already prove the section renders at
 * all (`hasContent`) for one fixed requirement each — this file's own contribution is the
 * requirement sweep: does [de.littletrotter.ui.component.B2bFieldWidgetFactory]'s required
 * indicator behave identically at these two placements as it does at INVOICE (only MANDATORY ever
 * sets it, CONDITIONAL/CONDITIONAL_IF_EMPTY never do, even
 * while currently required) — proving the gap is placement-independent, not an INVOICE-only quirk.
 */
class Ep6PlacementShownTest {

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
    }

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private fun widgetTextField(section: B2bProfileFieldsSection): TextField =
        section.children.toList().filterIsInstance<FormLayout>().single().children.toList().filterIsInstance<TextField>().single()

    private fun widgetTextField(section: ScopedB2bProfileFieldsSection): TextField =
        section.children.toList().filterIsInstance<com.vaadin.flow.component.html.Div>()
            .flatMap { it.children.toList() }.filterIsInstance<FormLayout>().single()
            .children.toList().filterIsInstance<TextField>().single()

    // ─── RECIPIENT placement (RecipientView) ──────────────────────────────────────────────────────

    private fun recipientField(requirement: B2bFieldRequirement) = B2bProfileFieldDto(
        id = 77L,
        term = B2bTerm.BT_58, // HEADER, FREE, TEXT — the fixture RecipientViewKaribuTest already uses
        businessDefinition = "Käufer-Kontakt-E-Mail",
        placement = B2bPlacement.RECIPIENT,
        requirement = requirement,
        conditionTerm = B2bTerm.BT_12.takeIf { requirement.isConditional() },
    )

    private fun B2bFieldRequirement.isConditional() =
        this == B2bFieldRequirement.CONDITIONAL || this == B2bFieldRequirement.CONDITIONAL_IF_EMPTY

    private fun populate(view: RecipientView, recipient: Recipient) {
        val detail = RecipientDetailMapper(mock<AddressManagementService>(), PartyFormExtensionRegistry(emptyList()))
            .toDetailDto(recipient)
        val m = RecipientView::class.java.getDeclaredMethod("populateForm", RecipientDetailDto::class.java)
        m.isAccessible = true
        m.invoke(view, detail)
    }

    private fun b2bFieldsSection(view: RecipientView): B2bProfileFieldsSection {
        val f = RecipientView::class.java.getDeclaredField("b2bFieldsSection")
        f.isAccessible = true
        return f.get(view) as B2bProfileFieldsSection
    }

    @ParameterizedTest(name = "RECIPIENT placement, requirement={0}")
    @EnumSource(B2bFieldRequirement::class)
    fun `Shown at RECIPIENT placement - the required indicator fires for MANDATORY only, reproducing EP3-F1`(requirement: B2bFieldRequirement) {
        val b2bProfileQueries: de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort = mock()
        val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
        val b2bFieldValueQueries: de.littletrotter.application.reference.port.inbound.B2bFieldValueQueryPort = mock()
        val manageB2bFieldValue: de.littletrotter.application.reference.port.inbound.ManageB2bFieldValueUseCase = mock()
        val profile = B2bProfile().edit { id = 5L; name = "Nekar" }
        whenever(b2bProfileQueries.findAllActiveB2bProfiles()).thenReturn(listOf(profile.toDto()))
        whenever(b2bProfileQueries.findB2bProfileById(5L)).thenReturn(profile.toDto())
        whenever(b2bFieldDefinitionQueries.findB2bRecipientFields(5L)).thenReturn(listOf(recipientField(requirement)))
        whenever(b2bFieldValueQueries.findB2bRecipientFieldValues(1L, 5L)).thenReturn(emptyMap())

        val view = RecipientView(
            mock<de.littletrotter.application.masterdata.port.inbound.ManageRecipientUseCase>(),
            mock<de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort>(),
            de.littletrotter.application.masterdata.PartyFormExtensionRegistry(emptyList()),
            mock<de.littletrotter.application.masterdata.port.inbound.VerifyVatIdUseCase>(),
            RecipientB2bFieldsQueries(b2bProfileQueries, b2bFieldDefinitionQueries, b2bFieldValueQueries, manageB2bFieldValue),
            InvoiceCreationDependencies(
                mock<de.littletrotter.application.masterdata.port.inbound.SenderQueryPort>(),
                mock<de.littletrotter.application.invoice.port.inbound.CreateInvoiceUseCase>(),
            ),
            time = FIXED_TIME_SOURCE,
        )
        val recipient = aRecipient { id = 1L; customerNumber = CustomerNumber("KD-1"); name = "Buyer AG"; b2bProfile = profile }
        populate(view, recipient)

        val section = b2bFieldsSection(view)
        assertTrue(section.hasContent, "the RECIPIENT-placed field must render regardless of requirement")
        val textField = widgetTextField(section)

        if (requirement == B2bFieldRequirement.MANDATORY) {
            assertTrue(textField.isRequiredIndicatorVisible, "MANDATORY must flag the widget required")
        } else {
            assertFalse(textField.isRequiredIndicatorVisible, "EP3-F1 generalizes to RECIPIENT placement: $requirement never gets the indicator")
        }
    }

    // ─── SELLER placement (SenderProfileView) ─────────────────────────────────────────────────────

    private fun sellerFieldGroup(term: B2bTerm, requirement: B2bFieldRequirement) = B2bSellerFieldGroupDto(
        field = B2bProfileFieldDto(
            term = term,
            requirement = requirement,
            placement = B2bPlacement.SELLER,
            businessDefinition = "Definition ${term.officialCode}",
            conditionTerm = B2bTerm.BT_18.takeIf { requirement.isConditional() },
        ),
        group = null,
        triggerCountries = emptySet(),
    )

    private fun scopedSection(view: SenderProfileView): ScopedB2bProfileFieldsSection {
        val f = SenderProfileView::class.java.getDeclaredField("scopedB2bSection")
        f.isAccessible = true
        return f.get(view) as ScopedB2bProfileFieldsSection
    }

    @ParameterizedTest(name = "SELLER placement, requirement={0}")
    @EnumSource(B2bFieldRequirement::class)
    fun `Shown at SELLER placement - the required indicator fires for MANDATORY only, reproducing EP3-F1`(requirement: B2bFieldRequirement) {
        val b2bFieldDefinitionQueries: B2bFieldDefinitionQueryPort = mock()
        // BT-12 (HEADER, FREE) placed SELLER — LxP-HEADER-SELLER, the legal-but-unusual cell
        // Ep6FieldCombinationMatrixTest proves is savable; this is its Shown-at-the-real-view proof.
        whenever(b2bFieldDefinitionQueries.findB2bSellerFieldsUnion()).thenReturn(listOf(sellerFieldGroup(B2bTerm.BT_12, requirement)))

        val view = SenderProfileView(
            ports = SenderProfilePorts(mock(), mock(), mock()),
            b2bSectionComposer = SenderB2bSectionComposer(b2bFieldDefinitionQueries, mock(), mock()),
            defaultsComposer = SenderProfileDefaultsComposer(mock()),
            consumerNoteComposer = de.littletrotter.ui.view.masterdata.SenderConsumerNoteComposer(mock(), mock(), mock()),
            numberRangeQueries = mock(),
            partyFormRegistry = de.littletrotter.application.masterdata.PartyFormExtensionRegistry(emptyList()),
            sellerVatRegistrationsRegistry = de.littletrotter.application.masterdata.SellerVatRegistrationsExtensionRegistry(emptyList()),
        )

        val section = scopedSection(view)
        assertTrue(section.hasContent, "a HEADER-level FREE term placed SELLER must still render on the seller form")
        val textField = widgetTextField(section)

        if (requirement == B2bFieldRequirement.MANDATORY) {
            assertTrue(textField.isRequiredIndicatorVisible, "MANDATORY must flag the widget required")
        } else {
            assertFalse(textField.isRequiredIndicatorVisible, "EP3-F1 generalizes to SELLER placement: $requirement never gets the indicator")
        }
    }
}
