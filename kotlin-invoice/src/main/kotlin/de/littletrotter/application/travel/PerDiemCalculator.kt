package de.littletrotter.application.travel

import de.littletrotter.application.dto.PerDiemRateDto
import de.littletrotter.application.error.ApplicationValidationException
import de.littletrotter.application.invoice.PerDiemEntryCommand
import de.littletrotter.application.reference.PerDiemDay
import de.littletrotter.application.reference.PerDiemService
import de.littletrotter.domain.reference.PerDiemRate
import de.littletrotter.domain.reference.TravelExpenseEntry
import de.littletrotter.domain.repository.PerDiemRateRepository
import de.littletrotter.shared.Money
import de.littletrotter.shared.PerDiemMealDeductions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import de.littletrotter.application.travel.port.inbound.PerDiemPreviewQueryPort

/**
 * The single place a [PerDiemEntryCommand] (one per-diem day row of a trip) becomes a
 * [TravelExpenseEntry], and the single place a day row's live preview amount is computed. Wraps
 * [PerDiemRate]'s own math ([PerDiemRate.calculateClaimableAmount]) via [PerDiemService] — no
 * second formula: [de.littletrotter.application.invoice.TripService] (persisting a trip),
 * the trip dialog (live preview while editing) and this package's own tests all go through
 * this class, never through [PerDiemRate] directly.
 */
@ApplicationScoped
class PerDiemCalculator @Inject constructor(
    private val perDiemRateRepository: PerDiemRateRepository,
    private val perDiemService: PerDiemService,
) : PerDiemPreviewQueryPort {
    /** Resolves [PerDiemEntryCommand.perDiemRateId] — throws when the id no longer names a rate
     *  (same "the reference must resolve" contract TripService applies to every other command
     *  reference id). */
    fun resolveRate(perDiemRateId: Long): PerDiemRate =
        perDiemRateRepository.findById(perDiemRateId)
            ?: throw ApplicationValidationException("Unknown per-diem rate id $perDiemRateId.")

    /** Builds one [TravelExpenseEntry] from [command], resolving its rate and applying its own
     *  mandatory description — mirrors what [de.littletrotter.application.invoice
     *  .InvoiceLifecycleService]'s old `rebuildPerDiemEntries` did before the TRIP-category merge (deleted there). */
    fun buildEntry(command: PerDiemEntryCommand): TravelExpenseEntry {
        val rate = resolveRate(command.perDiemRateId)
        val entry = perDiemService.createEntry(
            rate,
            PerDiemDay(command.date, command.isArrivalDeparture),
            PerDiemMealDeductions(command.breakfast, command.lunch, command.dinner),
            command.employerContribution,
        )
        entry.relabel(command.participant, command.description)
        return entry
    }

    /** Builds the full replacement list of [TravelExpenseEntry] for a trip's per-diem section —
     *  the caller (a [de.littletrotter.domain.invoice.InvoiceItem]'s `perDiemEntries`, `cascade =
     *  ALL, orphanRemoval = true`) clears its current collection and adds these fresh entries. */
    fun buildEntries(commands: List<PerDiemEntryCommand>): List<TravelExpenseEntry> = commands.map(::buildEntry)

    /** The live claimable-amount preview a day row shows before it is saved — same formula
     *  [buildEntry] persists, just without building an entity. Takes the read row
     *  ([PerDiemRateDto], what the trip dialog's rate combo actually holds), never the entity: no
     *  second formula either way, [PerDiemRateDto.calculateClaimableAmount] and
     *  [PerDiemRate.calculateClaimableAmount] both wrap the same
     *  [de.littletrotter.domain.reference.perDiemClaimableAmount]. */
    override fun previewClaimableAmount(
        rate: PerDiemRateDto,
        isArrivalDeparture: Boolean,
        deductions: PerDiemMealDeductions,
        employerContribution: Money,
    ): Money = rate.calculateClaimableAmount(
        isArrivalDeparture = isArrivalDeparture,
        deductions = deductions,
        employerContribution = employerContribution,
    )
}
