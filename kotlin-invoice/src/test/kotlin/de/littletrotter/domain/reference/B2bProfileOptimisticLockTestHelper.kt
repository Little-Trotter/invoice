package de.littletrotter.domain.reference

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * EP-4 border case (concurrency: "two sessions editing the same profile") — CDI helper bean for
 * `Ep4BorderCasesIT`, the same shape as `de.littletrotter.domain.invoice
 * .InvoiceOptimisticLockTestHelper` (kotlin-invoice) already established for [de.littletrotter
 * .domain.invoice.Invoice]: a top-level class (a non-static inner class cannot be a CDI bean —
 * no zero-arg constructor the container can see), so both concurrent "sessions" get their own
 * genuinely independent JTA transaction via [Transactional.TxType.REQUIRES_NEW].
 */
@ApplicationScoped
class B2bProfileOptimisticLockTestHelper {

    @Inject
    lateinit var em: EntityManager

    /** Persists a minimal [B2bProfile] and returns its database id. */
    @Transactional
    fun createProfile(name: String): Long {
        val profile = B2bProfile().apply { this.name = name }
        em.persist(profile)
        em.flush()
        return profile.id!!
    }

    /**
     * Loads [profileId] by id, signals [loadedLatch], waits on [startSignal], then edits
     * [B2bProfile.description] and merges — simulating one side of two concurrent profile-editing
     * sessions (a real B2B-Profile "Bearbeiten" dialog open twice for the same row).
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    fun loadAndRename(
        profileId: Long,
        newDescription: String,
        loadedLatch: CountDownLatch,
        startSignal: CountDownLatch,
    ) {
        val profile = em.find(B2bProfile::class.java, profileId)
        loadedLatch.countDown()
        startSignal.await(10, TimeUnit.SECONDS)
        profile.description = newDescription
        em.merge(profile)
        // JTA transaction commits here; Hibernate's version-WHERE check runs on flush.
    }
}
