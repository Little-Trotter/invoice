package de.littletrotter.ui.component

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.binder.Binder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PdfTextValidationKaribuTest {

    private val tr: (String) -> String = { "Enthält Zeichen, die im PDF nicht darstellbar sind" }

    @BeforeEach
    fun setup() = MockVaadin.setup()

    @AfterEach
    fun tearDown() = MockVaadin.tearDown()

    private class Form(var text: String = "")

    @Test
    fun `binder rejects an emoji, names it in the field error and accepts it once removed`() {
        val field = TextField()
        val binder = Binder(Form::class.java)
        binder.forField(field).withValidator(PdfTextValidation.validator(tr)).bind({ it.text }, { f, v -> f.text = v })

        field.value = "Danke 💩"
        assertFalse(binder.validate().isOk)
        assertTrue(field.isInvalid)
        assertEquals("Enthält Zeichen, die im PDF nicht darstellbar sind: 💩", field.errorMessage)

        field.value = "Danke – Größe „A“ €"
        assertTrue(binder.validate().isOk)
        assertFalse(field.isInvalid)
    }

    @Test
    fun `hand validation marks the field and later clears only its own mark`() {
        val field = TextField().apply { value = "漢" }

        assertFalse(PdfTextValidation.check(field, tr))
        assertTrue(field.isInvalid)

        field.value = "Kanji"
        assertTrue(PdfTextValidation.check(field, tr))
        assertFalse(field.isInvalid)
    }

    @Test
    fun `hand validation keeps a foreign error mark`() {
        val field = TextField().apply {
            value = "ok"
            isInvalid = true
            errorMessage = "Pflichtfeld"
        }

        assertTrue(PdfTextValidation.check(field, tr))
        assertTrue(field.isInvalid)
    }

    @Test
    fun `checkAll evaluates every field`() {
        val a = TextField().apply { value = "😀" }
        val b = TextField().apply { value = "😁" }

        assertFalse(PdfTextValidation.checkAll(tr, a, b))
        assertTrue(a.isInvalid && b.isInvalid)
        assertNull(PdfTextValidation.message(tr, "plain"))
    }
}
