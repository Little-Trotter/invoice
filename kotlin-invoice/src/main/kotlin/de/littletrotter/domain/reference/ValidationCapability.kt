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

    /**
     * Embedded KoSIT validation of the generated XML. Switching it off skips the stage as "not
     * enabled" for every profile except XRechnung, which is always validated: its recipient
     * portals reject on rules only KoSIT evaluates.
     */
    KOSIT_CHECK(
        labelKey = "validation.capability.kosit.label",
        descriptionKey = "validation.capability.kosit.description",
    ),

    /** VeraPDF conformity check, run asynchronously on the stored document. */
    VERAPDF_CHECK(
        labelKey = "validation.capability.verapdf.label",
        descriptionKey = "validation.capability.verapdf.description",
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
        protected set

    @Column(nullable = false)
    var enabled: Boolean = true
        protected set

    @Version
    var version: Long? = 0
        protected set

    /** `null` if row names a capability unknown to this version (e.g., after downgrade). */
    val capability: ValidationCapabilityId?
        get() = ValidationCapabilityId.entries.firstOrNull { it.name == capabilityId }

    /** Switches the check on or off. */
    fun switchTo(isEnabled: Boolean) {
        enabled = isEnabled
    }

    companion object {
        fun of(id: ValidationCapabilityId, enabled: Boolean = true): ValidationSetting =
            ValidationSetting().apply {
                capabilityId = id.name
                this.enabled = enabled
            }
    }
}
