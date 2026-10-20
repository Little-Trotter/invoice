package de.littletrotter.application.masterdata

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.fixture.aRecipient
import de.littletrotter.fixture.aSenderProfile
import de.littletrotter.fixture.anArticle
import de.littletrotter.fixture.edit
import de.littletrotter.shared.tax.CustomerType
import de.littletrotter.domain.value.Email
import de.littletrotter.domain.value.Iban
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import de.littletrotter.domain.value.TaxNumber
import de.littletrotter.domain.value.VatId

/**
 * N3-05 — saving an existing article/customer/seller without a short hash backfills one.
 * `ensureShortHash()` itself is already tested as a plain entity method
 * ([de.littletrotter.domain.shared.ShortHashTest]); this class proves that every save path
 * actually calls it -- for an *existing* object (`id != null`, not the create-new path),
 * against real PostgreSQL.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class MasterDataShortHashBackfillIT {

    @Inject
    lateinit var customerManagementService: CustomerManagementService

    @Inject
    lateinit var sellerProfileService: SellerProfileService

    @Inject
    lateinit var articleManagementService: ArticleManagementService

    @Test
    fun `N3-05 - an existing recipient without a short hash gets one on save`() {
        val id = QuarkusTransaction.requiringNew().call {
            val recipient = aRecipient {
                name = "Backfill Kunde GmbH"
                email = Email.ofNullable("backfill-kunde@example.invalid")
                customerType = CustomerType.CONSUMER
            }
            customerManagementService.save(recipient).id!!
        }

        val resaved = QuarkusTransaction.requiringNew().call {
            val existing = customerManagementService.findById(id)!!
            assertNotNull(existing.shortHash, "sollte bereits beim ersten Speichern nachgezogen worden sein")
            existing
        }

        assertNotNull(resaved.shortHash)
    }

    @Test
    fun `N3-05 - an existing article without a short hash gets one on save`() {
        val id = QuarkusTransaction.requiringNew().call {
            articleManagementService.save(anArticle { name = "Backfill Artikel" }).id!!
        }

        val article = QuarkusTransaction.requiringNew().call { articleManagementService.findById(id)!! }
        assertNotNull(article.shortHash)
    }

    @Test
    fun `N3-05 - an existing seller profile without a short hash gets one on save`() {
        val id = QuarkusTransaction.requiringNew().call {
            val profile = aSenderProfile {
                name = "Backfill Anbieter GmbH"
                email = Email.ofNullable("backfill-anbieter@example.invalid")
                taxNumber = TaxNumber("12/345/67890")
                vatId = VatId("DE123456789")
                iban = Iban.ofNullable("DE02120300000000202051")
                registerCourt = ""
            }
            sellerProfileService.save(profile, defaults = SellerDefaultSelections()).id!!
        }

        val profile = QuarkusTransaction.requiringNew().call { sellerProfileService.findById(id)!! }
        assertNotNull(profile.shortHash)
    }

    @Test
    fun `N3-05 - saving a recipient a second time never replaces its short hash`() {
        val id = QuarkusTransaction.requiringNew().call {
            val recipient = aRecipient {
                name = "Stabil Kunde GmbH"
                email = Email.ofNullable("stabil-kunde@example.invalid")
                customerType = CustomerType.CONSUMER
            }
            customerManagementService.save(recipient).id!!
        }
        val firstHash = QuarkusTransaction.requiringNew().call { customerManagementService.findById(id)!!.shortHash }

        QuarkusTransaction.requiringNew().call {
            val existing = customerManagementService.findById(id)!!
            existing.edit { name = "Stabil Kunde GmbH (umbenannt)" }
            customerManagementService.save(existing)
        }
        val secondHash = QuarkusTransaction.requiringNew().call { customerManagementService.findById(id)!!.shortHash }

        assertEquals(firstHash, secondHash)
    }
}
