package de.littletrotter.ui.view

import com.github.mvysny.kaributesting.v10.MockVaadin
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [AbstractCrudView]'s three `UI.getCurrent() != null` guards (around
 * `openEditor`/`saveCurrentForm`/`deleteCurrent`'s notification calls): every other test in
 * [AbstractCrudViewTest] runs under `MockVaadin.setup()`, so `UI.getCurrent()` is always non-null
 * there and the "no UI" branch never runs. Deliberately no `MockVaadin.setup()` in this class at
 * all — same idiom as `CommandPaletteDialogNoSessionKaribuTest`. An exception with a `null`
 * message (a bare `RuntimeException()`) also exercises the `ex.message ?: ex.javaClass.simpleName`
 * fallback these three call sites share.
 */
class AbstractCrudViewNoUiTest {

    @AfterEach
    fun teardown() {
        try { MockVaadin.tearDown() } catch (_: Exception) { }
    }

    private class NoUiCrudView : AbstractCrudView<SimpleEntity, SimpleEntity>() {
        override var currentFormData: SimpleEntity = SimpleEntity()
        var openException: Throwable? = null
        var persistException: Throwable? = null
        var deleteException: Throwable? = null
        var saved = false
        var deleted = false

        init { initCrudDialogButtons() }

        override fun idOf(formData: SimpleEntity): Long? = formData.id
        override fun formDataFrom(entity: SimpleEntity?): SimpleEntity {
            openException?.let { throw it }
            return entity ?: SimpleEntity()
        }
        override fun createWritableForm(id: Long?): SimpleEntity = SimpleEntity(id)
        override fun readFormData(formData: SimpleEntity) {}
        override fun writeValidatedForm(targetFormData: SimpleEntity): Boolean = true
        override fun findEntityById(id: Long): SimpleEntity? = SimpleEntity(id)
        override fun persistForm(formData: SimpleEntity, current: SimpleEntity?) { persistException?.let { throw it }; saved = true }
        override fun deleteEntityById(id: Long) { deleteException?.let { throw it }; deleted = true }
        override fun refreshGridData() {}

        fun openEditorPublic(entity: SimpleEntity?) = openEditor(entity)
        fun clickSave() { saveButton.click() }
        fun clickDelete() { deleteButton.click() }
        fun setCurrentFormDataPublic(entity: SimpleEntity) { currentFormData = entity }
        fun isDialogOpen(): Boolean = editorDialog.isOpened
    }

    @Test
    fun `boundary - opening the editor without an active UI logs but shows no notification, and never throws`() {
        val view = NoUiCrudView()
        view.openException = RuntimeException()

        view.openEditorPublic(SimpleEntity(1))

        assertFalse(view.isDialogOpen(), "the failed open must not have left the dialog open")
    }

    @Test
    fun `boundary - saving without an active UI logs but shows no notification, and never throws`() {
        val view = NoUiCrudView()
        view.openEditorPublic(SimpleEntity(1))
        view.persistException = RuntimeException()

        view.clickSave()

        assertTrue(view.isDialogOpen(), "a failed save keeps the dialog open, exactly as with an active UI")
    }

    @Test
    fun `boundary - deleting without an active UI logs but shows no notification, and never throws`() {
        val view = NoUiCrudView()
        view.setCurrentFormDataPublic(SimpleEntity(42))
        view.deleteException = RuntimeException()

        view.clickDelete()
        // Must not throw despite no UI being present — the only observable contract here.
    }

    @Test
    fun `boundary - a successful save without an active UI never calls UiNotifications, and never throws`() {
        val view = NoUiCrudView()
        view.openEditorPublic(SimpleEntity(1))

        view.clickSave()

        assertTrue(view.saved, "the save itself must still succeed even though no UI is present to notify")
    }

    @Test
    fun `boundary - a successful delete without an active UI never calls UiNotifications, and never throws`() {
        val view = NoUiCrudView()
        view.setCurrentFormDataPublic(SimpleEntity(42))

        view.clickDelete()

        assertTrue(view.deleted, "the delete itself must still succeed even though no UI is present to notify")
    }
}
