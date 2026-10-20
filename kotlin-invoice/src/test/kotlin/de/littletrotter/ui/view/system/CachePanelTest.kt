package de.littletrotter.ui.view.system

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasText
import de.littletrotter.application.system.port.inbound.CacheStatusQueryPort
import de.littletrotter.application.system.port.inbound.ValkeyStatsQueryPort
import de.littletrotter.application.dto.ValkeyStatsStatus
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import de.littletrotter.application.system.CacheStatusService
import de.littletrotter.application.dto.CacheStatus

class CachePanelTest {

    private fun stubValkeyStatsService(
        reachable: Boolean,
        families: List<ValkeyStatsStatus.KeyFamilyCount> = emptyList(),
        totalKeys: Long = 0,
        usedMemoryHuman: String? = null,
    ): ValkeyStatsQueryPort =
        object : ValkeyStatsQueryPort {
            override fun getStats(): ValkeyStatsStatus =
                ValkeyStatsStatus(reachable, families, totalKeys, usedMemoryHuman)
        }

    @Test
    fun `cache panel renders offline valkey status`() {
        val panel = CachePanel(CacheStatusService("127.0.0.1", 1, ""), stubValkeyStatsService(reachable = true))

        val texts = flatten(panel).filterIsInstance<HasText>().map { it.text }.filter { it.isNotBlank() }

        assertTrue(texts.any { it.contains("OFFLINE") })
    }

    @Test
    fun `cache panel renders online valkey status`() {
        val cacheStatusService = mock<CacheStatusQueryPort>()
        whenever(cacheStatusService.fetchStatus()).thenReturn(CacheStatus(valkeyOnline = true, pdfCacheKeys = 3, queryCacheKeys = 7))
        val panel = CachePanel(cacheStatusService, stubValkeyStatsService(reachable = true))

        val texts = flatten(panel).filterIsInstance<HasText>().map { it.text }.filter { it.isNotBlank() }

        assertTrue(texts.any { it.contains("ONLINE") })
    }

    @Test
    fun `cache panel shows valkey stats total, family badges and memory when reachable`() {
        val families = listOf(
            ValkeyStatsStatus.KeyFamilyCount("pdf", 100),
            ValkeyStatsStatus.KeyFamilyCount("job:status", 50),
        )
        val panel = CachePanel(
            CacheStatusService("127.0.0.1", 1, ""),
            stubValkeyStatsService(reachable = true, families = families, totalKeys = 150, usedMemoryHuman = "256M"),
        )

        val texts = flatten(panel).filterIsInstance<HasText>().map { it.text }.filter { it.isNotBlank() }

        assertTrue(texts.any { it.contains("150") }, "Should show total Valkey keys")
        assertTrue(texts.any { it.contains("PDF-Cache") }, "Should show PDF-Cache role label for pdf family")
        assertTrue(texts.any { it.contains("256M") }, "Should show memory usage")
        assertTrue(
            texts.any { it.contains("Austausch-Daten verfallen automatisch") },
            "Should show TTL hint for Valkey",
        )
    }

    @Test
    fun `cache panel collapses family badges beyond the max into a plus-N suffix`() {
        val families = listOf(
            ValkeyStatsStatus.KeyFamilyCount("pdf", 100),
            ValkeyStatsStatus.KeyFamilyCount("job:status", 50),
            ValkeyStatsStatus.KeyFamilyCount("receipt:checksum", 30),
            ValkeyStatsStatus.KeyFamilyCount("lt:tpl", 10),
        )
        val panel = CachePanel(
            CacheStatusService("127.0.0.1", 1, ""),
            stubValkeyStatsService(reachable = true, families = families, totalKeys = 190),
        )

        val texts = flatten(panel).filterIsInstance<HasText>().map { it.text }.filter { it.isNotBlank() }

        assertTrue(texts.any { it.contains("+1") }, "Should collapse the family beyond MAX_FAMILY_BADGES into a +N suffix")
    }

    @Test
    fun `cache panel falls back to raw family name when no i18n role label exists`() {
        val families = listOf(ValkeyStatsStatus.KeyFamilyCount("unknown-family", 10))
        val panel = CachePanel(
            CacheStatusService("127.0.0.1", 1, ""),
            stubValkeyStatsService(reachable = true, families = families, totalKeys = 10),
        )

        val texts = flatten(panel).filterIsInstance<HasText>().map { it.text }.filter { it.isNotBlank() }

        assertTrue(texts.any { it.contains("unknown-family") }, "Should show unknown family raw name")
    }

    @Test
    fun `cache panel shows unreachable message when valkey stats unavailable`() {
        val panel = CachePanel(CacheStatusService("127.0.0.1", 1, ""), stubValkeyStatsService(reachable = false))

        val texts = flatten(panel).filterIsInstance<HasText>().map { it.text }.filter { it.isNotBlank() }

        assertTrue(texts.any { it.contains("nicht erreichbar") }, "Should show unreachable message for Valkey")
    }

    private fun flatten(component: Component): List<Component> =
        listOf(component) + component.children.toList().flatMap { flatten(it) }
}
