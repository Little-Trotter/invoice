package de.littletrotter.application.masterdata.port.inbound

import de.littletrotter.application.dto.SenderProfileDetailDto
import de.littletrotter.application.dto.SenderProfileRowDto
import de.littletrotter.application.dto.SenderProfileSort

/**
 * Input port for reading sender profiles in the maintenance screen: the paged grid rows and the
 * completely loaded profile the edit dialog is built from.
 */
interface SenderProfileQueryPort {
    fun findAll(pageIndex: Int, pageSize: Int, filterText: String?): List<SenderProfileRowDto>

    fun findAllSorted(pageIndex: Int, pageSize: Int, filterText: String?, sort: SenderProfileSort): List<SenderProfileRowDto>

    fun count(filterText: String?): Long

    /** Lean lookup of one row, used to detect a concurrent change or deletion. */
    fun findRowById(id: Long): SenderProfileRowDto?

    /** Transactional and complete (address, default payment term, notes, extension state), so
     *  the dialog never reads an unloaded relation. */
    fun findForEdit(id: Long): SenderProfileDetailDto?
}
