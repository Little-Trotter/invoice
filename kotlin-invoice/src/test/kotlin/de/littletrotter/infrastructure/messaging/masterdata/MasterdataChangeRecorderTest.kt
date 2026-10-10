package de.littletrotter.infrastructure.messaging.masterdata

import de.littletrotter.domain.masterdata.Address
import de.littletrotter.domain.masterdata.Article
import de.littletrotter.domain.masterdata.ArticleRecipientRef
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.masterdata.Recipient
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.RecipientB2bFieldValue
import de.littletrotter.domain.reference.TaxRate
import de.littletrotter.infrastructure.messaging.OutboxAppended
import de.littletrotter.infrastructure.messaging.OutboxRepository
import de.littletrotter.shared.masterdata.CustomerDeleted
import jakarta.enterprise.event.Event
import jakarta.transaction.Status
import jakarta.transaction.Synchronization
import jakarta.transaction.TransactionSynchronizationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class MasterdataChangeRecorderTest {

    private val registry = mock<TransactionSynchronizationRegistry>()
    private val source = mock<MasterdataEventSource>()
    private val outbox = mock<OutboxRepository>()
    private val appended = mock<Event<OutboxAppended>>()
    private val resources = HashMap<Any, Any>()
    private var status = Status.STATUS_ACTIVE
    private val syncs = mutableListOf<Synchronization>()

    @BeforeEach
    fun wireRegistry() {
        whenever(registry.transactionStatus).thenAnswer { status }
        whenever(registry.getResource(any())).thenAnswer { resources[it.arguments[0]] }
        org.mockito.kotlin.doAnswer { resources[it.arguments[0]] = it.arguments[1] }.whenever(registry).putResource(any(), any())
        org.mockito.kotlin.doAnswer { syncs.add(it.arguments[0] as Synchronization) }.whenever(registry)
            .registerInterposedSynchronization(any())
    }

    private fun recorder(enabled: Boolean = true) =
        MasterdataChangeRecorder(registry, source, MasterdataEventMapper(), outbox, appended, enabled)

    private fun recipient(id: Long) = Recipient().apply { this.id = id }

    private fun commit() = syncs.forEach { it.beforeCompletion() }

    @Test
    fun `disabled records nothing`() {
        val recorder = recorder(enabled = false)

        recorder.saved(recipient(1))
        recorder.deleting(recipient(1))

        verifyNoInteractions(registry, outbox)
    }

    @Test
    fun `entities that are not master data are ignored`() {
        recorder().saved("something else")
        recorder().deleting("something else")

        verify(registry, never()).registerInterposedSynchronization(any())
    }

    @Test
    fun `a write outside a transaction fails loudly`() {
        status = Status.STATUS_NO_TRANSACTION

        assertThrows(IllegalStateException::class.java) { recorder().saved(recipient(1)) }
    }

    @Test
    fun `an entity without an id fails loudly`() {
        assertThrows(IllegalStateException::class.java) { recorder().saved(Recipient()) }
    }

    @Test
    fun `repeated writes of one aggregate yield one outbox row at commit`() {
        val recorder = recorder()
        whenever(source.upserted(MasterdataAggregate.CUSTOMER, 1)).thenReturn(CustomerDeleted(1))

        recorder.saved(recipient(1))
        recorder.saved(recipient(1))
        recorder.saved(RecipientB2bFieldValue().apply { recipientId = 1 })

        verify(outbox, never()).append(any(), any(), any(), any())
        verify(appended, times(1)).fire(any())
        commit()
        verify(outbox, times(1)).append(eq("customer"), eq("1"), eq("masterdata.customer.upserted"), any())
    }

    @Test
    fun `children count as changes of their owner`() {
        val recorder = recorder()
        val owner = recipient(1)
        val seller = SenderProfile().apply { id = 2 }
        val profile = B2bProfile().apply { id = 3 }
        whenever(source.upserted(any(), any())).thenReturn(CustomerDeleted(0))

        recorder.saved(Address.forRecipient(owner, Address.AddressType.BILLING, "s", "10115", "c"))
        recorder.saved(Address.forSender(seller, Address.AddressType.BILLING, "s", "10115", "c"))
        recorder.saved(B2bProfileField().apply { this.profile = profile })
        recorder.deleting(Address.forRecipient(owner, Address.AddressType.BILLING, "s", "10115", "c"))
        recorder.deleting(B2bProfileField().apply { this.profile = profile })
        recorder.deleting(RecipientB2bFieldValue().apply { recipientId = 1 })
        commit()

        verify(outbox).append(eq("customer"), eq("1"), eq("masterdata.customer.upserted"), any())
        verify(outbox).append(eq("seller"), eq("2"), eq("masterdata.seller.upserted"), any())
        verify(outbox).append(eq("b2bprofile"), eq("3"), eq("masterdata.b2bprofile.upserted"), any())
    }

    private fun range(id: Long, subject: NumberRangeSubject) = NumberRange().apply {
        this.id = id
        this.subject = subject
    }

    @Test
    fun `a quote number range is recorded and its removal is a deleted event`() {
        val recorder = recorder()
        whenever(source.upserted(MasterdataAggregate.NUMBER_RANGE, 5)).thenReturn(CustomerDeleted(5))

        recorder.saved(range(5, NumberRangeSubject.QUOTE))
        commit()
        verify(outbox).append(eq("numberrange"), eq("5"), eq("masterdata.numberrange.upserted"), any())

        syncs.clear()
        resources.clear()
        recorder.deleting(range(5, NumberRangeSubject.QUOTE))
        commit()
        verify(outbox).append(eq("numberrange"), eq("5"), eq("masterdata.numberrange.deleted"), any())
    }

    @Test
    fun `number ranges of other subjects are never recorded`() {
        val recorder = recorder()

        NumberRangeSubject.entries.filter { it != NumberRangeSubject.QUOTE }.forEach {
            recorder.saved(range(6, it))
            recorder.deleting(range(6, it))
        }

        verify(registry, never()).registerInterposedSynchronization(any())
        verifyNoInteractions(outbox)
    }

    @Test
    fun `an orphan address and an orphan profile field record nothing`() {
        recorder().saved(mock<Address>())
        recorder().saved(B2bProfileField())

        verify(registry, never()).registerInterposedSynchronization(any())
    }

    @Test
    fun `a customer reference counts as a change of its article and is never a removal`() {
        val recorder = recorder()
        whenever(source.upserted(any(), any())).thenReturn(CustomerDeleted(0))
        val ref = ArticleRecipientRef().apply { article = Article().apply { id = 4 } }

        recorder.deleting(ref)
        recorder.saved(ArticleRecipientRef())
        commit()

        verify(outbox).append(eq("article"), eq("4"), eq(MasterdataAggregate.ARTICLE.upsertedSubject), any())
    }

    @Test
    fun `deleting a customer republishes the articles that reference it`() {
        val recorder = recorder()
        whenever(source.touchArticlesReferencing(1)).thenReturn(listOf(7L, 8L))
        whenever(source.upserted(eq(MasterdataAggregate.ARTICLE), any())).thenReturn(CustomerDeleted(0))

        recorder.deleting(recipient(1))
        commit()

        verify(outbox).append(eq("customer"), eq("1"), eq(MasterdataAggregate.CUSTOMER.deletedSubject), any())
        listOf("7", "8").forEach {
            verify(outbox).append(eq("article"), eq(it), eq(MasterdataAggregate.ARTICLE.upsertedSubject), any())
        }
    }

    @Test
    fun `every root aggregate type is recognised`() {
        val recorder = recorder()
        whenever(source.upserted(any(), any())).thenReturn(CustomerDeleted(0))

        recorder.saved(SenderProfile().apply { id = 1 })
        recorder.saved(Article().apply { id = 1 })
        recorder.saved(TaxRate().apply { id = 1 })
        recorder.saved(PaymentTerm().apply { id = 1 })
        recorder.saved(B2bProfile().apply { id = 1 })
        recorder.saved(range(1, NumberRangeSubject.QUOTE))
        commit()

        MasterdataAggregate.entries.filter { it != MasterdataAggregate.CUSTOMER }.forEach {
            verify(outbox).append(eq(it.type), eq("1"), eq(it.upsertedSubject), any())
        }
    }

    @Test
    fun `a removal after a write ends as a deleted event`() {
        val recorder = recorder()

        recorder.saved(recipient(1))
        recorder.deleting(recipient(1))
        commit()

        val payload = argumentCaptor<String>()
        verify(outbox).append(eq("customer"), eq("1"), eq("masterdata.customer.deleted"), payload.capture())
        assertEquals("""{"id":1}""", payload.firstValue)
        verify(source, never()).upserted(any(), any())
    }

    @Test
    fun `an aggregate that vanished before commit is published as deleted`() {
        val recorder = recorder()
        whenever(source.upserted(MasterdataAggregate.CUSTOMER, 1)).thenReturn(null)

        recorder.saved(recipient(1))
        commit()

        verify(outbox).append(eq("customer"), eq("1"), eq("masterdata.customer.deleted"), any())
    }

    @Test
    fun `nothing is appended when the transaction is no longer active at completion`() {
        val recorder = recorder()
        recorder.saved(recipient(1))
        status = Status.STATUS_MARKED_ROLLBACK

        commit()
        syncs.forEach { it.afterCompletion(Status.STATUS_ROLLEDBACK) }

        verify(outbox, never()).append(any(), any(), any(), any())
    }
}
