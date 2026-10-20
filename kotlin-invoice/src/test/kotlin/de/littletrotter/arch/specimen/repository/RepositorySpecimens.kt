package de.littletrotter.arch.specimen.repository

/** Look-alike of a real aggregate root repository: the rule must stay silent about it. */
interface InvoiceRepository

/** Repository of a child entity of the invoice aggregate (its positions): the rule must report it. */
interface InvoiceItemRepository

/** Not a repository by name: the rule must ignore it. */
interface InvoiceItemLookup
