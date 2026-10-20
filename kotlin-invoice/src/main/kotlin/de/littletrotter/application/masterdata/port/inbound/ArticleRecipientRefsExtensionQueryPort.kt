package de.littletrotter.application.masterdata.port.inbound

import de.littletrotter.spi.masterdata.ArticleRecipientRefsExtension

/** Input port through which the article form obtains the registered recipient-reference extension. */
fun interface ArticleRecipientRefsExtensionQueryPort {
    /** The registered extension; `null` when none is present. */
    fun enabledExtension(): ArticleRecipientRefsExtension?
}
