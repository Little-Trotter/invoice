package de.littletrotter.domain.invoice.specimen

import de.littletrotter.application.invoice.InvoiceItemCommand

/**
 * negative specimen for [de.littletrotter.arch.CommandBoundaryArchitectureTest] —
 * a domain-package class depending on an application-layer [Command][de.littletrotter.application.command.Command]
 * type, exactly the direction forbids (the domain must stay independent of the commands
 * that translate into it, not the other way round). Test-only, no business data.
 */
class ViolatingDomainDependsOnCommand(val command: InvoiceItemCommand)
