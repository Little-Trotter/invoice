package de.littletrotter.application.reference

import de.littletrotter.application.masterdata.MasterDataNumberService
import de.littletrotter.application.reference.port.inbound.NumberRangeQueryPort
import de.littletrotter.domain.reference.Continuity
import de.littletrotter.domain.reference.Language
import de.littletrotter.domain.reference.NumberRange
import de.littletrotter.domain.reference.NumberRangeSubject
import de.littletrotter.domain.reference.PrefixSource
import de.littletrotter.domain.repository.LanguageRepository
import de.littletrotter.domain.repository.NumberCounterRepository
import de.littletrotter.domain.repository.NumberRangeRepository
import de.littletrotter.shared.catalogue.InvoiceItemCategory
import de.littletrotter.shared.numbering.DatePart
import de.littletrotter.application.dto.NumberRangeDto
import de.littletrotter.domain.exception.InvalidParameterException
import org.junit.jupiter.api.Assertions.assertThrows
import org.mockito.kotlin.never
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Number-range, PDF-template and language reads delegate unchanged; number-range writes check
 *  the domain rules and the prefix lock. */
class NumberRangePortsTest {

    @Test
    fun `NumberRangeQueries delegates paging, counts, lookup, counter value and proposals`() {
        val rangeRepository = mock<NumberRangeRepository>()
        val counterRepository = mock<NumberCounterRepository>()
        val numberService = mock<MasterDataNumberService>()
        val range = NumberRange().apply { id = 7L; code = "ART"; displayName = "Artikel" }
        whenever(rangeRepository.findAll(0, 60, null)).thenReturn(listOf(range))
        whenever(rangeRepository.count(null)).thenReturn(2L)
        whenever(rangeRepository.countInactive(null)).thenReturn(1L)
        whenever(rangeRepository.findById(7L)).thenReturn(range)
        whenever(counterRepository.currentValue(NumberRangeSubject.INVOICE)).thenReturn(42L)
        whenever(numberService.proposeArticle(eq(InvoiceItemCategory.PRODUCT))).thenReturn("ART-1")
        whenever(numberService.proposeSeller(eq("Nekar"))).thenReturn("VK-1")
        val queries: NumberRangeQueryPort = NumberRangeQueries(rangeRepository, counterRepository, numberService)

        val row = NumberRangeDto(id = 7L, version = 0L, code = "ART", displayName = "Artikel")
        assertEquals(listOf(row), queries.findNumberRanges())
        assertEquals(2L, queries.countNumberRanges())
        assertEquals(1L, queries.countInactiveNumberRanges())
        assertEquals(row, queries.findNumberRangeById(7L))
        assertNull(queries.findNumberRangeById(8L))
        assertEquals(42L, queries.numberRangeCurrentValue(NumberRangeSubject.INVOICE))
        assertEquals("ART-1", queries.proposeArticleNumber(InvoiceItemCategory.PRODUCT))
        assertEquals("VK-1", queries.proposeSellerNumber("Nekar"))
    }

    @Test
    fun `ManageNumberRange saves a new range and returns the stored row`() {
        val rangeRepository = mock<NumberRangeRepository>()
        whenever(rangeRepository.save(any())).thenAnswer { (it.arguments[0] as NumberRange).apply { id = 3L } }

        val saved = ManageNumberRange(rangeRepository, mock()).saveNumberRange(NumberRangeDto(code = "KD", displayName = "Kunden"))

        assertEquals(3L, saved.id)
        assertEquals("KD", saved.code)
    }

    @Test
    fun `ManageNumberRange rejects an invalid range with the domain message`() {
        val ex = assertThrows(InvalidParameterException::class.java) {
            ManageNumberRange(mock(), mock()).saveNumberRange(NumberRangeDto(code = "KD", displayName = "Kunden", prefixSource = PrefixSource.FIXED))
        }
        assertEquals("Fester Präfix darf nicht leer sein, wenn prefixSource = FIXED ist.", ex.message)
    }

    @Test
    fun `ManageNumberRange rejects a prefix change once a number was allocated`() {
        val rangeRepository = mock<NumberRangeRepository>()
        val counterRepository = mock<NumberCounterRepository>()
        whenever(rangeRepository.findById(7L)).thenReturn(NumberRange().apply { id = 7L; version = 2L; code = "KD"; displayName = "Kunden" })
        whenever(counterRepository.hasBeenUsed(7L)).thenReturn(true)
        val edit = NumberRangeDto(id = 7L, version = 2L, code = "KD", displayName = "Kunden neu", prefixSource = PrefixSource.FIXED, fixedPrefix = "K-")

        val ex = assertThrows(InvalidParameterException::class.java) {
            ManageNumberRange(rangeRepository, counterRepository).saveNumberRange(edit)
        }

        assertTrue(ex.message!!.startsWith("Das Präfix des Nummernkreises 'Kunden' kann nicht mehr geändert werden"))
        verify(rangeRepository, never()).save(any())
    }

    @Test
    fun `ManageNumberRange saves an edit that keeps the prefix, or changes it before any allocation`() {
        val rangeRepository = mock<NumberRangeRepository>()
        val counterRepository = mock<NumberCounterRepository>()
        whenever(rangeRepository.findById(7L)).thenAnswer { NumberRange().apply { id = 7L; version = 2L; code = "KD"; displayName = "Kunden" } }
        whenever(counterRepository.hasBeenUsed(7L)).thenReturn(false)
        whenever(rangeRepository.save(any())).thenAnswer { it.arguments[0] as NumberRange }
        val manage = ManageNumberRange(rangeRepository, counterRepository)

        assertEquals("Kunden neu", manage.saveNumberRange(NumberRangeDto(id = 7L, version = 2L, code = "KD", displayName = "Kunden neu")).displayName)
        val changed = manage.saveNumberRange(
            NumberRangeDto(id = 7L, version = 2L, code = "KD", displayName = "Kunden", prefixSource = PrefixSource.FIXED, fixedPrefix = "K-"),
        )
        assertEquals("K-", changed.fixedPrefix)
    }

    private fun quoteRange(id: Long?, name: String, prefix: String?, datePart: DatePart) = NumberRange().apply {
        this.id = id
        code = "Q$id"
        displayName = name
        subject = NumberRangeSubject.QUOTE
        prefixSource = if (prefix == null) PrefixSource.NONE else PrefixSource.FIXED
        fixedPrefix = prefix
        this.datePart = datePart
    }

    private fun quoteDto(id: Long? = null, version: Long = 0L, prefix: String?, datePart: DatePart) = NumberRangeDto(
        id = id,
        version = version,
        code = "AN",
        displayName = "Angebote neu",
        subject = NumberRangeSubject.QUOTE,
        continuity = Continuity.GAPLESS,
        prefixSource = if (prefix == null) PrefixSource.NONE else PrefixSource.FIXED,
        fixedPrefix = prefix,
        datePart = datePart,
    )

    private fun saveQuoteAgainst(existing: NumberRange, dto: NumberRangeDto): NumberRange {
        val rangeRepository = mock<NumberRangeRepository>()
        whenever(rangeRepository.findBySubject(NumberRangeSubject.QUOTE)).thenReturn(listOf(existing))
        whenever(rangeRepository.save(any())).thenAnswer { it.arguments[0] as NumberRange }
        val saved = ManageNumberRange(rangeRepository, mock()).saveNumberRange(dto)
        return NumberRange().apply { datePart = saved.datePart }
    }

    @Test
    fun `ManageNumberRange compares quote prefixes ignoring case and surrounding whitespace`() {
        assertThrows(InvalidParameterException::class.java) {
            saveQuoteAgainst(quoteRange(1L, "Angebote alt", "ANG", DatePart.YEAR), quoteDto(prefix = "ang", datePart = DatePart.YEAR))
        }
        assertThrows(InvalidParameterException::class.java) {
            saveQuoteAgainst(quoteRange(1L, "Angebote alt", "ANG", DatePart.YEAR), quoteDto(prefix = " Ang ", datePart = DatePart.YEAR))
        }
    }

    @Test
    fun `ManageNumberRange allows a quote range to change only the case of its own prefix or differ in date part`() {
        val own = quoteRange(1L, "Angebote", "ANG", DatePart.YEAR)
        val rangeRepository = mock<NumberRangeRepository>()
        whenever(rangeRepository.findBySubject(NumberRangeSubject.QUOTE)).thenReturn(listOf(own))
        whenever(rangeRepository.findById(1L)).thenReturn(own)
        whenever(rangeRepository.save(any())).thenAnswer { it.arguments[0] as NumberRange }
        val manage = ManageNumberRange(rangeRepository, mock())

        assertEquals("ang", manage.saveNumberRange(quoteDto(id = 1L, prefix = "ang", datePart = DatePart.YEAR)).fixedPrefix)
    }

    @Test
    fun `ManageNumberRange allows the same quote prefix with a different date part`() {
        val saved = saveQuoteAgainst(quoteRange(2L, "Anders", "ANG", DatePart.YEAR), quoteDto(prefix = "ang", datePart = DatePart.YEAR_MONTH))
        assertEquals(DatePart.YEAR_MONTH, saved.datePart)
    }

    @Test
    fun `ManageNumberRange rejects a quote range that shares prefix and date part with another one`() {
        val rangeRepository = mock<NumberRangeRepository>()
        whenever(rangeRepository.findBySubject(NumberRangeSubject.QUOTE))
            .thenReturn(listOf(quoteRange(1L, "Angebote alt", " AN- ", DatePart.YEAR)))

        val ex = assertThrows(InvalidParameterException::class.java) {
            ManageNumberRange(rangeRepository, mock()).saveNumberRange(quoteDto(prefix = "AN-", datePart = DatePart.YEAR))
        }

        assertEquals(
            "Präfix und Datumsanteil werden bereits vom Angebots-Nummernkreis 'Angebote alt' verwendet. " +
                "Angebotsnummern müssen eindeutig bleiben; wählen Sie einen anderen Präfix oder Datumsanteil.",
            ex.message,
        )
        verify(rangeRepository, never()).save(any())
    }

    @Test
    fun `ManageNumberRange accepts a quote range whose date part or prefix differs`() {
        val rangeRepository = mock<NumberRangeRepository>()
        whenever(rangeRepository.findBySubject(NumberRangeSubject.QUOTE)).thenReturn(
            listOf(quoteRange(1L, "Angebote alt", "AN-", DatePart.YEAR), quoteRange(2L, "Ohne Präfix", null, DatePart.YEAR)),
        )
        whenever(rangeRepository.save(any())).thenAnswer { it.arguments[0] as NumberRange }
        val manage = ManageNumberRange(rangeRepository, mock())

        assertEquals("AN-", manage.saveNumberRange(quoteDto(prefix = "AN-", datePart = DatePart.YEAR_MONTH)).fixedPrefix)
        assertEquals("AB-", manage.saveNumberRange(quoteDto(prefix = "AB-", datePart = DatePart.YEAR)).fixedPrefix)
    }

    @Test
    fun `ManageNumberRange treats prefix source NONE as the empty prefix when comparing`() {
        val rangeRepository = mock<NumberRangeRepository>()
        whenever(rangeRepository.findBySubject(NumberRangeSubject.QUOTE))
            .thenReturn(listOf(quoteRange(2L, "Ohne Präfix", null, DatePart.YEAR)))

        assertThrows(InvalidParameterException::class.java) {
            ManageNumberRange(rangeRepository, mock()).saveNumberRange(quoteDto(prefix = null, datePart = DatePart.YEAR))
        }
    }

    @Test
    fun `ManageNumberRange lets a quote range be edited when it is the only match`() {
        val rangeRepository = mock<NumberRangeRepository>()
        val stored = quoteRange(5L, "Angebote", "AN-", DatePart.YEAR).apply { version = 1L }
        whenever(rangeRepository.findById(5L)).thenReturn(stored)
        whenever(rangeRepository.findBySubject(NumberRangeSubject.QUOTE)).thenReturn(listOf(stored))
        whenever(rangeRepository.save(any())).thenAnswer { it.arguments[0] as NumberRange }

        val saved = ManageNumberRange(rangeRepository, mock())
            .saveNumberRange(quoteDto(id = 5L, version = 1L, prefix = "AN-", datePart = DatePart.YEAR))

        assertEquals("Angebote neu", saved.displayName)
    }

    @Test
    fun `ManageNumberRange does not look at other ranges for a subject other than QUOTE`() {
        val rangeRepository = mock<NumberRangeRepository>()
        whenever(rangeRepository.save(any())).thenAnswer { it.arguments[0] as NumberRange }

        ManageNumberRange(rangeRepository, mock()).saveNumberRange(NumberRangeDto(code = "KD", displayName = "Kunden"))

        verify(rangeRepository, never()).findBySubject(any())
    }

    @Test
    fun `ManageNumberRange deletes through the repository`() {
        val rangeRepository = mock<NumberRangeRepository>()

        ManageNumberRange(rangeRepository, mock()).deleteNumberRange(7L)

        verify(rangeRepository).delete(7L)
    }

    @Test
    fun `LanguageQueries returns the repository's active languages in order`() {
        val repository = mock<LanguageRepository>()
        val languages = listOf(Language().apply { code = "de" }, Language().apply { code = "en" })
        whenever(repository.findActiveOrdered()).thenReturn(languages)

        assertEquals(languages.map { it.toDto() }, LanguageQueries(repository).findActiveLanguages())
    }
}
