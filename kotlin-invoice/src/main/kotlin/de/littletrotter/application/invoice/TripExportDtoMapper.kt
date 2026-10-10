package de.littletrotter.application.invoice

import de.littletrotter.application.export.TripExportNotFoundException
import de.littletrotter.application.export.TripWorkbookExportPort
import de.littletrotter.application.export.TripWorkbookView
import de.littletrotter.domain.invoice.Invoice
import de.littletrotter.domain.invoice.InvoiceItem
import de.littletrotter.domain.invoice.TripExpense
import de.littletrotter.domain.invoice.TripExpenseKind
import de.littletrotter.shared.PayloadExchange
import de.littletrotter.shared.PerDiemDayExportDto
import de.littletrotter.shared.TripExpenseExportDto
import de.littletrotter.shared.TripExportDto
import de.littletrotter.shared.TripWorkbookRefDto
import de.littletrotter.shared.wording.TermResolver
import de.littletrotter.shared.wording.TermSubstitution
import de.littletrotter.shared.wording.WordingProfile
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.Base64
import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/**
 * [InvoiceDtoMapper]'s `TRIP` position export, extracted verbatim.
 */
@ApplicationScoped
class TripExportDtoMapper
@Inject
constructor(
    private val tripWorkbookExportPort: TripWorkbookExportPort?,
    private val payloadExchange: PayloadExchange?,
) {
    private val log = Logger.getLogger(TripExportDtoMapper::class.java)

    companion object {
        /** One day of headroom: `kotlin-xpdf` resolves the ref within seconds of the job
         *  landing. */
        private const val TRIP_WORKBOOK_TTL_SECONDS = 86_400L
    }

    /** The export-side detail of a TRIP position; only ever called for one. Unit label
     *  follows the same document-language rule the item's own label already applies. [receiptsCount]
     *  is the item mapper's own already-resolved item-receipt count, passed through only for
     *  [buildWorkbookRef]'s [TripWorkbookView]. */
    fun buildTripExportDto(item: InvoiceItem, invoice: Invoice, receiptsCount: Int, wordingProfile: WordingProfile): TripExportDto {
        val english = invoice.documentLanguage == "en"
        val expenseDtos = item.tripExpenses.map { expense ->
            TripExpenseExportDto(
                kindLabelKey = expense.kind.labelKey,
                description = expense.description.ifBlank {
                    if (expense.kind == TripExpenseKind.TRAVEL_TIME && !expense.rateProvided) TripExpense.TRAVEL_TIME_KULANZ_TEXT else ""
                },
                travelDate = expense.travelDate?.toString(),
                quantity = expense.quantity,
                unitCode = expense.unit.uneceCode,
                unitLabel = expense.unit.invoiceLabel(
                    english = english,
                    plural = expense.quantity.compareTo(BigDecimal.ONE) != 0,
                ),
                unitPriceNet = expense.unitPriceNet,
                netAmount = expense.netAmount,
            )
        }
        val perDiemDtos = item.perDiemEntries.map { entry ->
            PerDiemDayExportDto(
                date = entry.travelDate?.toString(),
                isArrivalDeparture = entry.isArrivalDeparture,
                breakfastDeduction = entry.breakfastDeduction,
                lunchDeduction = entry.lunchDeduction,
                dinnerDeduction = entry.dinnerDeduction,
                claimableAmount = entry.calculatedAmount,
                description = entry.description.ifBlank { null },
                baseRate = if (entry.isArrivalDeparture) entry.arrivalDepartureRateSnapshot else entry.fullDayRateSnapshot,
                participant = entry.participant.ifBlank { null },
                employerContribution = entry.employerContribution,
            )
        }
        val trip = TripExportDto(
            detailedOnInvoice = item.tripDetailedOnInvoice,
            fixedNetPrice = item.tripFixedNetPrice,
            calculatedNet = item.calculatedTripNet(),
            expenses = expenseDtos,
            perDiemDays = perDiemDtos,
        )
        return trip.copy(workbookRef = item.buildWorkbookRef(invoice, receiptsCount, trip, wordingProfile))
    }

    /**
     * Renders this `TRIP` position's workbook right now, from data already at hand,
     * `SETEX`s it into Valkey base64-encoded (content-addressed key, idempotent re-publish) and returns the `{key, sha256, fileName}` reference travelling on
     * [TripExportDto.workbookRef].
     *
     * `null` when [tripWorkbookExportPort]/[payloadExchange] aren't wired (a plain unit test), the
     * position no longer resolves to a `TRIP` detail ([TripExportNotFoundException] — a race with a
     * concurrent edit/delete, not this method's own bug), or the workbook writer fails at the I/O
     * layer ([java.io.IOException]) — a missing workbook attachment must never block the invoice's
     * export. A genuinely unexpected exception propagates, unlike a blanket `catch (Exception)` would.
     */
    private fun InvoiceItem.buildWorkbookRef(invoice: Invoice, receiptsCount: Int, trip: TripExportDto, wordingProfile: WordingProfile): TripWorkbookRefDto? {
        val item = this
        val port = tripWorkbookExportPort ?: return null
        val exchange = payloadExchange ?: return null
        return try {
            val view = TripWorkbookView(
                positionId = item.positionId,
                title = item.description,
                purpose = item.itemDescription?.trim()?.takeIf { it.isNotBlank() },
                periodFrom = item.servicePeriodFrom,
                periodTo = item.servicePeriodTo,
                invoiceNumber = invoice.invoiceNumber.value,
                invoiceDate = invoice.invoiceDate,
                customerName = invoice.recipient?.name,
                costCenter = item.costCenter,
                taxRatePercent = item.taxRateSnapshot?.value,
                calculatedNet = trip.calculatedNet,
                fixedNetPrice = trip.fixedNetPrice,
                receiptsCount = receiptsCount,
                currency = invoice.currency,
                expenses = trip.expenses,
                perDiemDays = trip.perDiemDays,
            )
            val bytes = ByteArrayOutputStream().use { out ->
                port.writeTripWorkbook(view, documentLanguageTranslator(invoice.documentLanguage, wordingProfile), out)
                out.toByteArray()
            }
            val base64 = Base64.getEncoder().encodeToString(bytes)
            val sha256 = PayloadExchange.sha256Hex(base64)
            val key = "lt:tripwb:$sha256"
            exchange.put(key, base64, TRIP_WORKBOOK_TTL_SECONDS)
            TripWorkbookRefDto(key = key, sha256 = sha256, fileName = "${invoice.invoiceNumber.value}-dienstreise-${item.positionId}.xlsx")
        } catch (ex: TripExportNotFoundException) {
            logFailure(invoice.id, item.positionId, ex)
            null
        } catch (ex: java.io.IOException) {
            logFailure(invoice.id, item.positionId, ex)
            null
        }
    }

    private fun logFailure(invoiceId: Long?, positionId: String, ex: Exception) {
        log.warn("Trip workbook rendering failed for invoice $invoiceId, position $positionId: ${ex.message}", ex)
    }

    /**
     * The `i18n.messages` bundle lookup for [documentLanguage] ("de"/"en") with the
     * `{term:…}` placeholders resolved for [wordingProfile], independent of any Vaadin session (this
     * runs at job-publish time, never from a view). A missing key returns the bare key.
     *
     * `getNoFallbackControl` is load-bearing: on a JVM whose *default* locale is German, a plain
     * `ResourceBundle.getBundle("i18n.messages", Locale.ENGLISH)` silently resolves to
     * `messages_de.properties` instead of the English base bundle once no `messages_en.properties`
     * exists.
     */
    private fun documentLanguageTranslator(documentLanguage: String?, wordingProfile: WordingProfile): (String) -> String {
        val locale = if (documentLanguage == "en") Locale.ENGLISH else Locale.GERMAN
        val bundle = ResourceBundle.getBundle(
            "i18n.messages",
            locale,
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
        )
        return { key ->
            val raw = try { bundle.getString(key) } catch (_: MissingResourceException) { key }
            TermSubstitution.apply(raw) { term -> TermResolver.resolve(wordingProfile, locale, term) }
        }
    }
}
