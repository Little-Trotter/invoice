package de.littletrotter.domain.repository

import de.littletrotter.domain.compliance.ValidationProfile

interface ValidationProfileRepository {
    /** The currently applicable row for [profileId] (highest [ValidationProfile.validFrom],
     *  ties broken by id) — `null` when the identifier is unregistered (F4: an unregistered
     *  profile blocks finalization the same as [de.littletrotter.domain.compliance
     *  .ValidationProfileState.BLOCKED]). */
    fun findCurrentByProfileId(profileId: String): ValidationProfile?
    fun findAll(): List<ValidationProfile>
    fun save(profile: ValidationProfile): ValidationProfile
}
