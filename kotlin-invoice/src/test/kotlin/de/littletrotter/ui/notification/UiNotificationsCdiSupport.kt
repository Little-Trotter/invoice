package de.littletrotter.ui.notification

import de.littletrotter.application.message.MessageCenterService
import de.littletrotter.shared.time.TimeSource
import jakarta.enterprise.inject.Instance
import jakarta.enterprise.inject.spi.CDI
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Runs [block] as if a CDI container were active: [UiNotifications] is a plain object and looks up
 * both its [MessageCenterService] and its [TimeSource] through `CDI.current()`.
 */
internal fun <T> withMockedCdi(service: MessageCenterService, time: TimeSource, block: () -> T): T {
    val serviceInstance = mock<Instance<MessageCenterService>>()
    whenever(serviceInstance.get()).thenReturn(service)
    val timeInstance = mock<Instance<TimeSource>>()
    whenever(timeInstance.get()).thenReturn(time)
    val cdi = mock<CDI<Any>>()
    whenever(cdi.select(MessageCenterService::class.java)).thenReturn(serviceInstance)
    whenever(cdi.select(TimeSource::class.java)).thenReturn(timeInstance)
    return Mockito.mockStatic(CDI::class.java).use { staticCdi ->
        staticCdi.`when`<CDI<Any>> { CDI.current() }.thenReturn(cdi)
        block()
    }
}
