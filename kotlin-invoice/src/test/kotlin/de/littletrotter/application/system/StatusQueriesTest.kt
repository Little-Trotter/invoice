package de.littletrotter.application.system

import de.littletrotter.application.dto.JobPipelineStatus
import de.littletrotter.application.dto.NatsStatus
import de.littletrotter.application.dto.ServiceStatus
import de.littletrotter.application.dto.ValkeyStatsStatus
import de.littletrotter.application.system.port.outbound.JobPipelineStatusService
import de.littletrotter.application.system.port.outbound.NatsStatusService
import de.littletrotter.application.system.port.outbound.SystemStatusService
import de.littletrotter.application.system.port.outbound.ValkeyStatsService
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class StatusQueriesTest {

    @Test
    fun `nats queries return the adapter status unchanged`() {
        val status = mock<NatsStatus>()
        val service = mock<NatsStatusService>()
        whenever(service.currentStatus()).thenReturn(status)

        assertSame(status, NatsStatusQueries(service).currentStatus())
    }

    @Test
    fun `system queries return the adapter dependency list unchanged`() {
        val dependencies = listOf(mock<ServiceStatus>())
        val service = mock<SystemStatusService>()
        whenever(service.listDependencies()).thenReturn(dependencies)

        assertSame(dependencies, SystemStatusQueries(service).listDependencies())
    }

    @Test
    fun `job pipeline queries return the adapter status unchanged`() {
        val status = mock<JobPipelineStatus>()
        val service = mock<JobPipelineStatusService>()
        whenever(service.currentStatus()).thenReturn(status)

        assertSame(status, JobPipelineStatusQueries(service).currentStatus())
    }

    @Test
    fun `valkey queries return the adapter statistics unchanged`() {
        val stats = mock<ValkeyStatsStatus>()
        val service = mock<ValkeyStatsService>()
        whenever(service.getStats()).thenReturn(stats)

        assertSame(stats, ValkeyStatsQueries(service).getStats())
    }
}
