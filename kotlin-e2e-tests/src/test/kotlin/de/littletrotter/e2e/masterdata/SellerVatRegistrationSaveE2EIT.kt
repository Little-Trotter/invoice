package de.littletrotter.e2e.masterdata

import de.littletrotter.application.masterdata.SellerDefaultSelections
import de.littletrotter.application.masterdata.SellerProfileService
import de.littletrotter.application.masterdata.SellerVatRegistrationsModule
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.shared.country.Country
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import de.littletrotter.e2e.PostgresTestResource
import de.littletrotter.e2e.TempDocumentStoreResource
import de.littletrotter.spi.party.SellerVatRegistrationRow
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/** Synthetic fixture derived from datagen --seed 42, generated 2026-09-16.
 * Each service call commits before the next read; exercises the same module/save path as the UI.
 * PostgreSQL is freshly provisioned by PostgresTestResource, never an existing instance.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@QuarkusTestResource(TempDocumentStoreResource::class)
class SellerVatRegistrationSaveE2EIT {
    @Inject lateinit var service: SellerProfileService
    @Inject lateinit var module: SellerVatRegistrationsModule
    private val lu = SellerVatRegistrationRow("LU", "LU12345678")
    private val fr = SellerVatRegistrationRow("FR", "FR94019655698")

    private fun create(): Long {
        val profile = SenderProfile().apply {
            name = "Deep Space Commerce GmbH"
            invoicePrefix = "DSC001"
            isDefault = false
            taxNumber = TaxNumber("11/754/10433")
            vatId = VatId("DE318196001")
            email = Email.ofNullable("buchhaltung@deepspacecommerce.de")
            iban = Iban.ofNullable("DE79100500003389083863")
        }
        module.write(profile, listOf(lu))
        return service.save(profile, defaults = SellerDefaultSelections()).id!!
    }

    private fun load(id: Long) = requireNotNull(service.findByIdForEdit(id))
    private fun saveRows(id: Long, rows: List<SellerVatRegistrationRow>) {
        val detached = load(id)
        module.write(detached, rows)
        service.save(detached, defaults = SellerDefaultSelections())
    }

    @Test
    fun `stale seller form cannot remove a newly committed FR registration`() {
        val id = create()
        try {
            val stale = load(id)
            val previousVersion = stale.version
            saveRows(id, listOf(lu, fr))
            assertNotEquals(previousVersion, load(id).version)
            assertThrows(InvalidParameterException::class.java) { service.save(stale, defaults = SellerDefaultSelections()) }
            assertEquals(setOf(Country.LU, Country.FR), load(id).additionalVatRegistrations.map { it.country }.toSet())
        } finally { service.delete(id) }
    }

    @Test
    fun `provider accepts FR beside LU and repeated saves preserve row ids`() {
        val id = create()
        try {
            val luId = load(id).additionalVatRegistrations.single().id
            saveRows(id, listOf(lu, fr))
            val saved = load(id)
            assertFalse(saved.isDefault)
            assertEquals("DE318196001", saved.vatId?.value)
            assertEquals(setOf(Country.LU, Country.FR), saved.additionalVatRegistrations.map { it.country }.toSet())
            assertEquals(luId, saved.additionalVatRegistrations.single { it.country == Country.LU }.id)
            val ids = saved.additionalVatRegistrations.associate { it.country to it.id }
            saveRows(id, listOf(fr, lu))
            assertEquals(ids, load(id).additionalVatRegistrations.associate { it.country to it.id })
            saveRows(id, listOf(SellerVatRegistrationRow("LU", "LU23456789"), fr))
            assertEquals("LU23456789", load(id).additionalVatRegistrations.single { it.country == Country.LU }.vatId?.value)
            assertEquals(ids, load(id).additionalVatRegistrations.associate { it.country to it.id })
            saveRows(id, listOf(fr))
            assertEquals(Country.FR, load(id).additionalVatRegistrations.single().country)
            saveRows(id, listOf(fr, lu))
            assertEquals(2, load(id).additionalVatRegistrations.size)
        } finally { service.delete(id) }
    }

    @Test
    fun `duplicate country gives a domain error and leaves committed LU unchanged`() {
        val id = create()
        try {
            val before = load(id).additionalVatRegistrations.single().id
            assertThrows(InvalidParameterException::class.java) { saveRows(id, listOf(lu, fr, fr)) }
            val after = load(id).additionalVatRegistrations.single()
            assertEquals(before, after.id)
            assertEquals(lu.vatId, after.vatId?.value)
        } finally { service.delete(id) }
    }
}
