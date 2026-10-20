package de.littletrotter.domain.settings

import de.littletrotter.domain.shared.AuditableEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import jakarta.validation.constraints.Size

@Entity
@Table(name = "feature_setting")
class FeatureSetting : AuditableEntity() {

    @Id
    @field:Size(max = 64)
    @Column(name = "key", nullable = false, length = 64)
    var key: String = ""
        protected set

    @Column(nullable = false)
    var enabled: Boolean = true
        protected set

    @field:Size(max = 255)
    @Column(length = 255)
    var description: String? = null
        protected set

    @Version
    var version: Long? = 0
        protected set

    val flag: FeatureFlag?
        get() = FeatureFlag.fromKey(key)

    /** Switches the flag on or off. */
    fun switchTo(isEnabled: Boolean) {
        enabled = isEnabled
    }

    companion object {
        fun of(flag: FeatureFlag, enabled: Boolean): FeatureSetting =
            FeatureSetting().apply {
                key = flag.key
                this.enabled = enabled
            }
    }
}
