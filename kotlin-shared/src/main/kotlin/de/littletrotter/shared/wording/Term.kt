package de.littletrotter.shared.wording

/**
 * Profile-dependent product and service labels shared by catalogue, invoice and exports.
 * A service category uses plural "Arbeitsstunden" for HANDWERK while singular service
 * nouns remain available for text that refers to one work hour.
 *
 * [placeholder] is the exact string a bundle value spells inside a `{term:…}` marker (see
 * [TermSubstitution]) and the suffix a term-bundle key carries after `term.<profile>.`.
 */
enum class Term(val placeholder: String) {
    PRODUCT_SINGULAR("product.singular"),
    PRODUCT_PLURAL("product.plural"),
    SERVICE_SINGULAR("service.singular"),
    SERVICE_PLURAL("service.plural"),
    SERVICE_CATEGORY("service.category"),
    ;

    companion object {
        fun fromPlaceholder(placeholder: String): Term? = entries.firstOrNull { it.placeholder == placeholder }
    }
}
