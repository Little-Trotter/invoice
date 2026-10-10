package de.littletrotter.infrastructure.quote

import com.fasterxml.jackson.core.type.TypeReference
import de.littletrotter.application.quote.port.inbound.AttachLibraryDocumentCommand
import de.littletrotter.application.quote.port.inbound.ReplaceLibraryVersionCommand
import de.littletrotter.application.quote.port.outbound.AttachLibraryDocumentRemote
import de.littletrotter.infrastructure.quote.acl.QuoteRequestTranslator
import de.littletrotter.shared.quote.QuoteApiPaths
import de.littletrotter.shared.quote.QuoteIdAnswer
import de.littletrotter.shared.quote.QuoteWireCodec
import de.littletrotter.shared.quote.ReplaceLibraryVersionRequest
import io.quarkus.arc.lookup.LookupIfProperty
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.faulttolerance.Timeout
import java.time.temporal.ChronoUnit

/** `POST /quote/api/quotes/{id}/attachments/from-library` and `PUT …/attachments/{aid}/version`. */
@LookupIfProperty(name = "quote.enabled", stringValue = "true")
@ApplicationScoped
class HttpAttachLibraryDocumentAdapter @Inject constructor(
    private val apiClient: QuoteApiClient,
) : AttachLibraryDocumentRemote {

    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun attach(command: AttachLibraryDocumentCommand): String =
        apiClient.post(
            QuoteRoutes.of(QuoteApiPaths.ATTACHMENTS_FROM_LIBRARY, command.quoteId),
            QuoteWireCodec.encode(QuoteRequestTranslator.toAttachFromLibraryRequest(command)),
            "library attachment",
            object : TypeReference<QuoteIdAnswer>() {},
        ).id

    @Timeout(value = 20_000, unit = ChronoUnit.MILLIS)
    override fun replaceVersion(command: ReplaceLibraryVersionCommand) =
        apiClient.put(
            QuoteRoutes.of(QuoteApiPaths.ATTACHMENT_VERSION, command.quoteId, command.attachmentId),
            QuoteWireCodec.encode(ReplaceLibraryVersionRequest(command.versionId)),
        )
}
