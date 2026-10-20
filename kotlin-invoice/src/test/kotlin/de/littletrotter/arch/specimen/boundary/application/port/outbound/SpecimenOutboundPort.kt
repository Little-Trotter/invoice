package de.littletrotter.arch.specimen.boundary.application.port.outbound

/** Outbound port specimen implemented by an infrastructure adapter: rule (a) stays silent. */
interface SpecimenOutboundPort {
    fun store(value: String)
}
