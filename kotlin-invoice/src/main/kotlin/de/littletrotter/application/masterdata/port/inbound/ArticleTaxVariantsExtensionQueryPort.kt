package de.littletrotter.application.masterdata.port.inbound

import de.littletrotter.spi.masterdata.ArticleTaxVariantsExtension

/** Input port through which the article form obtains the registered tax-variant extension. */
fun interface ArticleTaxVariantsExtensionQueryPort {
    /** The registered extension; `null` when none is present. */
    fun enabledExtension(): ArticleTaxVariantsExtension?
}
