package de.littletrotter.shared.wording

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.text.MessageFormat

/** [TermSubstitution.apply] in isolation: placeholder substitution, the unknown-term
 *  fallback (border case: "both profiles missing a term key -> parity test red at build time,
 *  never at runtime" — this test covers the runtime side, a resolver that legitimately has
 *  nothing for a name), and that a value carrying both a `{term:...}` marker and a numbered
 *  `{0}` MessageFormat argument still works once the marker is resolved first. */
class TermSubstitutionTest {

    @Test
    fun `a known placeholder is replaced with the resolver's value`() {
        val result = TermSubstitution.apply("{term:product.singular} hinzufügen") { term ->
            if (term == Term.PRODUCT_SINGULAR) "Artikel" else null
        }
        assertEquals("Artikel hinzufügen", result)
    }

    @Test
    fun `a value with no marker is returned unchanged`() {
        val result = TermSubstitution.apply("Rechnung Nr. {0}") { error("must not be called") }
        assertEquals("Rechnung Nr. {0}", result)
    }

    @Test
    fun `several markers in one value all resolve`() {
        val result = TermSubstitution.apply("{term:product.singular} / {term:product.plural}") { term ->
            when (term) {
                Term.PRODUCT_SINGULAR -> "Material"
                Term.PRODUCT_PLURAL -> "Materialien"
                else -> null
            }
        }
        assertEquals("Material / Materialien", result)
    }

    @Test
    fun `an unknown placeholder name degrades to a marker, like a missing i18n key`() {
        val result = TermSubstitution.apply("{term:not.a.real.term}") { null }
        assertEquals("!{term:not.a.real.term}!", result)
    }

    @Test
    fun `a resolver returning null for a known term also degrades to a marker`() {
        val result = TermSubstitution.apply("{term:product.singular}") { null }
        assertEquals("!{term:product.singular}!", result)
    }

    @Test
    fun `nested MessageFormat args still work once the marker is resolved first`() {
        val substituted = TermSubstitution.apply("{0} {term:product.plural} gefunden") { term ->
            if (term == Term.PRODUCT_PLURAL) "Artikel" else null
        }
        val formatted = MessageFormat.format(substituted, 3)
        assertEquals("3 Artikel gefunden", formatted)
    }
}
