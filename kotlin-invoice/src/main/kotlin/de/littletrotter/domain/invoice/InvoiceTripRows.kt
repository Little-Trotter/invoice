package de.littletrotter.domain.invoice

import de.littletrotter.domain.reference.TravelExpenseEntry

/** Adds one per diem day; the owning invoice must still be editable. */
fun InvoiceItem.addPerDiemEntry(entry: TravelExpenseEntry) = perDiemRows.add(entry)

/** Replaces all per diem days; orphan removal deletes the ones that are gone. */
fun InvoiceItem.replacePerDiemEntries(entries: List<TravelExpenseEntry>) = perDiemRows.replaceAll(entries)

/** Adds one expense line; the owning invoice must still be editable. */
fun InvoiceItem.addTripExpense(expense: TripExpense) = tripExpenseRows.add(expense)

/** Replaces all expense lines; orphan removal deletes the ones that are gone. */
fun InvoiceItem.replaceTripExpenses(expenses: List<TripExpense>) = tripExpenseRows.replaceAll(expenses)
