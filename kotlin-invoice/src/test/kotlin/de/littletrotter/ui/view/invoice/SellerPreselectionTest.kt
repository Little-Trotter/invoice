package de.littletrotter.ui.view.invoice

import de.littletrotter.application.dto.SenderSummaryDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Every branch of [resolvePreselectedSeller] exercised in isolation. */
class SellerPreselectionTest {

    private fun sellerOf(id: Long, name: String, isDefault: Boolean = false) = SenderSummaryDto(
        id = id,
        name = name,
        vatId = null,
        isDefault = isDefault,
        billingAddressCity = null,
    )

    @Test
    fun `a profile carrying the default flag wins over every other profile`() {
        val other = sellerOf(1L, "Acme")
        val default = sellerOf(2L, "Beta", isDefault = true)

        assertSame(default, resolvePreselectedSeller(listOf(other, default)))
    }

    @Test
    fun `the first default wins if data ever carried several`() {
        val firstDefault = sellerOf(1L, "Acme", isDefault = true)
        val secondDefault = sellerOf(2L, "Beta", isDefault = true)

        assertSame(firstDefault, resolvePreselectedSeller(listOf(firstDefault, secondDefault)))
    }

    @Test
    fun `no default but exactly one profile - that profile wins`() {
        val onlyProfile = sellerOf(1L, "Acme")

        assertEquals(onlyProfile, resolvePreselectedSeller(listOf(onlyProfile)))
    }

    @Test
    fun `no default and several profiles - null, the user must choose`() {
        val first = sellerOf(1L, "Acme")
        val second = sellerOf(2L, "Beta")

        assertNull(resolvePreselectedSeller(listOf(first, second)))
    }

    @Test
    fun `no profiles at all - null`() {
        assertNull(resolvePreselectedSeller(emptyList()))
    }
}
