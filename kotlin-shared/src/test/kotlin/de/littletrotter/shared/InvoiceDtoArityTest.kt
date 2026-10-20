package de.littletrotter.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.reflect.full.primaryConstructor

/**
 * Growth-stop rule: [InvoiceDto] must not gain further top-level fields. Any
 * new business data that arrives together (a new party attribute, a new B2B-style free-value
 * group, …) belongs in a nested sub-DTO — [PartyDto], [ShipToDto], [B2bValueDto] and friends are
 * the established pattern — not as another flat property here. This test freezes the current
 * primary-constructor arity so a future addition fails loudly instead of silently growing the
 * type indefinitely.
 *
 * One deliberate, reviewed exception: [InvoiceDto.draft] is
 * a single top-level `Boolean`, explicitly specified by the PO as a flat field (not nested) since
 * it is the one bit `kotlin-xpdf`'s watermark stamper needs and does not belong to any existing
 * sub-DTO's own topic (dates/references/totals/meta). Arity bumped from 15 to 16 for exactly this
 * field — see [InvoiceDto.draft]'s own KDoc.
 *
 * One more of the same exception, same reasoning: [InvoiceDto.cancellation] is the
 * further bit the watermark stamper needs to pick the cancellation document's own mark, and like
 * [InvoiceDto.draft] it names no topic any existing sub-DTO already owns. Arity bumped from 16 to
 * 17 for exactly this field.
 *
 * [InvoiceDto.cancelled] (the former "cancelled-original" watermark flag,
 * arity briefly 18) is deleted again: a cancelled original is never regenerated (the single-
 * generation gate), so its archived PDF simply keeps whichever mark it already carried and this
 * bit is no longer needed anywhere. Arity back down from 18 to 17.
 *
 * [InvoiceDto.cashDiscount] follows the rule's own prescription rather than breaking it: the
 * early-payment discount (percent, days, deadline, amount, payable amount) arrives as one nested
 * [InvoiceCashDiscountDto], a single field of a sub-DTO type, because the domain computes it once
 * and every consumer (PDF sentence, BT-20 line, structured EXTENDED terms, detail view) must read
 * the same figures instead of recomputing them. It names no topic an existing sub-DTO owns:
 * `dates` holds the payment term's name, not a derived amount, and `totals` are the invoice's own
 * sums, not a conditional deduction. Arity bumped from 17 to 18 for exactly this field.
 *
 * The two template fields (a template pointer and the locally resolved template body) went
 * with the template catalogue: the layout is fixed in code, so the invoice carries no template
 * data. Arity back down from 18 to 16.
 */
class InvoiceDtoArityTest {

    @Test
    fun `InvoiceDto's primary constructor must not grow beyond its frozen arity`() {
        val actual = requireNotNull(InvoiceDto::class.primaryConstructor).parameters.size
        assertEquals(
            16,
            actual,
            "InvoiceDto changed from 16 to $actual top-level constructor parameters. Growth-stop rule " +
                "(PO 2026-08-23): do not add another flat field here. Group new related data into a " +
                "nested sub-DTO instead (see PartyDto, ShipToDto, B2bValueDto) and add a single field " +
                "of that sub-DTO type, or extend an existing sub-DTO's own fields. If this growth is " +
                "genuinely intentional and reviewed, update this expected count deliberately.",
        )
    }
}
