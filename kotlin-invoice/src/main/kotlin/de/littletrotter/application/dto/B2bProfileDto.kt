package de.littletrotter.application.dto

import de.littletrotter.shared.country.Country

/**
 * One B2B profile: what the profile catalogue grid shows and its editor saves, and what the
 * profile selectors (customer form, invoice header, PDF-template editor) pick from.
 *
 * [countries] is the profile's country scope; empty means the profile applies to every seller.
 * [id] `null` marks a new profile; [version] is its optimistic-lock revision, checked on save.
 */
data class B2bProfileDto(
    val id: Long? = null,
    val version: Long? = null,
    val name: String = "",
    val description: String? = null,
    val active: Boolean = true,
    val countries: Set<Country> = emptySet(),
)
