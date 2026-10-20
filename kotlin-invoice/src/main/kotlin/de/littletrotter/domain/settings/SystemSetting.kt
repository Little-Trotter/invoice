package de.littletrotter.domain.settings

import de.littletrotter.domain.shared.AuditableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.constraints.Size

/**
 * A small generic key/value settings table for the one setting
 * [FeatureSetting]'s boolean-only shape cannot express: the active
 * [de.littletrotter.shared.wording.WordingProfile]. Reusable for a future non-boolean setting
 * (there is exactly one row, [de.littletrotter.application.settings.WordingSettingsGate.KEY],
 * today) rather than bending `feature_setting` itself.
 */
@Entity
@Table(name = "system_setting")
class SystemSetting : AuditableEntity() {

    @Id
    @field:Size(max = 64)
    @Column(name = "key", nullable = false, length = 64)
    var key: String = ""
        protected set

    @field:Size(max = 255)
    @Column(nullable = false, length = 255)
    var value: String = ""
        protected set

    @Version
    var version: Long? = 0
        protected set

    /** Replaces the stored value. */
    fun changeValue(newValue: String) {
        value = newValue
    }

    companion object {
        fun of(key: String, value: String): SystemSetting = SystemSetting().apply {
            this.key = key
            this.value = value
        }
    }
}
