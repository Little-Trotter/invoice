package de.littletrotter.domain.repository

import de.littletrotter.domain.reference.B2bProfileField

interface B2bProfileFieldRepository {
    fun findByProfileId(profileId: Long): List<B2bProfileField>
    fun findById(id: Long): B2bProfileField?
    fun save(field: B2bProfileField): B2bProfileField
    fun delete(id: Long)
}
