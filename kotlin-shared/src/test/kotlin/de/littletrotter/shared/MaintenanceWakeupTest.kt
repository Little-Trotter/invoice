package de.littletrotter.shared

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

/** Every `require` guard in [MaintenanceWakeup]'s constructor and [MaintenanceWakeup.subject]. */
class MaintenanceWakeupTest {

    @Test
    fun `a current-version wake-up with a positive requestId constructs`() {
        val wakeup = MaintenanceWakeup(MaintenanceWakeup.CURRENT_VERSION, 42L)

        assertThat(wakeup.version).isEqualTo(1)
        assertThat(wakeup.requestId).isEqualTo(42L)
    }

    @Test
    fun `an unsupported version is rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy { MaintenanceWakeup(2, 42L) }
            .withMessageContaining("Unsupported maintenance wake-up version")
    }

    @Test
    fun `a non-positive requestId is rejected`() {
        assertThatIllegalArgumentException()
            .isThrownBy { MaintenanceWakeup(MaintenanceWakeup.CURRENT_VERSION, 0L) }
            .withMessageContaining("requestId must be positive")
        assertThatIllegalArgumentException()
            .isThrownBy { MaintenanceWakeup(MaintenanceWakeup.CURRENT_VERSION, -1L) }
    }

    @Test
    fun `two wake-ups with the same version and requestId are equal`() {
        assertThat(MaintenanceWakeup(1, 42L)).isEqualTo(MaintenanceWakeup(1, 42L))
    }

    @Test
    fun `two wake-ups with a different requestId are not equal`() {
        assertThat(MaintenanceWakeup(1, 42L)).isNotEqualTo(MaintenanceWakeup(1, 43L))
    }

    @Test
    fun `subject builds the fixed maintenance subject for a valid instance name`() {
        assertThat(MaintenanceWakeup.subject("a8-max-muli")).isEqualTo("maintenance.v1.a8-max-muli.requested")
    }

    @Test
    fun `subject rejects an instance name outside the allowed character set`() {
        assertThatIllegalArgumentException()
            .isThrownBy { MaintenanceWakeup.subject("a8/max muli") }
            .withMessageContaining("must match")
    }
}
