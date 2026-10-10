package de.littletrotter.ui.view.invoice

import com.vaadin.flow.component.html.Div
import de.littletrotter.application.dto.InvoiceDetailDto
import de.littletrotter.application.invoice.port.inbound.AttachReceiptsUseCase
import de.littletrotter.application.invoice.port.inbound.InvoiceReceiptQueryPort
import de.littletrotter.domain.masterdata.Receipt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * [ReceiptTargetGateway.fetchAssignable]/[ReceiptTargetGateway.countAssignable]'s
 * `excludeReceiptIds` default parameter (empty set) is only ever exercised through
 * [ReceiptPoolPane], which always passes an explicit `thisInvoiceReceiptIds` argument at every one
 * of its call sites — so the default-value expression itself (jacoco reports the function
 * signature line as entirely unexecuted) never runs there. [ReceiptTargetGateway] is `internal`,
 * so a caller in this same module invoking its public API without that argument is real, reachable
 * usage, not a synthetic hack — same class as any other caller of an internal type.
 */
class ReceiptTargetGatewayTest {

    private val invoiceReceiptQueryPort: InvoiceReceiptQueryPort = mock()
    private val attachReceiptsUseCase: AttachReceiptsUseCase = mock()
    private val invoice = InvoiceDetailDto(
        id = 42L, invoiceNumber = "RE-2026-0001", status = 0, invoiceDate = null,
        netAmount = null, vatAmount = null, grossAmount = null,
        sender = null, recipient = null, items = emptyList(), latestGenerationJob = null,
    )

    private fun gateway(target: ReceiptTarget) =
        ReceiptTargetGateway(invoiceReceiptQueryPort, attachReceiptsUseCase, invoice, target, Div())

    @Test
    fun `fetchAssignable without an explicit exclude set uses the empty default`() {
        val g = gateway(ReceiptTarget.Item(10L))
        whenever(invoiceReceiptQueryPort.findAssignableForItem(10L, 0, 20, "x", emptySet())).thenReturn(emptyList())

        val result = g.fetchAssignable(0, 20, "x")

        assertEquals(emptyList<Receipt>(), result)
        verify(invoiceReceiptQueryPort).findAssignableForItem(10L, 0, 20, "x", emptySet())
    }

    @Test
    fun `countAssignable without an explicit exclude set uses the empty default`() {
        val g = gateway(ReceiptTarget.Item(10L))
        whenever(invoiceReceiptQueryPort.countAssignableForItem(10L, "x", emptySet())).thenReturn(3L)

        val result = g.countAssignable("x")

        assertEquals(3L, result)
        verify(invoiceReceiptQueryPort).countAssignableForItem(10L, "x", emptySet())
    }
}
