package de.littletrotter.domain.masterdata

import de.littletrotter.domain.reference.Language

/**
 * [PaymentTerm.name] no longer exists (it is now [PaymentTerm.nameGroup], a
 * [de.littletrotter.domain.reference.LocalizedTextGroup]); every fixture across the test suite
 * that used to write `PaymentTerm().apply { name = "..." }` directly now goes through
 * [withName] instead. `id = 1`/`code = "de"`/`isFallback = true` mirrors the `language` row V1
 * seeds — a detached [Language] instance is enough for [PaymentTerm.setName]'s own `id`-based
 * comparisons; a persistence-layer test that actually flushes needs the real managed row instead
 * (`em.find(Language::class.java, 1)`).
 */
internal fun germanFallbackLanguage(): Language = Language().apply { id = 1; code = "de"; isFallback = true }

/** Sets [PaymentTerm]'s German (fallback) name — the one-liner replacement for the former
 *  `name = "..."` assignment. Returns `this` so it composes inside an existing `.apply { }`. */
internal fun PaymentTerm.withName(name: String): PaymentTerm = apply { setName(germanFallbackLanguage(), name) }
