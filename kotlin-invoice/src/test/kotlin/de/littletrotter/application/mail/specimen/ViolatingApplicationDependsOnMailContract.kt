package de.littletrotter.application.mail.specimen

import de.littletrotter.shared.mail.MailComposeRequest

/**
 * Negative specimen for
 * [de.littletrotter.arch.ArchitectureRulesTest]'s "domain and application never depend on the mail
 * contract" rule — an application-package class depending directly on the contract type
 * [MailComposeRequest], exactly what the anti-corruption-layer rule forbids on this side of the boundary. Test-only, no
 * business data, never wired into any production path.
 */
class ViolatingApplicationDependsOnMailContract(val request: MailComposeRequest)
