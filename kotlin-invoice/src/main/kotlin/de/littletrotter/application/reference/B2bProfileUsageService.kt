package de.littletrotter.application.reference

import de.littletrotter.domain.repository.RecipientRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

/**
 * Backs the profile grid's "Verwendung" column and its expanded-row "Verwendet
 * von" panel; one grouped query each, no per-row N+1.
 */
@ApplicationScoped
class B2bProfileUsageService @Inject constructor(
    private val recipientRepository: RecipientRepository,
) {
    /** Usage counts of every B2B profile, one grouped query for the profile grid's "Verwendung"
     *  column; no N+1. */
    fun usageCounts(): Map<Long, Long> = recipientRepository.countGroupedByB2bProfile()

    /** Names of every recipient currently assigned [profileId] as their standing default, for the
     *  profile row's expanded "Verwendet von" panel (capped in the UI). Reuses the exact same
     *  query [B2bProfileLifecycleService.deleteProfile]'s guard already relies on. */
    fun usageNames(profileId: Long): List<String> = recipientRepository.findNamesByB2bProfileId(profileId)
}
