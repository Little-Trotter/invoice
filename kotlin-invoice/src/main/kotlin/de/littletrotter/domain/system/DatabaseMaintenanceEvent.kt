package de.littletrotter.domain.system

import de.littletrotter.domain.shared.CreationStampListener
import de.littletrotter.domain.shared.StampedOnCreation
import de.littletrotter.domain.system.converters.DatabaseMaintenanceStatusConverter
import de.littletrotter.domain.system.converters.DatabaseMaintenanceTypeConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "database_maintenance_event")
@EntityListeners(CreationStampListener::class)
class DatabaseMaintenanceEvent(
    type: DatabaseMaintenanceType = DatabaseMaintenanceType.VACUUM,
    status: DatabaseMaintenanceStatus = DatabaseMaintenanceStatus.SUCCESS,
    details: String? = null,
    createdAt: OffsetDateTime = StampedOnCreation.UNSET,
) : StampedOnCreation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = createdAt
        protected set

    @Convert(converter = DatabaseMaintenanceTypeConverter::class)
    @Column(nullable = false)
    var type: DatabaseMaintenanceType = type
        protected set

    @Convert(converter = DatabaseMaintenanceStatusConverter::class)
    @Column(nullable = false)
    var status: DatabaseMaintenanceStatus = status
        protected set

    @Column(columnDefinition = "TEXT")
    var details: String? = details
        protected set

    override fun stampCreationIfUnset(now: OffsetDateTime) {
        if (createdAt == StampedOnCreation.UNSET) createdAt = now
    }
}

enum class DatabaseMaintenanceType {
    VACUUM,
    AUTO_VACUUM,
    ANALYZE,
    AUTO_ANALYZE,
    REINDEX,
    BACKUP,
    RECALCULATE_TOTALS,

    /** The nightly job that recomputes `number_journal` and `invoice_audit_journal`
     *  from scratch and compares against the stored `entry_hash` values (see
     *  [de.littletrotter.application.compliance.ChainVerificationService]). */
    CHAIN_VERIFICATION,

    /** Queues real generation jobs for every issued invoice (ISSUED/CANCELLED) that has none,
     *  so a dataset an out-of-band process (e.g. datagen) or an operational gap left without
     *  artifacts gets its real PDF/XML/KoSIT report/archive package through the normal pipeline.
     *  Handled by
     *  [de.littletrotter.application.system.port.outbound.DatabaseMaintenanceService.performQueueIssuedGenerationJobs],
     *  claimed by the same sequential worker as [RECALCULATE_TOTALS]
     *  ([de.littletrotter.application.invoice.MaintenanceQueueProcessor]). Appended last —
     *  ordinal converter, never reorder. */
    QUEUE_ISSUED_GENERATION_JOBS,

    /** The nightly Message Center retention sweep
     *  ([de.littletrotter.infrastructure.persistence.SystemMessageRetentionScheduler]). Recorded
     *  purely as due-state: [de.littletrotter.infrastructure.persistence
     *  .SystemMessageRetentionScheduler] reads the latest event of this type to decide whether a
     *  `@Scheduled(every = "24h")` tick is actually due (a restart must not silently either skip a
     *  day or re-run within minutes of the last real cleanup) rather than to report a business
     *  outcome the way [CHAIN_VERIFICATION] does. Appended last — ordinal converter, never reorder. */
    SYSTEM_MESSAGE_RETENTION,
}

enum class DatabaseMaintenanceStatus {
    SUCCESS,
    FAILED,
    UNKNOWN,
    /** Task S: queued for de.littletrotter.application.invoice.RecalculationJobProcessor, not yet picked up. */
    PENDING,
    /** Task S: currently being processed by the worker. */
    RUNNING,
}
