package de.littletrotter.ui.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Properties

class ReceiptsColumnGermanLabelTest {

    private fun load(resourceName: String): Properties {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(resourceName)) {
            "Bundle resource '$resourceName' not found on the classpath"
        }
        return Properties().also { props -> stream.use { props.load(it) } }
    }

    @Test
    fun `items receipts column header is German in the German bundle and English in the default bundle`() {
        assertEquals("Belege", load("i18n/messages_de.properties").getProperty("invoiceDetail.items.column.receipts"))
        assertEquals("Receipts", load("i18n/messages.properties").getProperty("invoiceDetail.items.column.receipts"))
    }
}
