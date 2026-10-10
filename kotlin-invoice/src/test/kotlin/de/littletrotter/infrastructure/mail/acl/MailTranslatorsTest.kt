package de.littletrotter.infrastructure.mail.acl

import de.littletrotter.application.mail.port.inbound.MailJournalArchiveStatus
import de.littletrotter.application.mail.port.inbound.MailJournalCriteria
import de.littletrotter.application.mail.port.inbound.MailJournalHandoverStatus
import de.littletrotter.application.mail.port.inbound.MailJournalSortColumn
import de.littletrotter.application.mail.port.inbound.MailJournalStatus
import de.littletrotter.application.mail.port.inbound.MailSortOrder
import de.littletrotter.application.mail.port.inbound.MailTemplateKind
import de.littletrotter.application.mail.port.inbound.MailTemplateLanguage
import de.littletrotter.shared.mail.MailArchiveHandoverStatus
import de.littletrotter.shared.mail.MailArchiveStatus
import de.littletrotter.shared.mail.MailDocumentKind
import de.littletrotter.shared.mail.MailJournalEntryView
import de.littletrotter.shared.mail.MailLanguage
import de.littletrotter.shared.mail.MailSendStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.Instant

class MailTranslatorsTest {

    @ParameterizedTest
    @EnumSource(MailJournalStatus::class)
    fun `every status maps to the contract status of the same name and back`(status: MailJournalStatus) {
        assertEquals(status.name, MailJournalTranslator.statusFor(status).name)
        assertEquals(status, MailJournalTranslator.statusFrom(MailSendStatus.valueOf(status.name)))
    }

    @ParameterizedTest
    @EnumSource(MailJournalHandoverStatus::class)
    fun `every handover status and its details are carried over`(status: MailJournalHandoverStatus) {
        val entry = MailJournalTranslator.toEntry(
            view(MailArchiveStatus.PENDING).copy(
                archiveHandoverStatus = MailArchiveHandoverStatus.valueOf(status.name),
                archiveHandoverAt = Instant.EPOCH,
                archiveHandoverError = "e",
            ),
        )
        assertEquals(status, entry.archiveHandoverStatus)
        assertEquals(Instant.EPOCH, entry.archiveHandoverAt)
        assertEquals("e", entry.archiveHandoverError)
    }

    @ParameterizedTest
    @EnumSource(MailJournalArchiveStatus::class)
    fun `every archive status survives the round trip`(status: MailJournalArchiveStatus) {
        val query = MailJournalTranslator.toQuery(MailJournalCriteria(archiveStatus = status))
        assertEquals(status.name, query.archiveStatus?.name)
        assertEquals(status, MailJournalTranslator.toEntry(view(MailArchiveStatus.valueOf(status.name))).archiveStatus)
    }

    @ParameterizedTest
    @EnumSource(MailJournalSortColumn::class)
    fun `every sort column maps by name`(column: MailJournalSortColumn) {
        assertEquals(column.name, MailJournalTranslator.toQuery(MailJournalCriteria(sort = column)).sort.name)
    }

    @ParameterizedTest
    @EnumSource(MailSortOrder::class)
    fun `every direction maps by name`(order: MailSortOrder) {
        assertEquals(order.name, MailJournalTranslator.toQuery(MailJournalCriteria(direction = order)).direction.name)
    }

    @ParameterizedTest
    @EnumSource(MailTemplateKind::class)
    fun `every template kind round-trips`(kind: MailTemplateKind) {
        assertEquals(kind, MailTemplateTranslator.kindFrom(MailTemplateTranslator.kindFor(kind)))
        assertEquals(MailDocumentKind.valueOf(kind.name), MailTemplateTranslator.kindFor(kind))
    }

    @ParameterizedTest
    @EnumSource(MailTemplateLanguage::class)
    fun `every template language round-trips`(language: MailTemplateLanguage) {
        assertEquals(language, MailTemplateTranslator.languageFrom(MailTemplateTranslator.languageFor(language)))
        assertEquals(MailLanguage.valueOf(language.name), MailTemplateTranslator.languageFor(language))
    }

    @Test
    fun `counts of statuses the component omitted are zero`() {
        val counts = MailJournalTranslator.toCounts(mapOf(MailSendStatus.SENT to 3L))
        assertEquals(3L, counts[MailJournalStatus.SENT])
        assertEquals(0L, counts[MailJournalStatus.FAILED])
        assertEquals(MailJournalStatus.entries.size, counts.size)
    }

    @Test
    fun `parameters leave out unset filters and the window when asked`() {
        val query = MailJournalTranslator.toQuery(MailJournalCriteria())
        assertEquals(emptyList<Pair<String, String>>(), MailJournalTranslator.toParameters(query, withWindow = false))
        assertEquals(
            listOf("sort" to "CREATED_AT", "dir" to "DESC", "page" to "0", "size" to "50"),
            MailJournalTranslator.toParameters(query, withWindow = true),
        )
    }

    @Test
    fun `template path is kind slash language`() {
        assertEquals("QUOTE_DISPATCH/EN", MailTemplateTranslator.pathOf(MailTemplateKind.QUOTE_DISPATCH, MailTemplateLanguage.EN))
    }

    private fun view(archive: MailArchiveStatus) = MailJournalEntryView(
        id = 1, referenceKey = "R", recipient = "r", subject = "s", messageId = null, status = MailSendStatus.SENT,
        smtpResult = null, templateKind = MailDocumentKind.INVOICE_DISPATCH, templateLanguage = MailLanguage.DE,
        templateVersion = 1, bodyEditedByUser = false, retryOfJournalId = null, createdAt = Instant.EPOCH,
        archiveStatus = archive,
    )
}
