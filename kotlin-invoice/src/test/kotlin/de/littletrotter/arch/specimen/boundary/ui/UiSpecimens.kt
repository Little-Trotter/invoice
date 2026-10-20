package de.littletrotter.arch.specimen.boundary.ui

import de.littletrotter.arch.specimen.boundary.application.SpecimenAppService
import de.littletrotter.arch.specimen.boundary.application.SpecimenMarkerContract
import de.littletrotter.arch.specimen.boundary.application.SpecimenServiceContract
import de.littletrotter.arch.specimen.boundary.application.SpecimenValueCarrier
import de.littletrotter.arch.specimen.boundary.application.dto.SpecimenDto
import de.littletrotter.arch.specimen.boundary.application.port.inbound.SpecimenQueryPort

/** Reaches a container-managed service and a bean-backed contract directly: rule (b) must report both. */
class SpecimenViolatingView(
    private val service: SpecimenAppService,
    private val contract: SpecimenServiceContract,
) {
    fun render(): String = service.work() + contract.work()
}

/** Uses only an input port, a DTO, a value carrier and a marker contract: rule (b) stays silent. */
class SpecimenCleanView(
    private val queries: SpecimenQueryPort,
    private val marker: SpecimenMarkerContract? = null,
) {
    fun render(): String = queries.find(1)?.let { SpecimenDto(1, it).label + SpecimenValueCarrier(it).value + marker }.orEmpty()
}
