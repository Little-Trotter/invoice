package de.littletrotter.arch.specimen.boundary.application.port.inbound

/** Input port specimen: a view may depend on it although a container-managed bean implements it. */
interface SpecimenQueryPort {
    fun find(id: Long): String?
}

/** Input port whose read model an infrastructure adapter implements by design: rule (a) stays silent. */
interface SpecimenReadModelQueryPort {
    fun count(): Long
}
