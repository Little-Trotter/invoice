package de.littletrotter.architecture.rules.txspecimen.ports

/** Outbound port to another service: the `Remote` suffix marks it. */
interface PaymentRemote {
    fun charge(): Int
}

/** Same package, no suffix: an in-process collaborator. */
interface PaymentLocal {
    fun charge(): Int
}
