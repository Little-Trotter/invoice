package de.littletrotter.infrastructure.persistence.stats

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable

/**
 * Read-only mapping of `v_stat_available_years` (`SELECT DISTINCT
 * EXTRACT(YEAR FROM invoice_date)::INT AS year FROM invoice WHERE invoice_date IS NOT NULL`).
 * `year` is genuinely unique per row here (the view is already `DISTINCT`), so a plain `@Id`
 * suffices — no composite key needed, unlike the grouped `v_stat_*` views.
 */
@Entity
@Immutable
@Table(name = "v_stat_available_years")
class StatAvailableYear {

    @Id
    @Column(name = "year")
    var year: Int = 0
}
