package de.littletrotter.application.reference

import de.littletrotter.application.dto.B2bProfileDto
import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.application.dto.B2bSellerFieldGroupDto
import de.littletrotter.application.dto.ResolvedB2bFieldValueDto
import de.littletrotter.application.reference.port.inbound.B2bFieldDefinitionQueryPort
import de.littletrotter.application.reference.port.inbound.B2bProfileQueryPort
import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.reference.B2bFieldOwnerType
import de.littletrotter.domain.reference.B2bFieldRequirement
import de.littletrotter.domain.reference.B2bFieldValueOrigin
import de.littletrotter.domain.reference.B2bPlacement
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.reference.B2bSellerFieldGroup
import de.littletrotter.domain.reference.ResolvedB2bFieldValue
import de.littletrotter.shared.b2b.B2bTerm
import jakarta.persistence.OptimisticLockException
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

/** The B2B profile, field-definition and field-value ports delegate to their services and map
 *  the stored rows to their read models. */
class B2bPortsTest {

    @Test
    fun `B2bProfileQueries delegates paging, counts, lookups and usage`() {
        val lifecycle = mock<B2bProfileLifecycleService>()
        val usage = mock<B2bProfileUsageService>()
        val stored = storedProfile()
        val profile = stored.toDto()
        whenever(lifecycle.findProfiles(0, 60, null, SortDirection.ASC)).thenReturn(listOf(stored))
        whenever(lifecycle.countProfiles(null)).thenReturn(2L)
        whenever(lifecycle.countInactiveProfiles(null)).thenReturn(1L)
        whenever(lifecycle.findProfileById(4L)).thenReturn(stored)
        whenever(lifecycle.findAllActiveProfiles()).thenReturn(listOf(stored))
        whenever(usage.usageCounts()).thenReturn(mapOf(4L to 3L))
        whenever(usage.usageNames(4L)).thenReturn(listOf("Kunde A"))
        val queries: B2bProfileQueryPort = B2bProfileQueries(lifecycle, usage)

        assertEquals(listOf(B2bProfileDto(4L, 2L, "Nekar", "Neckar-Kunden", false, setOf(Country.FR))), queries.findB2bProfiles())
        assertEquals(2L, queries.countB2bProfiles())
        assertEquals(1L, queries.countInactiveB2bProfiles())
        assertEquals(profile, queries.findB2bProfileById(4L))
        assertEquals(listOf(profile), queries.findAllActiveB2bProfiles())
        assertEquals(mapOf(4L to 3L), queries.findB2bProfileUsageCounts())
        assertEquals(listOf("Kunde A"), queries.findB2bProfileUsageNames(4L))
        val today = java.time.LocalDate.of(2026, 6, 1)
        assertEquals(B2bProfile.allowedCountryScope(today), queries.allowedB2bCountryScope(today))
    }

    @Test
    fun `ManageB2bProfile saves, deletes and copies through the lifecycle service`() {
        val lifecycle = mock<B2bProfileLifecycleService>()
        whenever(lifecycle.saveProfile(any())).thenAnswer { (it.arguments[0] as B2bProfile).edit { id = 9L } }
        whenever(lifecycle.copy(4L)).thenReturn(storedProfile())
        val manage = ManageB2bProfile(lifecycle)

        val saved = manage.saveB2bProfile(B2bProfileDto(name = "Neu", description = "d", active = false, countries = setOf(Country.BE)))

        assertEquals(B2bProfileDto(9L, 0L, "Neu", "d", false, setOf(Country.BE)), saved)
        assertEquals(storedProfile().toDto(), manage.copyB2bProfile(4L))
        manage.deleteB2bProfile(4L)
        verify(lifecycle).deleteProfile(4L)
    }

    @Test
    fun `ManageB2bProfile writes an edit onto the stored row and replaces its country scope`() {
        val lifecycle = mock<B2bProfileLifecycleService>()
        val stored = storedProfile()
        whenever(lifecycle.findProfileById(4L)).thenReturn(stored)
        whenever(lifecycle.saveProfile(any())).thenAnswer { it.arguments[0] as B2bProfile }

        val saved = ManageB2bProfile(lifecycle).saveB2bProfile(
            stored.toDto().copy(name = "Umbenannt", countries = setOf(Country.LU)),
        )

        assertEquals("Umbenannt", stored.name)
        assertEquals(setOf(Country.LU), stored.countries)
        assertEquals(4L, saved.id)
    }

    @Test
    fun `ManageB2bProfile refuses an edit started from an older revision`() {
        val lifecycle = mock<B2bProfileLifecycleService>()
        whenever(lifecycle.findProfileById(4L)).thenReturn(storedProfile())

        assertThrows(OptimisticLockException::class.java) {
            ManageB2bProfile(lifecycle).saveB2bProfile(storedProfile().toDto().copy(version = 1L))
        }
    }

    private fun storedProfile() = B2bProfile().edit {
        id = 4L
        version = 2L
        name = "Nekar"
        description = "Neckar-Kunden"
        active = false
        countries = mutableSetOf(Country.FR)
    }

    private fun storedField(profile: B2bProfile? = storedProfile()) = B2bProfileField().edit {
        id = 8L
        version = 3L
        this.profile = profile
        term = B2bTerm.BT_10
        businessDefinition = "Käuferreferenz"
        requirement = B2bFieldRequirement.CONDITIONAL
        conditionTerm = B2bTerm.BT_13
        placement = B2bPlacement.RECIPIENT
        sortOrder = 2
        defaultValue = "REF"
    }

    @Test
    fun `B2bFieldDefinitionQueries delegates every field list to the service`() {
        val service = mock<B2bProfileFieldDefinitionService>()
        val stored = storedField()
        val field = B2bProfileFieldDto(
            id = 8L,
            version = 3L,
            profileId = 4L,
            term = B2bTerm.BT_10,
            businessDefinition = "Käuferreferenz",
            requirement = B2bFieldRequirement.CONDITIONAL,
            conditionTerm = B2bTerm.BT_13,
            placement = B2bPlacement.RECIPIENT,
            sortOrder = 2,
            defaultValue = "REF",
        )
        whenever(service.findFields(4L)).thenReturn(listOf(stored))
        whenever(service.findSelectableCatalogEntries(4L, null)).thenReturn(emptyList())
        whenever(service.findSellerFields(4L)).thenReturn(listOf(stored))
        whenever(service.findSellerFieldsUnion()).thenReturn(listOf(B2bSellerFieldGroup(stored, Country.FR, setOf(Country.FR))))
        whenever(service.findInvoiceHeaderFields(4L)).thenReturn(listOf(stored))
        whenever(service.findInvoiceItemFields(4L)).thenReturn(listOf(stored))
        whenever(service.findRecipientFields(4L)).thenReturn(listOf(stored))
        val queries: B2bFieldDefinitionQueryPort = B2bFieldDefinitionQueries(service)

        assertEquals(listOf(field), queries.findB2bProfileFields(4L))
        assertEquals(emptyList<Any>(), queries.findSelectableB2bCatalogEntries(4L))
        assertEquals(listOf(field), queries.findB2bSellerFields(4L))
        assertEquals(listOf(B2bSellerFieldGroupDto(field, Country.FR, setOf(Country.FR))), queries.findB2bSellerFieldsUnion())
        assertEquals(listOf(field), queries.findB2bInvoiceHeaderFields(4L))
        assertEquals(listOf(field), queries.findB2bInvoiceItemFields(4L))
        assertEquals(listOf(field), queries.findB2bRecipientFields(4L))
    }

    @Test
    fun `B2bFieldDefinitionQueries returns no fields for a missing profile without asking the service`() {
        val service = mock<B2bProfileFieldDefinitionService>()
        val queries = B2bFieldDefinitionQueries(service)

        assertEquals(emptyList<B2bProfileFieldDto>(), queries.findB2bInvoiceHeaderFields(null))
        assertEquals(emptyList<B2bProfileFieldDto>(), queries.findB2bInvoiceItemFields(null))
        assertEquals(emptyList<B2bProfileFieldDto>(), queries.findB2bRecipientFields(null))
        verifyNoInteractions(service)
    }

    @Test
    fun `ManageB2bFieldDefinition saves and deletes through the service`() {
        val service = mock<B2bProfileFieldDefinitionService>()
        val lifecycle = mock<B2bProfileLifecycleService>()
        val owner = storedProfile()
        whenever(lifecycle.findProfileById(4L)).thenReturn(owner)
        whenever(service.saveField(any())).thenAnswer { (it.arguments[0] as B2bProfileField).edit { id = 8L } }
        val manage = ManageB2bFieldDefinition(service, lifecycle)

        val saved = manage.saveB2bProfileField(
            B2bProfileFieldDto(profileId = 4L, term = B2bTerm.BT_10, businessDefinition = "Käuferreferenz", sortOrder = 2),
        )

        val captor = argumentCaptor<B2bProfileField>()
        verify(service).saveField(captor.capture())
        assertSame(owner, captor.firstValue.profile)
        assertEquals(2, captor.firstValue.sortOrder)
        assertEquals(8L, saved.id)
        assertEquals(4L, saved.profileId)
        manage.deleteB2bProfileField(8L)
        verify(service).deleteField(8L)
    }

    @Test
    fun `ManageB2bFieldDefinition edits the stored field and leaves an unknown profile for the service to reject`() {
        val service = mock<B2bProfileFieldDefinitionService>()
        val lifecycle = mock<B2bProfileLifecycleService>()
        val stored = storedField()
        whenever(service.findFieldById(8L)).thenReturn(stored)
        whenever(service.saveField(any())).thenAnswer { it.arguments[0] as B2bProfileField }

        val saved = ManageB2bFieldDefinition(service, lifecycle).saveB2bProfileField(
            stored.toDto().copy(profileId = 99L, businessDefinition = "geändert"),
        )

        assertEquals("geändert", stored.businessDefinition)
        assertNull(stored.profile, "an unknown profile id must not keep the old owner")
        assertNull(saved.profileId)
    }

    @Test
    fun `B2bFieldValueQueries delegates owner, batch, customer, invoice-profile and guided reads`() {
        val service = mock<B2bFieldValueService>()
        whenever(service.findFieldValues(B2bFieldOwnerType.INVOICE, 1L)).thenReturn(mapOf("BT-10" to "x"))
        whenever(service.findFieldValuesByOwners(B2bFieldOwnerType.INVOICE_ITEM, listOf(2L)))
            .thenReturn(mapOf(2L to mapOf("BT-156" to "y")))
        whenever(service.findRecipientFieldValues(3L, 4L)).thenReturn(mapOf("BT-46" to "z"))
        whenever(service.findProfileIdForInvoice(1L)).thenReturn(null)
        whenever(service.resolvedGuidedFieldValues(1L))
            .thenReturn(mapOf("BT-10" to ResolvedB2bFieldValue("a", B2bFieldValueOrigin.PARTY, "a")))
        val queries = B2bFieldValueQueries(service)

        assertEquals(mapOf("BT-10" to "x"), queries.findB2bFieldValues(B2bFieldOwnerType.INVOICE, 1L))
        assertEquals(mapOf(2L to mapOf("BT-156" to "y")), queries.findB2bFieldValuesForOwners(B2bFieldOwnerType.INVOICE_ITEM, listOf(2L)))
        assertEquals(mapOf("BT-46" to "z"), queries.findB2bRecipientFieldValues(3L, 4L))
        assertNull(queries.findB2bProfileIdForInvoice(1L))
        assertEquals(
            mapOf("BT-10" to ResolvedB2bFieldValueDto("a", B2bFieldValueOrigin.PARTY, "a")),
            queries.findB2bGuidedFieldValues(1L),
        )
    }

    @Test
    fun `ManageB2bFieldValue hands every write to the service`() {
        val service = mock<B2bFieldValueService>()
        val manage = ManageB2bFieldValue(service)
        val values = mapOf("BT-10" to "x")

        manage.saveB2bFieldValues(B2bFieldOwnerType.INVOICE, 1L, values)
        manage.saveB2bRecipientFieldValues(3L, 4L, values)
        manage.copyB2bArticleTemplateToInvoiceItem(5L, 6L, 1L)

        verify(service).saveFieldValues(B2bFieldOwnerType.INVOICE, 1L, values)
        verify(service).saveRecipientFieldValues(3L, 4L, values)
        verify(service).copyLineTemplateValuesToInvoiceItem(5L, 6L, 1L)
    }
}
