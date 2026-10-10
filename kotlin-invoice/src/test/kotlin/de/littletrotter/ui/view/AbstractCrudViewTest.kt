package de.littletrotter.ui.view

import com.github.mvysny.kaributesting.v10.MockVaadin
import com.github.mvysny.kaributesting.v10.clearNotifications
import com.github.mvysny.kaributesting.v10.getNotifications
import de.littletrotter.domain.exception.InvalidParameterException
import de.littletrotter.ui.i18n.UiTranslations
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger as JulLogger

class SimpleEntity(val id: Long? = null, val revision: Long? = null)

// minimal concrete implementation to exercise dialog flow
class TestCrudView : AbstractCrudView<SimpleEntity, SimpleEntity>() {
    override var currentFormData: SimpleEntity = SimpleEntity()
    var saved = false
    var deleted = false
    var applied = false
    var loadedRevision: Long? = null
    var missing = false

    /** Lets a test force `formDataFrom`/`persistForm`/`deleteEntityById` to fail with a
     *  chosen exception, to exercise [AbstractCrudView]'s log-split without needing real callers. */
    var openException: Throwable? = null
    var persistException: Throwable? = null
    var deleteException: Throwable? = null

    init {
        // wire buttons as AbstractCrudView normally expects subclasses to call
        initCrudDialogButtons()
    }

    var validationFails = false

    override fun idOf(formData: SimpleEntity): Long? = formData.id
    override fun formDataFrom(entity: SimpleEntity?): SimpleEntity {
        openException?.let { throw it }
        return entity ?: SimpleEntity()
    }
    override fun createWritableForm(id: Long?): SimpleEntity = SimpleEntity(id, currentFormData.revision)
    override fun readFormData(formData: SimpleEntity) {}
    override fun writeValidatedForm(targetFormData: SimpleEntity): Boolean = !validationFails
    override fun findEntityById(id: Long): SimpleEntity? = if (missing) null else SimpleEntity(id, loadedRevision)
    override fun expectedRevision(formData: SimpleEntity): Long? = formData.revision
    override fun currentRevision(entity: SimpleEntity): Long? = entity.revision
    override fun persistForm(formData: SimpleEntity, current: SimpleEntity?) {
        applied = true
        persistException?.let { throw it }
        saved = true
    }
    override fun deleteEntityById(id: Long) {
        deleteException?.let { throw it }
        deleted = true
    }
    override fun refreshGridData() {}

    // test-friendly public wrappers to exercise protected functionality
    fun openEditorPublic(entity: SimpleEntity?) = openEditor(entity)
    fun clickSave() { saveButton.click() }
    fun clickDelete() { deleteButton.click() }
    fun clickCancel() { cancelButton.click() }
    fun isDialogOpen(): Boolean = editorDialog.isOpened
    fun setCurrentFormDataPublic(entity: SimpleEntity) { currentFormData = entity }

    fun <F> writeValidatedPublic(binder: com.vaadin.flow.data.binder.Binder<F>, formData: F): Boolean =
        binder.writeValidated(formData)

    fun createGridDataProviderPublic(): com.vaadin.flow.data.provider.DataProvider<SimpleEntity, Void> =
        createGridDataProvider(
            fetch = { _, _, _ -> listOf(SimpleEntity(1)) },
            count = { _ -> 1L },
            filterSupplier = { null },
        )
}

/** [AbstractCrudView]'s two `open` hooks ([newHeaderTitle]/[editHeaderTitle]) and the revision
 *  defaults ([expectedRevision]/[currentRevision]) all default to something — [TestCrudView]
 *  above overrides every one of them, so this minimal sibling exists purely to exercise the base
 *  class' own default bodies instead. */
class DefaultsCrudView : AbstractCrudView<SimpleEntity, SimpleEntity>() {
    override var currentFormData: SimpleEntity = SimpleEntity()
    var saved = false

    init { initCrudDialogButtons() }

    override fun idOf(formData: SimpleEntity): Long? = formData.id
    override fun formDataFrom(entity: SimpleEntity?): SimpleEntity = entity ?: SimpleEntity()
    override fun createWritableForm(id: Long?): SimpleEntity = SimpleEntity(id)
    override fun readFormData(formData: SimpleEntity) {}
    override fun writeValidatedForm(targetFormData: SimpleEntity): Boolean = true
    override fun findEntityById(id: Long): SimpleEntity? = SimpleEntity(id)
    override fun persistForm(formData: SimpleEntity, current: SimpleEntity?) { saved = true }
    override fun deleteEntityById(id: Long) {}
    override fun refreshGridData() {}

    fun openEditorPublic(entity: SimpleEntity?) = openEditor(entity)
    fun clickSave() { saveButton.click() }
    fun headerTitle(): String? = editorDialog.headerTitle
}

class AbstractCrudViewTest {
    private lateinit var testView: TestCrudView

    @BeforeEach
    fun setup() {
        MockVaadin.setup()
        testView = TestCrudView()
    }

    @AfterEach
    fun tearDown() {
        MockVaadin.tearDown()
    }

    @Test
    fun `save closes dialog and calls persist`() {
        testView.clickSave()
        assertTrue(testView.saved)
    }

    @Test
    fun `delete calls delete and closes dialog`() {
        // set an existing id and call delete via public setter
        testView.setCurrentFormDataPublic(SimpleEntity(42))
        testView.clickDelete()
        assertTrue(testView.deleted)
    }

    // ── WP2 (0.9.10, concurrent-editing) — deleteCurrent's own stale-revision guard,
    // mirroring saveCurrentForm's own check right above (same expectedRevision/currentRevision
    // hooks TestCrudView already wires for the save-side tests). ──────────────────────────────

    @Test
    fun `WP2 - a stale dialog revision rejects the delete and never calls deleteEntityById`() {
        testView.openEditorPublic(SimpleEntity(42, 7))
        testView.loadedRevision = 8

        testView.clickDelete()

        assertFalse(testView.deleted)
    }

    @Test
    fun `WP2 - a matching dialog revision still deletes normally`() {
        testView.openEditorPublic(SimpleEntity(42, 7))
        testView.loadedRevision = 7

        testView.clickDelete()

        assertTrue(testView.deleted)
    }

    @Test
    fun `WP2 - deleting an already-deleted row (findEntityById returns null) is not blocked by the guard`() {
        testView.openEditorPublic(SimpleEntity(42, 7))
        testView.missing = true

        testView.clickDelete()

        assertTrue(testView.deleted, "nothing to compare revisions against, so the underlying deleteEntityById still runs")
    }

    @Test
    fun `stale dialog revision prevents mutation and persistence and keeps dialog open`() {
        testView.openEditorPublic(SimpleEntity(42, 7))
        testView.loadedRevision = 8

        testView.clickSave()

        assertFalse(testView.applied)
        assertFalse(testView.saved)
        assertTrue(testView.isDialogOpen())
    }

    @Test
    fun `matching dialog revision permits mutation and persistence`() {
        testView.openEditorPublic(SimpleEntity(42, 7))
        testView.loadedRevision = 7

        testView.clickSave()

        assertTrue(testView.applied)
        assertTrue(testView.saved)
        assertFalse(testView.isDialogOpen())
    }

    @Test
    fun `deleted record is not recreated by saving its old dialog`() {
        testView.openEditorPublic(SimpleEntity(42, 7))
        testView.missing = true

        testView.clickSave()

        assertFalse(testView.applied)
        assertFalse(testView.saved)
        assertTrue(testView.isDialogOpen())
    }

    // ── a domain rejection (DomainException/OptimisticLockException) logs one INFO line
    // and shows the existing notification; an unexpected exception still logs ERROR with a trace.

    /** Attaches a plain [Handler] to the JUL logger backing [TestCrudView]'s `org.jboss.logging
     *  .Logger` (jboss-logging delegates through to `java.util.logging` under jboss-logmanager,
     *  the backend this module's surefire config installs), matching the capture pattern already
     *  used by `DocumentGeneratedReactionHandlerTest`. */
    private fun captureLogRecords(block: () -> Unit): List<LogRecord> {
        val julLogger = JulLogger.getLogger(TestCrudView::class.java.name)
        val records = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records.add(record) }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val previousLevel = julLogger.level
        julLogger.level = Level.ALL
        julLogger.addHandler(handler)
        try {
            block()
        } finally {
            julLogger.removeHandler(handler)
            julLogger.level = previousLevel
        }
        return records
    }

    @Test
    fun `a domain exception on save shows the notification and logs INFO only, no ERROR record`() {
        testView.openEditorPublic(SimpleEntity())
        testView.persistException = InvalidParameterException("rejected")

        val records = captureLogRecords { testView.clickSave() }

        assertFalse(testView.saved)
        assertTrue(testView.isDialogOpen(), "a failed save must keep the dialog open")
        assertTrue(records.none { it.level == Level.SEVERE }, "expected no ERROR record, got: $records")
        assertTrue(
            records.any { it.level == Level.INFO && it.message.contains("InvalidParameterException") },
            "expected one INFO record naming the exception, got: $records",
        )
    }

    @Test
    fun `a bean-validation ConstraintViolationException on save logs INFO only (flush-time @Size guard)`() {
        testView.persistException = jakarta.validation.ConstraintViolationException(emptySet())

        val records = captureLogRecords { testView.clickSave() }

        assertFalse(testView.saved)
        assertTrue(records.none { it.level == Level.SEVERE }, "expected no ERROR record, got: $records")
        assertTrue(
            records.any { it.level == Level.INFO && it.message.contains("ConstraintViolationException") },
            "expected one INFO record naming the exception, got: $records",
        )
    }

    @Test
    fun `a database constraint violation on save shows the user-facing db text, never the SQL, and logs one WARN with the DB reason`() {
        clearNotifications()
        val sql = "could not execute statement [ERROR: duplicate key value violates unique constraint \"uq_x\"] [update sender_profile set ...]"
        testView.persistException = org.hibernate.exception.ConstraintViolationException(
            sql,
            java.sql.SQLException("duplicate key", "23505"),
            org.hibernate.exception.ConstraintViolationException.ConstraintKind.UNIQUE,
            "uq_x",
        )

        val records = captureLogRecords { testView.clickSave() }

        assertFalse(testView.saved)
        val text = spanTexts(getNotifications().single()).joinToString(" ")
        assertTrue(text.contains(UiTranslations.tr("message.error.db.unique")), text)
        assertFalse(text.contains("could not execute statement"), text)
        // Defined behaviour (the UI shows the mapped unique-constraint text): WARN, not ERROR.
        assertTrue(records.none { it.level == Level.SEVERE }, "expected no ERROR record, got: $records")
        val warn = records.singleOrNull { it.level == Level.WARNING }
        assertNotNull(warn, "the exact database reason must reach the server log: $records")
        assertTrue(warn!!.message.contains("uq_x"), warn.message)
    }

    private fun spanTexts(component: com.vaadin.flow.component.Component): List<String> =
        (if (component is com.vaadin.flow.component.html.Span) listOf(component.text ?: "") else emptyList()) +
            component.children.toList().flatMap { spanTexts(it) }

    @Test
    fun `an unexpected exception on save still logs ERROR with the stack trace`() {
        testView.persistException = IllegalStateException("boom")

        val records = captureLogRecords { testView.clickSave() }

        assertFalse(testView.saved)
        val errorRecord = records.singleOrNull { it.level == Level.SEVERE }
        assertNotNull(errorRecord, "expected exactly one ERROR record, got: $records")
        assertNotNull(errorRecord!!.thrown, "an unexpected exception must still log with its stack trace")
    }

    @Test
    fun `a stale-revision save raises an ApplicationConflictException and logs INFO only`() {
        testView.openEditorPublic(SimpleEntity(42, 7))
        testView.loadedRevision = 8

        val records = captureLogRecords { testView.clickSave() }

        assertFalse(testView.saved)
        assertTrue(records.none { it.level == Level.SEVERE }, "expected no ERROR record, got: $records")
        assertTrue(
            records.any { it.level == Level.INFO && it.message.contains("ApplicationConflictException") },
            "expected one INFO record naming the exception, got: $records",
        )
    }

    @Test
    fun `a domain exception on delete shows the notification and logs INFO only`() {
        testView.setCurrentFormDataPublic(SimpleEntity(42))
        testView.deleteException = InvalidParameterException("in use")

        val records = captureLogRecords { testView.clickDelete() }

        assertFalse(testView.deleted)
        assertTrue(records.none { it.level == Level.SEVERE }, "expected no ERROR record, got: $records")
        assertTrue(records.any { it.level == Level.INFO && it.message.contains("InvalidParameterException") })
    }

    @Test
    fun `an unexpected exception on delete still logs ERROR with the stack trace`() {
        testView.setCurrentFormDataPublic(SimpleEntity(42))
        testView.deleteException = IllegalStateException("boom")

        val records = captureLogRecords { testView.clickDelete() }

        assertFalse(testView.deleted)
        val errorRecord = records.singleOrNull { it.level == Level.SEVERE }
        assertNotNull(errorRecord, "expected exactly one ERROR record, got: $records")
        assertNotNull(errorRecord!!.thrown)
    }

    @Test
    fun `a domain exception opening the editor logs INFO only`() {
        testView.openException = InvalidParameterException("cannot open")

        val records = captureLogRecords { testView.openEditorPublic(SimpleEntity(42)) }

        assertFalse(testView.isDialogOpen())
        assertTrue(records.none { it.level == Level.SEVERE }, "expected no ERROR record, got: $records")
        assertTrue(records.any { it.level == Level.INFO && it.message.contains("InvalidParameterException") })
    }

    @Test
    fun `an unexpected exception opening the editor still logs ERROR with the stack trace`() {
        testView.openException = IllegalStateException("boom")

        val records = captureLogRecords { testView.openEditorPublic(SimpleEntity(42)) }

        assertFalse(testView.isDialogOpen())
        val errorRecord = records.singleOrNull { it.level == Level.SEVERE }
        assertNotNull(errorRecord, "expected exactly one ERROR record, got: $records")
        assertNotNull(errorRecord!!.thrown)
    }

    // ── The base class' own default bodies, redundant type-checks and never-exercised
    // helpers TestCrudView's overrides/setup never reach ──────────────────────────────────────────

    @Test
    fun `happy path - opening the editor for a new entity uses the default newHeaderTitle`() {
        val view = DefaultsCrudView()

        view.openEditorPublic(null)

        assertEquals(UiTranslations.tr("referenceData.new"), view.headerTitle())
    }

    @Test
    fun `happy path - saving through the default expectedRevision and currentRevision hooks (both null) still persists`() {
        val view = DefaultsCrudView()
        view.openEditorPublic(SimpleEntity(7))

        view.clickSave()

        assertTrue(view.saved)
    }

    @Test
    fun `happy path - clicking cancel closes the editor dialog`() {
        testView.openEditorPublic(SimpleEntity(1))
        assertTrue(testView.isDialogOpen())

        testView.clickCancel()

        assertFalse(testView.isDialogOpen())
    }

    @Test
    fun `happy path - writeValidated writes the bean and returns true on a passing binder`() {
        val binder = com.vaadin.flow.data.binder.Binder(SimpleFormBean::class.java)
        val field = com.vaadin.flow.component.textfield.TextField()
        binder.forField(field).asRequired("required").bind(SimpleFormBean::name.name)
        field.value = "Alice"
        val target = SimpleFormBean()

        val ok = testView.writeValidatedPublic(binder, target)

        assertTrue(ok)
        assertEquals("Alice", target.name)
    }

    @Test
    fun `boundary - writeValidated shows the correction notification and returns false on a failing binder`() {
        val binder = com.vaadin.flow.data.binder.Binder(SimpleFormBean::class.java)
        val field = com.vaadin.flow.component.textfield.TextField()
        binder.forField(field).asRequired("required").bind(SimpleFormBean::name.name)
        field.value = ""
        val target = SimpleFormBean()

        val ok = testView.writeValidatedPublic(binder, target)

        assertFalse(ok)
    }

    @Test
    fun `boundary - createGridDataProvider wires fetch and count through to a usable DataProvider`() {
        val provider = testView.createGridDataProviderPublic()

        assertNotNull(provider)
    }

    @Test
    fun `boundary - a failing writeValidatedForm short-circuits the save, never reaching persistForm`() {
        testView.validationFails = true

        testView.clickSave()

        assertFalse(testView.saved)
        assertFalse(testView.applied)
    }

    @Test
    fun `boundary - deleting with no id set on the current form is a no-op, never calls deleteEntityById`() {
        // testView.currentFormData is the default SimpleEntity() — id == null.
        testView.clickDelete()

        assertFalse(testView.deleted)
    }

    @Test
    fun `boundary - an unexpected exception with no message on open shows a fallback naming the exception class`() {
        testView.openException = IllegalStateException()

        testView.openEditorPublic(SimpleEntity(1))

        assertFalse(testView.isDialogOpen())
    }

    @Test
    fun `boundary - an unexpected exception with no message on save shows a fallback naming the exception class`() {
        testView.persistException = IllegalStateException()

        testView.clickSave()

        assertFalse(testView.saved)
    }

    @Test
    fun `boundary - an unexpected exception on delete shows the generic text, never the exception itself`() {
        clearNotifications()
        testView.setCurrentFormDataPublic(SimpleEntity(42))
        testView.deleteException = IllegalStateException("NullPointerException at Foo.bar")

        testView.clickDelete()

        assertFalse(testView.deleted)
        val text = spanTexts(getNotifications().single()).joinToString(" ")
        assertTrue(text.contains(UiTranslations.tr("message.error.generic")), text)
        assertFalse(text.contains("NullPointerException"), text)
    }
}

/** Plain mutable bean for [AbstractCrudViewTest]'s `writeValidated` coverage — [SimpleEntity] has
 *  no mutable property a [com.vaadin.flow.data.binder.Binder] could write back into. */
class SimpleFormBean {
    var name: String = ""
}
