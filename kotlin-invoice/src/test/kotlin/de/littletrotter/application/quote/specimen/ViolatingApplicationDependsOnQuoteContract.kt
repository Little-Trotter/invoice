package de.littletrotter.application.quote.specimen

import de.littletrotter.shared.quote.CreateQuoteRequest

/**
 * Negative specimen for [de.littletrotter.arch.ArchitectureRulesTest]'s "domain and application
 * never depend on the quote contract" rule: an application-package class depending directly on the
 * contract type [CreateQuoteRequest], exactly what the anti-corruption-layer rule forbids on this
 * side of the boundary. Test-only, no business data, never wired into any production path.
 */
class ViolatingApplicationDependsOnQuoteContract(val request: CreateQuoteRequest)
