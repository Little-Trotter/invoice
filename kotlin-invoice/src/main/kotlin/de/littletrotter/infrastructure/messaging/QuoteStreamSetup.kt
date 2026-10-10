package de.littletrotter.infrastructure.messaging

import de.littletrotter.shared.quote.QuoteStreams
import de.littletrotter.shared.quote.QuoteSubjects
import io.nats.client.JetStreamManagement
import io.nats.client.api.DiscardPolicy
import io.nats.client.api.RetentionPolicy
import io.nats.client.api.StorageType
import io.nats.client.api.StreamConfiguration
import java.time.Duration

/**
 * Creates the two streams of the offer/invoice conversation: the acceptances the offer side
 * publishes and the replies this side publishes. Both sides create either stream when it is
 * missing, so neither start order nor a restart of one service leaves the other without a stream.
 * The parameters equal the offer side's own acceptance stream: an existing stream is never
 * altered from here, so the two sides cannot fight over its configuration.
 */
object QuoteStreamSetup {

    val MAX_AGE: Duration = Duration.ofDays(30)
    const val MAX_BYTES: Long = 256L * 1024 * 1024
    val DUPLICATE_WINDOW: Duration = Duration.ofHours(1)

    fun invoiceOrderConfiguration(): StreamConfiguration = configuration(QuoteStreams.INVOICE_ORDER, listOf(QuoteSubjects.INVOICE_ORDER_REQUESTED))

    fun replyConfiguration(): StreamConfiguration = configuration(QuoteStreams.INVOICE_REPLY, QuoteSubjects.INVOICE_REPLY)

    /** Creates whichever of the two streams is missing. */
    fun ensure(jsm: JetStreamManagement) {
        val existing = jsm.streamNames.toSet()
        listOf(invoiceOrderConfiguration(), replyConfiguration())
            .filterNot { it.name in existing }
            .forEach { jsm.addStream(it) }
    }

    private fun configuration(name: String, subjects: List<String>): StreamConfiguration =
        StreamConfiguration.builder()
            .name(name)
            .subjects(subjects)
            .storageType(StorageType.File)
            .retentionPolicy(RetentionPolicy.Limits)
            .maxAge(MAX_AGE)
            .maxBytes(MAX_BYTES)
            .discardPolicy(DiscardPolicy.Old)
            .duplicateWindow(DUPLICATE_WINDOW)
            .build()
}
