package de.littletrotter.infrastructure.messaging.masterdata

import io.nats.client.JetStreamManagement
import io.nats.client.api.RetentionPolicy
import io.nats.client.api.StreamConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MasterdataStreamSetupTest {

    @Test
    fun `configuration is a limits stream over all master data subjects with a dedup window`() {
        val config = MasterdataStreamSetup.configuration()

        assertEquals("MASTERDATA", config.name)
        assertEquals(listOf("masterdata.>"), config.subjects)
        assertEquals(RetentionPolicy.Limits, config.retentionPolicy)
        assertEquals(MasterdataStreamSetup.DUPLICATE_WINDOW, config.duplicateWindow)
        assertEquals(MasterdataStreamSetup.MAX_AGE, config.maxAge)
        assertEquals(MasterdataStreamSetup.MAX_BYTES, config.maxBytes)
    }

    @Test
    fun `a missing stream is created`() {
        val jsm = mock<JetStreamManagement>()
        whenever(jsm.streamNames).thenReturn(emptyList())

        MasterdataStreamSetup.ensure(jsm)

        verify(jsm).addStream(any<StreamConfiguration>())
        verify(jsm, never()).updateStream(any<StreamConfiguration>())
    }

    @Test
    fun `an existing stream is aligned`() {
        val jsm = mock<JetStreamManagement>()
        whenever(jsm.streamNames).thenReturn(listOf("MASTERDATA"))

        MasterdataStreamSetup.ensure(jsm)

        verify(jsm).updateStream(any<StreamConfiguration>())
        verify(jsm, never()).addStream(any<StreamConfiguration>())
    }
}
