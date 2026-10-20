package de.littletrotter.domain.shared.converters

import de.littletrotter.shared.document.DocumentLanguage
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * Persists [DocumentLanguage] as its lower-case [DocumentLanguage.code] (`de`/`en`), the value the
 * `language` table references, so the column and every rendered string stay exactly as they were
 * when the language was a plain string.
 */
@Converter(autoApply = false)
class DocumentLanguageConverter : AttributeConverter<DocumentLanguage, String> {
    override fun convertToDatabaseColumn(attribute: DocumentLanguage?): String? = attribute?.code

    override fun convertToEntityAttribute(dbData: String?): DocumentLanguage? = dbData?.let { code ->
        DocumentLanguage.entries.firstOrNull { it.code == code } ?: error("Unknown document language code '$code' in stored invoice")
    }
}
