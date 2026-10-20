package de.littletrotter.application.invoice.port.inbound

/** Sort request for the receipt pool: the column's property name and the direction. */
data class ReceiptSort(val property: String, val descending: Boolean)
