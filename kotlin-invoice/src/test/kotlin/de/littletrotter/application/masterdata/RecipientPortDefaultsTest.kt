package de.littletrotter.application.masterdata

import de.littletrotter.application.masterdata.port.inbound.RecipientQueryPort
import de.littletrotter.application.masterdata.port.outbound.RecipientReadRepository
import de.littletrotter.domain.shared.SortDirection
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/** The default arguments of the recipient read ports are the paging contract every grid relies on. */
class RecipientPortDefaultsTest {

    @Test
    fun `query port findRecipients without arguments asks for the first page of 60 sorted by name`() {
        val port: RecipientQueryPort = mock()

        port.findRecipients()

        verify(port).findRecipients(0, 60, null, "name", SortDirection.ASC)
    }

    @Test
    fun `query port countRecipients without filter passes a null filter`() {
        val port: RecipientQueryPort = mock()

        port.countRecipients()

        verify(port).countRecipients(null)
    }

    @Test
    fun `read repository findRecipients without arguments asks for the first page of 60 sorted by name`() {
        val repository: RecipientReadRepository = mock()

        repository.findRecipients()

        verify(repository).findRecipients(0, 60, null, "name", SortDirection.ASC)
    }

    @Test
    fun `explicit arguments override the defaults`() {
        val repository: RecipientReadRepository = mock()

        repository.findRecipients(pageIndex = 2, filterText = "acme", sortDir = SortDirection.DESC)

        verify(repository).findRecipients(2, 60, "acme", "name", SortDirection.DESC)
    }
}
