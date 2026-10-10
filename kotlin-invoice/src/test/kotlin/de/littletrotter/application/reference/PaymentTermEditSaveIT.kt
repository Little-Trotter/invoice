package de.littletrotter.application.reference

import de.littletrotter.application.PostgresTestResource
import de.littletrotter.application.dto.PaymentTermDto
import io.quarkus.narayana.jta.QuarkusTransaction
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * L27 — every edit-save of an existing payment term used to fail with
 * `OptimisticLockException: Unexpected row count (expected 1 but was 0)` (create worked).
 * Drives the same path as the reference-data tab: create, reload by id, edit, save.
 */
@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
class PaymentTermEditSaveIT {

    @Inject
    lateinit var manage: ManagePaymentTerm

    @Inject
    lateinit var service: PaymentTermService

    @Inject
    lateinit var em: EntityManager

    /** Fresh persistence context per read: the test's request scope would otherwise serve a cached copy. */
    private fun stored(id: Long): Triple<Int, String?, Long?> =
        QuarkusTransaction.requiringNew().call {
            val t = service.findPaymentTermById(id)!!
            Triple(t.dueInDays, t.rawNameFor("de"), t.version)
        }

    @Test
    @TestSecurity(user = "admin", roles = ["admin"])
    fun editSaveOfExistingTermPersistsChangesAndBumpsVersion() {
        val created = manage.savePaymentTerm(
            PaymentTermDto(namesByLanguageCode = mapOf("de" to "L27 Netto 14"), dueInDays = 14),
        )
        val id = created.id!!
        val versionBefore = stored(id).third!!

        val edited = manage.savePaymentTerm(
            PaymentTermDto(
                id = id,
                version = versionBefore,
                namesByLanguageCode = mapOf("de" to "L27 Netto 30"),
                dueInDays = 30,
            ),
        )

        assertEquals(30, edited.dueInDays)
        val (days, name, version) = stored(id)
        assertEquals(30, days)
        assertEquals("L27 Netto 30", name)
        assertEquals(versionBefore + 1, version)

        // A second consecutive edit from the returned revision must work too.
        manage.savePaymentTerm(
            PaymentTermDto(id = id, version = edited.version, namesByLanguageCode = mapOf("de" to "L27 Netto 30"), dueInDays = 45),
        )
        assertEquals(45, stored(id).first)
    }

    private fun defaultCount(): Long = QuarkusTransaction.requiringNew().call {
        (em.createNativeQuery("SELECT COUNT(*) FROM payment_term WHERE is_default = TRUE").singleResult as Number).toLong()
    }

    @Test
    @TestSecurity(user = "admin", roles = ["admin"])
    fun concurrentDefaultPromotionsThroughTheUiPathLeaveExactlyOneDefault() {
        val seededId = service.findDefaultPaymentTerm()!!.id!!
        val threads = 8
        val rounds = 4
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(rounds) { round ->
                // Existing, non-default rows are loaded and flipped to default in the same
                // transaction as the lock: the shape that auto-flushed before the lock was held.
                val ids = (1..threads).map {
                    manage.savePaymentTerm(
                        PaymentTermDto(namesByLanguageCode = mapOf("de" to "PTD r$round-$it"), dueInDays = 10 + it),
                    ).id!!
                }
                val start = CountDownLatch(1)
                val futures = ids.mapIndexed { i, id ->
                    pool.submit {
                        start.await()
                        val version = stored(id).third
                        if (i % 2 == 0) {
                            manage.savePaymentTerm(
                                PaymentTermDto(id = id, version = version, namesByLanguageCode = mapOf("de" to "PTD edit $round-$i"), dueInDays = 10, isDefault = true),
                            )
                        } else {
                            manage.savePaymentTerm(
                                PaymentTermDto(namesByLanguageCode = mapOf("de" to "PTD new $round-$i"), dueInDays = 11, isDefault = true),
                            )
                        }
                    }
                }
                start.countDown()
                futures.forEach { it.get(60, TimeUnit.SECONDS) }
                assertEquals(1L, defaultCount())
            }
        } finally {
            pool.shutdownNow()
            QuarkusTransaction.requiringNew().run {
                em.createNativeQuery("UPDATE payment_term SET is_default = FALSE WHERE is_default = TRUE").executeUpdate()
                em.createNativeQuery("UPDATE payment_term SET is_default = TRUE WHERE id = :id").setParameter("id", seededId).executeUpdate()
            }
        }
    }
}
