package de.littletrotter.domain.compliance

import de.littletrotter.domain.shared.CreationStampListener
import de.littletrotter.domain.shared.StampedOnCreation
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityListeners
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.constraints.Size
import java.time.LocalDate
import java.time.OffsetDateTime

/** F4/VALIDATION-01 — lifecycle of a registered validation profile/guideline version. Only
 *  [ACTIVE] and [DEPRECATED] allow finalizing an invoice under this profile ([DEPRECATED] with
 *  a warning); [BLOCKED] and an unregistered profile id both block, non-overridably. */
enum class ValidationProfileState { EXPERIMENTAL, ACTIVE, DEPRECATED, BLOCKED }

/**
 * F4 — one row per concrete, approved profile/guideline version (VALIDATION-01). An invoice
 * references a specific version, not just "XRechnung": [profileId] is the value
 * `XmlExportServiceImpl.resolveProfile()` already returns today (`"XRechnung"`, `"EN16931"`) —
 * this entity only records what's known about that identifier, it never derives it.
 */
@Entity
@Table(name = "validation_profile")
@EntityListeners(CreationStampListener::class)
class ValidationProfile : StampedOnCreation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @field:Size(max = 100)
    @Column(name = "profile_id", nullable = false, length = 100)
    var profileId: String = ""

    @field:Size(max = 50)
    @Column(name = "spec_version", nullable = false, length = 50)
    var specVersion: String = ""

    @field:Size(max = 50)
    @Column(length = 50)
    var syntax: String? = null

    // codelist_version/validator_config_version/mustang_version still exist in the DB (operator
    // documentation of the validator versions expected per profile) but are never read by the
    // application — deliberately not mapped; the entity only carries what the app uses.

    @field:Size(max = 20)
    @Column(nullable = false, length = 20)
    var state: String = ValidationProfileState.EXPERIMENTAL.name

    /** Deliberately not the R4/OrdinalEnumConverter convention — like `TaxRate.category`,
     *  [state] is an externally readable value, not a pure ordinal. */
    val stateEnum: ValidationProfileState?
        get() = ValidationProfileState.entries.firstOrNull { it.name == state }

    @Column(name = "valid_from")
    var validFrom: LocalDate? = null

    @Column(name = "valid_to")
    var validTo: LocalDate? = null

    var createdAt: OffsetDateTime? = null

    override fun stampCreationIfUnset(now: OffsetDateTime) {
        if (createdAt == null) createdAt = now
    }

    @Version
    var version: Long? = 0
}
