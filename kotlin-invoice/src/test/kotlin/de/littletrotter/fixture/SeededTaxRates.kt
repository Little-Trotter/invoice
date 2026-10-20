package de.littletrotter.fixture

import de.littletrotter.domain.reference.TaxRate
import jakarta.persistence.EntityManager

/**
 * The reference 19 % standard rule the baseline seed installs. Integration tests persist invoice
 * lines against it because `invoice_item.tax_rate_id` is a mandatory foreign key to a stored rule;
 * a transient rule from [aStandardTaxRate] cannot be referenced by a persisted line.
 */
fun EntityManager.seededStandardTaxRate(): TaxRate =
    createQuery("select t from TaxRate t where t.code = :code", TaxRate::class.java)
        .setParameter("code", "DE_STANDARD_19")
        .singleResult
