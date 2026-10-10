package de.littletrotter.domain.reference

import de.littletrotter.domain.shared.AuditableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.constraints.Size

/**
 * Switchable validation check. Additively extensible: new value + seed row, no code change needed.
 * String PK (not Ordinal): human-readable in seed scripts; reordering constants stays safe.
 * [labelKey]/[descriptionKey] resolve in UI; deliberately outside `referenceData.*` namespace.
 */
enum class ValidationCapabilityId(val labelKey: String, val descriptionKey: String) {
    /** Local oMLX validation of exported document. */
    OMLX_LLM_CHECK(
        labelKey = "validation.capability.omlx.label",
        descriptionKey = "validation.capability.omlx.description",
    ),

    /** VeraPDF conformity check as runtime step per export job. */
    VERAPDF_CHECK(
        labelKey = "validation.capability.verapdf.label",
        descriptionKey = "validation.capability.verapdf.description",
    ),

    /** Factur-X engine choice toggle in export dialog. String PK, safe to rename in seed. */
    FACTURX_ENGINE(
        labelKey = "validation.capability.facturx.label",
        descriptionKey = "validation.capability.facturx.description",
    ),
}

/**
 * Validation setting per check. Missing row = enabled (fail-open). Edition-neutral: exists in Community too.
 */
@Entity
@Table(name = "validation_setting")
class ValidationSetting : AuditableEntity() {

    @Id
    @field:Size(max = 64)
    @Column(name = "capability_id", nullable = false, length = 64)
    var capabilityId: String = ""

    @Column(nullable = false)
    var enabled: Boolean = true

    @Version
    var version: Long? = 0

    /** `null` if row names a capability unknown to this version (e.g., after downgrade). */
    val capability: ValidationCapabilityId?
        get() = ValidationCapabilityId.entries.firstOrNull { it.name == capabilityId }

    companion object {
        fun of(id: ValidationCapabilityId, enabled: Boolean = true): ValidationSetting =
            ValidationSetting().apply {
                capabilityId = id.name
                this.enabled = enabled
            }
    }
}
