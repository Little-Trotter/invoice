package de.littletrotter.domain.masterdata

import de.littletrotter.fixture.edit
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.VatId
import de.littletrotter.test.FIXED_TODAY

/** Values derived from datagen --seed 42 (LU sender and FR recipient). */
class SellerVatRegistrationReconciliationTest {
    private val profile = SenderProfile()
    private fun fr() = SellerVatRegistration.of(profile, Country.FR, VatId("FR94019655698"), today = FIXED_TODAY)

    @Test
    fun `adding FR retains the existing LU entity identity and version`() {
        profile.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))), today = FIXED_TODAY)
        val existing = profile.additionalVatRegistrations.single().edit { id = 12L; version = 3L }
        profile.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, existing.vatId!!), SellerVatRegistrationCommand(Country.FR, fr().vatId!!)), today = FIXED_TODAY)
        assertSame(existing, profile.additionalVatRegistrations.first { it.country == Country.LU })
        assertEquals(12L, existing.id)
        assertEquals(3L, existing.version)
        assertEquals(setOf(Country.LU, Country.FR), profile.additionalVatRegistrations.map { it.country }.toSet())
        profile.additionalVatRegistrations.forEach { assertSame(profile, it.senderProfile) }
    }

    @Test
    fun `updating VAT changes the existing row and removes only omitted countries`() {
        profile.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678")), SellerVatRegistrationCommand(Country.FR, fr().vatId!!)), today = FIXED_TODAY)
        val existing = profile.additionalVatRegistrations.first { it.country == Country.LU }.edit { id = 12L }
        profile.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU23456789"))), today = FIXED_TODAY)
        assertSame(existing, profile.additionalVatRegistrations.single())
        assertEquals("LU23456789", existing.vatId?.value)
    }

    @Test
    fun `submitting the same list is safe and an empty list removes registrations`() {
        profile.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))), today = FIXED_TODAY)
        profile.updateVatRegistrations(profile.additionalVatRegistrations.map { SellerVatRegistrationCommand(it.country, it.vatId!!) }, today = FIXED_TODAY)
        assertEquals(1, profile.additionalVatRegistrations.size)
        profile.updateVatRegistrations(emptyList(), today = FIXED_TODAY)
        assertTrue(profile.additionalVatRegistrations.isEmpty())
    }

    @Test
    fun `duplicate countries are rejected without changing existing values`() {
        profile.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))), today = FIXED_TODAY)
        val existing = profile.additionalVatRegistrations.single()
        assertThrows(InvalidParameterException::class.java) {
            profile.updateVatRegistrations(
                listOf(SellerVatRegistrationCommand(Country.FR, fr().vatId!!), SellerVatRegistrationCommand(Country.FR, fr().vatId!!)), today = FIXED_TODAY,
            )
        }
        assertSame(existing, profile.additionalVatRegistrations.single())
    }

    @Test
    fun `typed replacement validates every command before mutating the aggregate`() {
        profile.updateVatRegistrations(listOf(SellerVatRegistrationCommand(Country.LU, VatId("LU12345678"))), today = FIXED_TODAY)
        val existing = profile.additionalVatRegistrations.single()

        assertThrows(InvalidParameterException::class.java) {
            profile.updateVatRegistrations(
                listOf(
                    SellerVatRegistrationCommand(Country.LU, VatId("LU23456789")),
                    SellerVatRegistrationCommand(Country.FR, VatId("not-a-vat-id")),
                ), today = FIXED_TODAY,
            )
        }

        assertSame(existing, profile.additionalVatRegistrations.single())
        assertEquals("LU12345678", existing.vatId?.value)
    }
}
