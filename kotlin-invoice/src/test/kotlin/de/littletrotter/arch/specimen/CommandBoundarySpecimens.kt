package de.littletrotter.arch.specimen

import de.littletrotter.application.command.Command
import de.littletrotter.application.command.CommandEntryPoint
import de.littletrotter.domain.invoice.Invoice
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional

/**
 * Test-only specimens for [de.littletrotter.arch.CommandBoundaryArchitectureTest].
 * Deliberately violating classes, one per rule, that prove each ArchUnit rule actually
 * rejects a real violation rather than only ever passing on the current codebase. None of these
 * classes carry business data or are wired into any production path; they exist purely as
 * ArchUnit import targets. Package name (`..specimen`) is what the architecture test's own sanity
 * check uses to prove they never leak into the production scan.
 */

/** A command depending directly on [EntityManager]. */
class ViolatingCommandWithEntityManager(val entityManager: EntityManager) : Command

/** An entry point whose method both accepts and returns the JPA
 *  entity [Invoice] itself (this package also does not live under `..application..`) —
 *  exactly the kind of leak forbidden. */
interface ViolatingEntryPoint : CommandEntryPoint {
    fun leaky(invoice: Invoice): Invoice
}

/** A "command" with a mutable (`var`) property, i.e. a public setter. */
class ViolatingMutableCommand(var mutableField: String) : Command

/** A command that is itself the transactional handler, which the persistence
 *  decision explicitly forbids (a command is a data carrier, not a transaction boundary). */
@Transactional
class ViolatingTransactionalCommand(val value: String) : Command
