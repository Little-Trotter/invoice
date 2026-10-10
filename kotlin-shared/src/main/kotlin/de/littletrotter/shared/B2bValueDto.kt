package de.littletrotter.shared

import de.littletrotter.shared.b2b.B2bTerm

/**
 * One captured B2B free-field value: the business term plus the anwender-entered
 * value. Carried generically (not one typed field per term) so the wire format stays stable as the
 * curated catalog grows; the consuming side (kotlin-xpdf mustang export, PDF renderer) maps each
 * known term to its specific XML/PDF slot. Empty list = no B2B profile active (Community) — the
 * wire is byte-identical to a message before this field existed. [term] is carried by its enum name
 * over the wire — the term taxonomy itself lives typed in [B2bTerm], both sides
 * of the wire share the same knowledge, so only the code travels.
 */
data class B2bValueDto(val term: B2bTerm, val value: String)
