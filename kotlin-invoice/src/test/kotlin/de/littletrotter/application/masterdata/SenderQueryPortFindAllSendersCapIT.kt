package de.littletrotter.application.masterdata

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.masterdata.port.inbound.SenderQueryPort
import de.littletrotter.domain.masterdata.SenderProfile
import de.littletrotter.domain.repository.SenderProfileRepository
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Regression — [SenderQueryPort.findAllSenders] must load the true complete set of sender
 * profiles, not a single `littletrotter.page-size.default` (60) page of it. Before the fix,
 * `sellerProfileService.findAll(0, defaultPageSize, null)` silently dropped everything beyond
 * position 60 of the `isDefault DESC, name ASC` ordering — this shared, never-cleaned test
 * database already holds an unknown number of sender profiles from other fixtures, so this test
 * seeds 110 of its own (guaranteed to sort last: `ZZZZZ-L11-*` sorts after every other fixture's
 * marker prefix, e.g. `PanacheSenderProfileRepositoryIT`'s `ZZ-COV4-*`) and asserts the port
 * returns every one of them, matching the repository's own `count`, regardless of how many other
 * senders already exist. 110 (not just 61) deliberately matches the scale of the live failure
 * this finding was reproduced against — the shared e2e database held 105 senders with the
 * offending fixture sender at position 66/103-105, well past 60 AND past 100.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class SenderQueryPortFindAllSendersCapIT {

    @Inject
    lateinit var em: EntityManager

    @Inject
    lateinit var senderProfileRepository: SenderProfileRepository

    @Inject
    lateinit var senderQuery: SenderQueryPort

    private val markerCount = 110

    @BeforeEach
    @Transactional
    fun removeOwnRows() {
        em.createNativeQuery("DELETE FROM sender_profile WHERE invoice_prefix LIKE 'ZL11-%'").executeUpdate()
    }

    private fun seedMarkerSenders() {
        QuarkusTransaction.requiringNew().run {
            for (i in 1..markerCount) {
                val suffix = i.toString().padStart(3, '0')
                em.persist(
                    SenderProfile().apply {
                        // ZZZZZ- sorts after every other fixture's marker name in this shared
                        // database (e.g. PanacheSenderProfileRepositoryIT's "ZZ-COV4 ..."; '-' <
                        // 'Z' so "ZZ-COV4" < "ZZZZZ-L11"), guaranteeing these 65 rows occupy the
                        // very end of the `ORDER BY isDefault DESC, name ASC` result set.
                        name = "ZZZZZ-L11-$suffix Marker GmbH"
                        invoicePrefix = "ZL11-$suffix"
                        // NOT NULL at the DB level (legacy column, see
                        // PanacheSenderProfileRepositoryIT's newSender for the same fix-up).
                        registerCourt = ""
                    },
                )
            }
            em.flush()
        }
    }

    @Test
    fun `findAllSenders returns every sender once the true count exceeds the old 60-row page`() {
        seedMarkerSenders()

        val totalSenders = senderProfileRepository.count(null)
        // >100, not just >60 — matches the live failure's scale (105 senders, offending fixture
        // sender at alphabetical position 66 of 67 / 103-105 of 105), not merely the theoretical
        // minimum needed to exceed the old cap.
        assertTrue(totalSenders > 100, "fixture must push the true sender count past the live failure's scale, was $totalSenders")

        val all = senderQuery.findAllSenders()

        assertEquals(totalSenders, all.size.toLong(), "findAllSenders() must return the complete set, not a capped page")

        val markerNames = all.map { it.name }.filter { it.startsWith("ZZZZZ-L11-") }.toSet()
        val expectedMarkerNames = (1..markerCount).map { "ZZZZZ-L11-${it.toString().padStart(3, '0')} Marker GmbH" }.toSet()
        assertEquals(
            expectedMarkerNames,
            markerNames,
            "every seeded marker sender must be present, including the ones ranked well beyond position 60",
        )
    }
}
