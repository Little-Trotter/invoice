package de.littletrotter.application

import de.littletrotter.application.reference.port.inbound.PaymentTermQueryPort
import de.littletrotter.domain.masterdata.PaymentTerm
import de.littletrotter.domain.reference.Language
import de.littletrotter.fixture.aPaymentTerm
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * ST-B5 — `SenderProfileView.defaultPaymentTermField`/`InvoiceDetailViewOverview.paymentTermBox`
 * both build their ComboBox item labels via `PaymentTerm.nameFor(...)`, which reads the LAZY
 * `nameGroup.entries` collection. Both feed from `PaymentTermQueryPort.findAllPaymentTerms()`, and
 * unlike `SenderProfileEditLoadIT`'s `AI-T01` (a *single* payment term reached through
 * `SenderProfile.defaultPaymentTerm`, already force-initialized in
 * `PanacheSenderProfileRepository.findByIdForEdit`), the full list this facade method returns had
 * no such guard — opening either picker under load threw `LazyInitializationException` the moment
 * Vaadin called the item label generator, well outside the loading request's own transaction.
 *
 * The test methods are deliberately **not** `@Transactional` (mirrors [SenderProfileEditLoadIT]'s
 * own note): the bug only appears once the facade call has returned and its transaction has
 * committed — the exact state a ComboBox's item label generator runs in when Vaadin renders it.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PaymentTermSelectorLoadIT {

    @Inject
    lateinit var paymentTermQueries: PaymentTermQueryPort

    @Inject
    lateinit var em: EntityManager

    companion object {
        private val counter = AtomicInteger(0)
    }

    @Transactional
    fun findLanguage(code: String): Language =
        em.createQuery("SELECT l FROM Language l WHERE l.code = :code", Language::class.java)
            .setParameter("code", code).singleResult

    @Transactional
    fun persistPaymentTerm(name: String): PaymentTerm {
        val term = aPaymentTerm {
            changeName(findLanguage("de"), name)
            dueInDays = 7
            isActive = true
        }
        em.persist(term)
        em.flush()
        return term
    }

    @Test
    fun `findAllPaymentTerms resolves nameFor for every entry outside the loading transaction`() {
        val name = "B5 Zahlungsziel ${counter.incrementAndGet()}"
        val term = persistPaymentTerm(name)

        // Exactly what SenderProfileView's/InvoiceDetailViewOverview's ComboBox item label
        // generator does: read every returned term's name after the facade call has returned.
        val terms = paymentTermQueries.findAllPaymentTerms()
        val loaded = terms.first { it.id == term.id }

        assertEquals(name, loaded.nameFor("de"))
        assertFalse(loaded.nameFor("de").isNullOrBlank())
    }
}
