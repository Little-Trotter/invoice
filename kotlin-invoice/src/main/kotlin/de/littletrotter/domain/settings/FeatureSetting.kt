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

    @Column(nullable = false)
    var enabled: Boolean = true

    @field:Size(max = 255)
    @Column(length = 255)
    var description: String? = null

    @Version
    var version: Long? = 0

    val flag: FeatureFlag?
        get() = FeatureFlag.fromKey(key)

    companion object {
        fun of(flag: FeatureFlag, enabled: Boolean): FeatureSetting =
            FeatureSetting().apply {
                key = flag.key
                this.enabled = enabled
            }
    }
}
