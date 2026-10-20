package de.littletrotter.application.reference

import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.domain.reference.B2bProfile
import de.littletrotter.domain.reference.B2bProfileDefinition
import de.littletrotter.domain.reference.B2bProfileField
import de.littletrotter.domain.repository.B2bProfileRepository
import de.littletrotter.application.invoice.port.outbound.InvoiceLookupRepository
import de.littletrotter.domain.repository.RecipientRepository
import de.littletrotter.domain.shared.SortDirection
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Transactional

/**
 * Profile CRUD/lifecycle: orchestrates the [B2bProfile] row itself: listing, save (name-uniqueness), delete
 * (in-use guards) and the deep-copy.
 */
@ApplicationScoped
class B2bProfileLifecycleService @Inject constructor(
    private val profileRepository: B2bProfileRepository,
    // The delete-guard checks recipients (the profile's standing-default holders),
    // not senders: the assignment moved off the seller entirely.
    private val recipientRepository: RecipientRepository,
    private val invoiceLookup: InvoiceLookupRepository,
    private val time: TimeSource,
) {
    fun findProfiles(
        pageIndex: Int = 0,
        pageSize: Int = 60,
        filterText: String? = null,
        sortDir: SortDirection = SortDirection.ASC,
    ): List<B2bProfile> =
        profileRepository.findAll(pageIndex, pageSize, filterText, sortDir)

    fun countProfiles(filterText: String? = null): Long = profileRepository.count(filterText)
    /** Count of profiles with `active = false`, header counter's "N inaktiv" half. */
    fun countInactiveProfiles(filterText: String? = null): Long = profileRepository.countInactive(filterText)

    fun findProfileById(id: Long): B2bProfile? = profileRepository.findById(id)

    fun findAllActiveProfiles(): List<B2bProfile> = profileRepository.findAllActive()

    @Transactional
    fun saveProfile(profile: B2bProfile): B2bProfile {
        profile.validate(time.today())
        profileRepository.findByName(profile.name)?.let { existing ->
            if (existing.id != profile.id) {
                throw InvalidParameterException("Ein Profil mit dem Namen '${profile.name}' existiert bereits.")
            }
        }
        return profileRepository.save(profile)
    }

    @Transactional
    fun deleteProfile(id: Long) {
        // A profile assigned to at least one recipient (as their standing default) must
        // not be deleted; the message names the affected customers instead of a generic
        // FK-violation.
        val assignedRecipients = recipientRepository.findNamesByB2bProfileId(id)
        if (assignedRecipients.isNotEmpty()) {
            throw InvalidParameterException(
                "Profil ist folgenden Kunden zugeordnet und kann nicht gelöscht werden: " +
                    assignedRecipients.joinToString(", ")
            )
        }
        // A recipient's standing default is not the only way an invoice can end up
        // referencing this profile: a manual override lets any invoice pin its own
        // b2b_profile_id independent of its recipient's default (Ep4BorderCasesIT's own "mid-edit
        // draft" cases). That FK is just as NO-ACTION-guarded as the recipient one, so without this
        // check the delete would reach Postgres and surface as a raw persistence exception instead
        // of this same readable German error.
        if (invoiceLookup.existsByB2bProfileId(id)) {
            throw InvalidParameterException(
                "Profil wird von mindestens einer Rechnung über eine individuelle Zuordnung " +
                    "verwendet und kann nicht gelöscht werden."
            )
        }
        profileRepository.delete(id)
    }

    /**
     * Deep-copies [profileId] and every one of its fields (including the country
     * scope) into a new, independently editable profile: the seeded standard profile's own
     * description already advertises "Kopieren und anpassen statt bei null anfangen". The copy's
     * name is [uniqueCopyName]'s de-duplicated
     * `"<Original> (Kopie)"`, always `active = true` regardless of the source's own state, and
     * `countries` is copied as-is (empty stays empty = unscoped).
     *
     * One transaction: a failure partway through a multi-field profile (e.g. a stray invariant
     * violation) must never leave a half-copied profile behind. Field [B2bProfileField
     * .conditionTerm] references need no remapping on the copy — they are `B2bTerm` constants,
     * not row FKs, so the condition graph (including a mutual conditional-if-empty pair) stays
     * intact verbatim.
     */
    @Transactional
    fun copy(profileId: Long): B2bProfile {
        val source = profileRepository.findById(profileId)
            ?: throw InvalidParameterException("Profil mit ID $profileId existiert nicht.")
        val copyProfile = B2bProfile.create(
            B2bProfileDefinition(
                name = uniqueCopyName(source.name),
                description = source.description,
                active = true,
                countries = source.countries.toSet(),
            ),
        )
        copyProfile.validate(time.today()) // same self-contained check saveProfile() runs; belt-and-braces
        // Deep-copy semantics: every field definition is copied on purpose, and a shared
        // copier would hide which fields a profile copy carries over.
        profileRepository.findFieldsByProfileIds(listOf(profileId))[profileId].orEmpty().forEach { field ->
            copyProfile.defineField(B2bProfileField.create(copyProfile, field.definition()))
        }
        return profileRepository.save(copyProfile)
    }

    /** [copy]'s naming rule: `"<originalName> (Kopie)"`, or `"<originalName> (Kopie 2)"`, `"...
     *  (Kopie 3)"`, … the first one not already taken — [B2bProfileRepository.findByName] is the
     *  same case-insensitive lookup [saveProfile]'s own uniqueness check uses, so a copy can never
     *  collide with the unique-name constraint it enforces. */
    private fun uniqueCopyName(originalName: String): String {
        val base = "$originalName (Kopie)"
        if (profileRepository.findByName(base) == null) return base
        var suffix = 2
        while (true) {
            val candidate = "$originalName (Kopie $suffix)"
            if (profileRepository.findByName(candidate) == null) return candidate
            suffix++
        }
    }
}
