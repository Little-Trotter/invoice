package de.littletrotter.application.reference.port.inbound

import de.littletrotter.application.dto.B2bProfileFieldDto
import de.littletrotter.application.dto.B2bSellerFieldGroupDto
import de.littletrotter.shared.b2b.B2bTerm

/**
 * Input port for reading the fields a B2B profile defines: the profile tab's field editor and the
 * placement-specific field lists the seller form, the invoice header and the invoice item grid
 * render.
 */
interface B2bFieldDefinitionQueryPort {
    fun findB2bProfileFields(profileId: Long): List<B2bProfileFieldDto>

    /** Catalog terms not already used by another field of [profileId] (`currentFieldId` excluded
     *  from that check so an in-edit field's own term stays selectable). */
    fun findSelectableB2bCatalogEntries(profileId: Long?, currentFieldId: Long? = null): List<B2bTerm>

    fun findB2bSellerFields(profileId: Long): List<B2bProfileFieldDto>

    /** The `SELLER`/`BOTH`-placed fields of every profile, grouped for the seller form. */
    fun findB2bSellerFieldsUnion(): List<B2bSellerFieldGroupDto>

    /** `FREE` `HEADER` fields of [profileId] that render on the invoice header (placement
     *  `INVOICE`/`BOTH`). `null` profile = no section. */
    fun findB2bInvoiceHeaderFields(profileId: Long?): List<B2bProfileFieldDto>

    /** `FREE` `LINE` fields of [profileId] that render on a position. */
    fun findB2bInvoiceItemFields(profileId: Long?): List<B2bProfileFieldDto>

    /** `FREE` `HEADER` fields of [profileId] that render on the customer form's "B2B-Felder"
     *  section (placement `RECIPIENT`). `null` profile = no section. */
    fun findB2bRecipientFields(profileId: Long?): List<B2bProfileFieldDto>
}
