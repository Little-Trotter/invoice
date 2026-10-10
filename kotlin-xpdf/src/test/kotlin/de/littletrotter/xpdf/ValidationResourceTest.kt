package de.littletrotter.xpdf

import de.littletrotter.shared.KositOutcome
import de.littletrotter.shared.KositValidationResult
import de.littletrotter.shared.ValidationMessage
import de.littletrotter.xpdf.impl.EmbeddedKositValidator
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [ValidationResource] is a thin JAX-RS wrapper — no HTTP layer needed to exercise its logic,
 * only the injected [EmbeddedKositValidator] mocked.
 */
class ValidationResourceTest {

    private fun resourceWith(result: KositValidationResult): ValidationResource {
        val validator: EmbeddedKositValidator = mock()
        whenever(validator.validate(any(), any())).thenReturn(result)
        return ValidationResource(validator)
    }

    private fun payloadOf(response: Response): Map<String, Any?> {
        val entity = response.entity
        check(entity is Map<*, *>) { "expected a Map response entity, got ${entity?.javaClass}" }
        return entity.entries.associate { (key, value) -> key.toString() to value }
    }

    @Test
    fun `a VALID result returns 200 with ok true`() {
        val resource = resourceWith(KositValidationResult(status = KositOutcome.VALID, validatorVersion = "1.5.0"))

        val response = resource.validate("<Invoice/>".toByteArray())

        assertEquals(200, response.status)
        val payload = payloadOf(response)
        assertEquals(true, payload["ok"])
        assertEquals("VALID", payload["status"])
        assertEquals("1.5.0", payload["validatorVersion"])
    }

    @Test
    fun `an INVALID result returns 400 with ok false and the finding messages`() {
        val resource = resourceWith(
            KositValidationResult(
                status = KositOutcome.INVALID,
                details = listOf(ValidationMessage(severity = "ERROR", message = "BR-01 violated", stage = "KOSIT")),
            ),
        )

        val response = resource.validate("<Invoice/>".toByteArray())

        assertEquals(400, response.status)
        val payload = payloadOf(response)
        assertEquals(false, payload["ok"])
        assertEquals(listOf("BR-01 violated"), payload["messages"])
    }

    @Test
    fun `a missing validatorVersion is reported as an empty string, never null`() {
        val resource = resourceWith(KositValidationResult(status = KositOutcome.VALID, validatorVersion = null))

        val response = resource.validate("<Invoice/>".toByteArray())

        assertEquals("", payloadOf(response)["validatorVersion"])
    }

    @Test
    fun `an ERROR outcome is reported as a 400 as well, not a false success`() {
        val resource = resourceWith(KositValidationResult(status = KositOutcome.ERROR))

        val response = resource.validate("<Invoice/>".toByteArray())

        assertEquals(400, response.status)
        assertEquals(false, payloadOf(response)["ok"])
    }
}
