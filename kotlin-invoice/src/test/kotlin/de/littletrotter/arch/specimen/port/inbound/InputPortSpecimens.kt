package de.littletrotter.arch.specimen.port.inbound

import de.littletrotter.application.command.CommandEntryPoint

/**
 * Test-only specimens for [de.littletrotter.arch.InputPortArchitectureTest]: one deliberately
 * violating input port per rule, proving each rule rejects a real violation. Never wired into a
 * production path; the package mirrors `..port.inbound..` so the rules' own scope applies.
 */

/** A well-formed query port, extended by the composite below. */
interface SpecimenQueryPort {
    fun find(id: Long): String?
}

/** Composite port: extends another inbound port instead of declaring its own role. */
interface SpecimenCompositeQueryPort : SpecimenQueryPort {
    fun count(): Long
}

/** Composite interface: bundles two roles instead of declaring one of its own. */
interface SpecimenCombinedQueryPort : SpecimenQueryPort, SpecimenUnmarkedUseCase

/** Port with more than 12 methods. */
interface SpecimenOversizedQueryPort {
    fun m01(): Int
    fun m02(): Int
    fun m03(): Int
    fun m04(): Int
    fun m05(): Int
    fun m06(): Int
    fun m07(): Int
    fun m08(): Int
    fun m09(): Int
    fun m10(): Int
    fun m11(): Int
    fun m12(): Int
    fun m13(): Int
}

/** Query port that is marked as a command entry point. */
interface SpecimenCommandMarkedQueryPort : CommandEntryPoint {
    fun find(id: Long): String?
}

/** Use case without the command entry point marker. */
interface SpecimenUnmarkedUseCase {
    fun execute(id: Long)
}

/** Type in a port package whose name follows neither port convention. */
interface SpecimenInvoiceFacade {
    fun find(id: Long): String?
}
