package de.littletrotter.application.invoice

import de.littletrotter.application.reference.validationSettingsGate
import de.littletrotter.domain.invoice.DocumentEngine
import de.littletrotter.domain.reference.ValidationCapabilityId
import de.littletrotter.domain.repository.GenerationJobRepository
import de.littletrotter.shared.time.ClockTimeSource
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * facturx-arc-improve.md §7 — the idle-stop scheduler. `existsRecentByEngine` is stubbed
 * directly (mocked [GenerationJobRepository]) rather than exercised against a real database —
 * that JPQL query itself is covered by [de.littletrotter.infrastructure.persistence
 * .PanacheGenerationJobRepository] and this module's existing `@QuarkusTest` coverage of that
 * class' sibling queries; what this test owns is the scheduler's own decision logic.
 */
class FacturXEngineIdleStopSchedulerTest {

    private fun scheduler(
        switchEnabled: Boolean,
        repository: GenerationJobRepository,
        engineActivation: de.littletrotter.application.invoice.EngineActivation,
        idleStopMinutes: Long = 30,
        deployed: Boolean = true,
    ) = FacturXEngineIdleStopScheduler(
        validationSettingsGate = if (switchEnabled) validationSettingsGate() else validationSettingsGate(ValidationCapabilityId.FACTURX_ENGINE),
        engineActivation = engineActivation,
        generationJobRepository = repository,
        idleStopMinutes = idleStopMinutes,
        facturXDeployment = FacturXDeployment(deployed),
        time = ClockTimeSource.fixedAt(java.time.Instant.parse("2026-06-15T10:00:00Z")),
    )

    @Test
    fun `releases the engine when no FACTURX job was seen recently`() {
        val repository = mock<GenerationJobRepository>()
        whenever(repository.existsRecentByEngine(eq(DocumentEngine.FACTURX), any())).thenReturn(false)
        val engineActivation = mock<EngineActivation>()

        scheduler(switchEnabled = true, repository = repository, engineActivation = engineActivation).releaseIfIdle()

        verify(engineActivation).releaseIdle(DocumentEngine.FACTURX)
    }

    @Test
    fun `does nothing when a FACTURX job was seen recently`() {
        val repository = mock<GenerationJobRepository>()
        whenever(repository.existsRecentByEngine(eq(DocumentEngine.FACTURX), any())).thenReturn(true)
        val engineActivation = mock<EngineActivation>()

        scheduler(switchEnabled = true, repository = repository, engineActivation = engineActivation).releaseIfIdle()

        verify(engineActivation, never()).releaseIdle(any())
    }

    @Test
    fun `does nothing at all when the FACTURX_ENGINE switch is off`() {
        val repository = mock<GenerationJobRepository>()
        val engineActivation = mock<EngineActivation>()

        scheduler(switchEnabled = false, repository = repository, engineActivation = engineActivation).releaseIfIdle()

        verify(engineActivation, never()).releaseIdle(any())
        verify(repository, never()).existsRecentByEngine(any(), any())
    }

    // A non-deployed engine has nothing to idle-stop; must not even reach the switch or
    // repository check.
    @Test
    fun `does nothing at all when the engine is not deployed, even with the switch on`() {
        val repository = mock<GenerationJobRepository>()
        val engineActivation = mock<EngineActivation>()

        scheduler(switchEnabled = true, repository = repository, engineActivation = engineActivation, deployed = false).releaseIfIdle()

        verify(engineActivation, never()).releaseIdle(any())
        verify(repository, never()).existsRecentByEngine(any(), any())
    }

    @Test
    fun `a repository failure is swallowed, not propagated`() {
        val repository = mock<GenerationJobRepository>()
        whenever(repository.existsRecentByEngine(eq(DocumentEngine.FACTURX), any())).thenThrow(RuntimeException("db down"))
        val engineActivation = mock<EngineActivation>()

        assertDoesNotThrow {
            scheduler(switchEnabled = true, repository = repository, engineActivation = engineActivation).releaseIfIdle()
        }
        verify(engineActivation, never()).releaseIdle(any())
    }
}
