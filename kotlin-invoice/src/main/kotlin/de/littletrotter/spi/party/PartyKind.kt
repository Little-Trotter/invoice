package de.littletrotter.spi.party

/**
 * The kind of business party a [PartyFormExtension] targets in the master-data forms.
 *
 * - `SELLER`: the seller / sender profile (anbieter) — e.g. its electronic address is BT-34.
 * - `BUYER`: the buyer / recipient (käufer) — e.g. its electronic address is BT-49.
 */
enum class PartyKind { SELLER, BUYER }
