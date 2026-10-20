package de.littletrotter.application.invoice

import de.littletrotter.application.invoice.port.inbound.InvoiceB2bFieldsQueryPort
import de.littletrotter.application.masterdata.AddressManagementService
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.repository.ArticleRepository
import de.littletrotter.domain.repository.B2bProfileRepository
import de.littletrotter.domain.repository.PaymentTermRepository
import de.littletrotter.domain.repository.TaxRateRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * [InvoiceLifecycleSupport.configuredDefaultPageSize] carries a Kotlin default (`= 60`)
 * purely as a defensive fallback for `@ConfigProperty`'s own `defaultValue = "60"`; every real CDI
 * injection point and [InvoiceLifecycleServiceTestBuilder] always pass the value explicitly, so
 * the constructor's own default expression was never exercised before this test.
 */
class InvoiceLifecycleSupportTest {

    @Test
    fun `happy path - configuredDefaultPageSize falls back to 60 when omitted from the constructor call`() {
        val support = InvoiceLifecycleSupport(
            addressManagementService = AddressManagementService(mock<RecipientRepository>(), mock(), mock()),
            paymentTermRepo = mock<PaymentTermRepository>(),
            taxRateRepo = mock<TaxRateRepository>(),
            b2bProfileRepo = mock<B2bProfileRepository>(),
            invoiceB2bFieldsQueryPort = mock<InvoiceB2bFieldsQueryPort>(),
            articleRepo = mock<ArticleRepository>(),
            numberAssigner = InvoiceNumberAssigner { error("not used by this test") },
        )

        assertEquals(60, support.configuredDefaultPageSize)
    }
}
