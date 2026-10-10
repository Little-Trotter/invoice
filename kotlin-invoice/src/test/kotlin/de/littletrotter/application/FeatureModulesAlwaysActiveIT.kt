package de.littletrotter.application

import de.littletrotter.application.invoice.InvoiceFormModuleRegistry
import de.littletrotter.application.invoice.InvoiceFormatModule
import de.littletrotter.application.invoice.ProjectNumberModule
import de.littletrotter.application.masterdata.ArticleTaxVariantsExtensionRegistry
import de.littletrotter.application.masterdata.ArticleTaxVariantsModule
import de.littletrotter.application.masterdata.ElectronicAddressModule
import de.littletrotter.application.masterdata.FacturXPartyFormExtension
import de.littletrotter.application.masterdata.PartyFormExtensionRegistry
import de.littletrotter.application.masterdata.SellerVatRegistrationsExtensionRegistry
import de.littletrotter.application.masterdata.SellerVatRegistrationsModule
import de.littletrotter.spi.party.PartyKind
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the merged invariant: the six field-modules/extensions that used to be gated behind a
 * separately built edition (`InvoiceFormatModule`, `ProjectNumberModule`,
 * `ElectronicAddressModule`, `FacturXPartyFormExtension`, `SellerVatRegistrationsModule`,
 * `ArticleTaxVariantsModule`) are discovered by their registries and contribute unconditionally
 * — there is no gate left to disable them — and [ApplicationIdentity] reports the single, merged
 * product identity.
 *
 * Runs as a `@QuarkusTest` against a real Arc container (Testcontainers Postgres), the same way
 * [de.littletrotter.domain.invoice.InvoiceOptimisticLockingTest] does, because the point is to
 * prove real CDI discovery, not a hand-wired list.
 *
 * Runs as a failsafe integration test (not surefire): [PostgresTestResource] starts a real
 * Postgres Testcontainer, which needs a Docker daemon that isn't available in the plain
 * unit-test environment.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class FeatureModulesAlwaysActiveIT {

    @Inject
    lateinit var invoiceFormModuleRegistry: InvoiceFormModuleRegistry

    @Inject
    lateinit var partyFormExtensionRegistry: PartyFormExtensionRegistry

    @Inject
    lateinit var sellerVatRegistrationsExtensionRegistry: SellerVatRegistrationsExtensionRegistry

    @Inject
    lateinit var articleTaxVariantsExtensionRegistry: ArticleTaxVariantsExtensionRegistry

    @Inject
    lateinit var applicationIdentity: ApplicationIdentity

    @Test
    fun `both invoice form modules are registered and contribute fields`() {
        val modules = invoiceFormModuleRegistry.modules()
        assertTrue(modules.any { it is InvoiceFormatModule }, "InvoiceFormatModule must be discovered")
        assertTrue(modules.any { it is ProjectNumberModule }, "ProjectNumberModule must be discovered")
    }

    @Test
    fun `both party form extensions are registered and contribute pairs unconditionally`() {
        val extensions = partyFormExtensionRegistry.extensions()
        assertTrue(extensions.any { it is ElectronicAddressModule }, "ElectronicAddressModule must be discovered")
        assertTrue(extensions.any { it is FacturXPartyFormExtension }, "FacturXPartyFormExtension must be discovered")

        assertTrue(
            partyFormExtensionRegistry.enabledAspects(PartyKind.SELLER).isNotEmpty(),
            "the electronic-address pair must be visible for the seller without any gate",
        )
        assertTrue(
            partyFormExtensionRegistry.enabledAspects(PartyKind.BUYER)
                .any { (_, extension) -> extension is FacturXPartyFormExtension },
            "the Ardennes-X SIRET/NAF pair must be visible for the buyer without any gate",
        )
    }

    @Test
    fun `the seller-VAT-registrations extension is registered and unconditionally enabled`() {
        val extension = sellerVatRegistrationsExtensionRegistry.enabledExtension()
        assertTrue(extension is SellerVatRegistrationsModule)
    }

    @Test
    fun `the article-tax-variants extension is registered and unconditionally enabled`() {
        val extension = articleTaxVariantsExtensionRegistry.enabledExtension()
        assertTrue(extension is ArticleTaxVariantsModule)
    }

    @Test
    fun `application identity reports the merged product name`() {
        assertEquals("Little Trotter", applicationIdentity.displayName)
    }
}
