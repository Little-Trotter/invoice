package de.littletrotter.infrastructure.persistence

import de.littletrotter.application.invoice.InvoiceNumberReadRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.persistence.Tuple

/**
 * Plain SQL on purpose: a mail reference may be a quote number that is no valid invoice number,
 * which the entity's number value object would reject while binding the parameter. Only number and
 * id are selected, so no invoice graph is loaded.
 */
@ApplicationScoped
class InvoiceNumberReadRepositoryImpl @Inject constructor(
    private val em: EntityManager,
) : InvoiceNumberReadRepository {

    override fun findIdsByNumbers(numbers: Set<String>): Map<String, Long> =
        em.createNativeQuery("SELECT invoice_number AS number, id AS id FROM invoice WHERE invoice_number IN (:numbers)", Tuple::class.java)
            .setParameter("numbers", numbers)
            .resultList
            .filterIsInstance<Tuple>()
            .associate { (it.get("number") as String) to (it.get("id") as Number).toLong() }
}
