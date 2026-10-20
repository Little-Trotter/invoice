package de.littletrotter.ui.view

import de.littletrotter.ui.component.asActionColumn
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.data.binder.Binder
import com.vaadin.flow.data.binder.ValidationException
import de.littletrotter.application.error.ApplicationConflictException
import de.littletrotter.ui.component.ActionBar
import de.littletrotter.ui.component.ActionIcons
import de.littletrotter.ui.component.AppButtons
import de.littletrotter.ui.component.AppDialog
import de.littletrotter.ui.notification.UiNotifications
import de.littletrotter.ui.i18n.UiTranslations
import org.jboss.logging.Logger

private const val MESSAGE_ERROR_PREFIX_KEY = "message.error.prefix"
private const val OPTIMISTIC_LOCK_KEY = "message.error.optimisticLock"

/**
 * Generic CRUD workflow for editor dialogs. [T] is the row a grid shows and an edit reloads, [F]
 * the mutable form model the dialog binds; subclasses map a row onto the form and hand the
 * validated form to their write port.
 */
abstract class AbstractCrudView<T, F> : VerticalLayout() {

    private val log = Logger.getLogger(javaClass)

    protected val editorDialog = AppDialog("", AppDialog.Size.M)
    protected val saveButton = AppButtons.primary("") { saveCurrentForm() }
    protected val deleteButton = AppButtons.danger("") { deleteCurrent() }
    protected val cancelButton = AppButtons.cancel { editorDialog.close() }

    protected abstract var currentFormData: F

    protected abstract fun idOf(formData: F): Long?
    protected abstract fun formDataFrom(entity: T?): F
    protected abstract fun createWritableForm(id: Long?): F
    protected abstract fun readFormData(formData: F)
    /**
     * Validates [targetFormData] against the on-screen form and returns whether it may be saved.
     *
     * Contract: on `false`, the implementation itself must tell the user why — [saveCurrentForm]
     * only does `if (!writeValidatedForm(formData)) return`, a bare return with no notification of
     * its own. Every implementation but one satisfies this via [writeValidated] (the `Binder`
     * helper right below, which shows `tr("validation.correctMarkedFields")` on a
     * `ValidationException`); one that hand-validates without a `Binder` must call
     * [de.littletrotter.ui.notification.UiNotifications.error] itself on failure. Skipping this
     * makes a validation failure indistinguishable from a dead save button — no message, no field
     * visibly wrong (if the offending field sits on a tab/step not currently shown), dialog just
     * stays open.
     */
    protected abstract fun writeValidatedForm(targetFormData: F): Boolean
    protected abstract fun findEntityById(id: Long): T?

    /**
     * Saves the validated [formData]. [current] is the row freshly reloaded for an edit (already
     * checked against the form's revision), `null` for a new row.
     */
    protected abstract fun persistForm(formData: F, current: T?)
    protected abstract fun deleteEntityById(id: Long)
    protected abstract fun refreshGridData()

    /** Revision-aware editors override both hooks; the form token comes from dialog opening. */
    protected open fun expectedRevision(formData: F): Long? = null
    protected open fun currentRevision(entity: T): Long? = null

    /**
     * Message key shown when the pre-save revision check finds a concurrent change. The notification
     * resolver maps a bare optimistic-lock exception to the generic text, so the pre-check throws a
     * conflict carrying the already translated, entity-specific message instead.
     */
    protected open fun optimisticLockMessageKey(): String = OPTIMISTIC_LOCK_KEY

    /** The cause keeps the rejection classified as an optimistic-lock failure, so it is logged at INFO only. */
    private fun staleRevisionConflict(): ApplicationConflictException {
        val message = tr(optimisticLockMessageKey())
        return ApplicationConflictException(message, jakarta.persistence.OptimisticLockException(message))
    }

    protected open fun newHeaderTitle(): String = tr("referenceData.new")
    protected open fun editHeaderTitle(): String = tr("referenceData.edit")
    protected open fun onAfterSave() {
        // No-op hook: subclasses override to react after a successful save.
    }

    protected open fun onAfterDelete() {
        // No-op hook: subclasses override to react after a delete.
    }

    protected fun tr(key: String, vararg params: Any): String = UiTranslations.tr(this, key, *params)

    private fun logRejection(action: String, ex: Exception) = logRejection(log, action, ex)

    /** Writes [formData] from `binder`, shows an error notification on validation failure. Returns true on success. */
    protected fun <F> Binder<F>.writeValidated(formData: F): Boolean {
        return try {
            writeBean(formData)
            true
        } catch (ex: ValidationException) {
            if (!UiNotifications.focusFirstInvalid(ex)) UiNotifications.error(tr("validation.correctMarkedFields"))
            false
        }
    }

    protected fun initCrudDialogButtons() {
        saveButton.text = tr("app.save")
        deleteButton.text = tr("app.delete")
        cancelButton.text = tr("app.cancel")
    }

    /** Picks one of the fixed dialog widths instead of a hand-written width. */
    protected fun useDialogSize(size: AppDialog.Size) {
        editorDialog.width = size.width
    }

    /**
     * Puts the editor actions into the dialog footer: delete (destructive) on the left, then the
     * [extra] actions, cancel, and the primary save last.
     */
    protected fun installCrudFooter(vararg extra: Button) {
        editorDialog.footer.removeAll()
        editorDialog.footer.add(ActionBar(listOf(deleteButton), *extra, cancelButton, saveButton))
    }

    /**
     * Helper to create a Vaadin DataProvider for typical page+filter callbacks.
     * - fetch: (pageIndex, limit, filter) -> List<E>
     * - count: (filter) -> Int
     * - filterSupplier: () -> String? (usually a field value)
     */
    protected fun <E> createGridDataProvider(
        fetch: (pageIndex: Int, limit: Int, filter: String?) -> List<E>,
        count: (filter: String?) -> Long,
        filterSupplier: () -> String?
    ): com.vaadin.flow.data.provider.DataProvider<E, Void> {
        return de.littletrotter.ui.view.createGridDataProvider(fetch, count, filterSupplier)
    }

    /**
     * The shared row interaction pattern, applied once for every reference-data tab instead of
     * per subclass: a row click only selects (Vaadin's native single-selection
     * behavior; no listener needed for that part), and a pencil-icon action column plus a
     * double-click on the row are the sole ways to open the editor dialog. [openWith] defaults to
     * [openEditor] itself; pass a wrapper when a subclass needs to run entity-specific logic before
     * opening (e.g. [de.littletrotter.ui.view.referencedata.TaxRatesTab]'s in-use check).
     */
    protected fun wireEditorRowActions(grid: Grid<T>, openWith: (T) -> Unit = { openEditor(it) }) {
        grid.selectionMode = Grid.SelectionMode.SINGLE
        grid.addComponentColumn { entity ->
            AppButtons.icon(ActionIcons.EDIT, tr("referenceData.edit")) { openWith(entity) }
        }
            .asActionColumn()
        grid.addItemDoubleClickListener { event -> event.item?.let { openWith(it) } }
    }

    protected fun openEditor(entity: T?) {
        try {
            currentFormData = formDataFrom(entity)
            readFormData(currentFormData)
            deleteButton.isEnabled = idOf(currentFormData) != null
            // allow subclasses in different packages to call tr()
            editorDialog.headerTitle = if (entity == null) newHeaderTitle() else editHeaderTitle()
            editorDialog.open()
        } catch (ex: Exception) {
            // Row-click/edit-button listener: must not let an exception escape. Logged with the
            // entity being opened so a failed dialog open is discoverable.
            logRejection("Failed to open editor for entity=$entity", ex)
            if (com.vaadin.flow.component.UI.getCurrent() != null) {
                UiNotifications.error(tr(MESSAGE_ERROR_PREFIX_KEY, UiNotifications.resolveMessage(this, ex)))
            }
        }
    }

    private fun saveCurrentForm() {
        val formData = createWritableForm(idOf(currentFormData))
        if (!writeValidatedForm(formData)) return

        try {
            val current = idOf(formData)?.let { id ->
                findEntityById(id) ?: throw staleRevisionConflict()
            }
            if (current != null && expectedRevision(formData) != currentRevision(current)) {
                throw staleRevisionConflict()
            }
            persistForm(formData, current)
            refreshGridData()
            editorDialog.close()
            if (com.vaadin.flow.component.UI.getCurrent() != null) UiNotifications.saved(this)
            onAfterSave()
        } catch (ex: Exception) {
            // Save-button click listener: must not let an exception escape. Logged with the
            // entity's id so a failed save is discoverable.
            logRejection("Failed to save entity id=${idOf(formData)}", ex)
            if (com.vaadin.flow.component.UI.getCurrent() != null) UiNotifications.error(tr(MESSAGE_ERROR_PREFIX_KEY, UiNotifications.resolveMessage(this, ex)))
        }
    }

    private fun deleteCurrent() {
        idOf(currentFormData)?.let { id ->
            try {
                // WP2 (0.9.10, concurrent-editing) — mirrors saveCurrentForm's own revision check
                // right above: a row a revision-aware subclass (currently Article/Recipient;
                // [expectedRevision]/[currentRevision] both default to `null` for every other
                // subclass, so `null != null` never trips this) already sees change concurrently
                // since the editor opened is rejected here too — a stale delete must not silently
                // discard the other session's edit. The service-level delete call below still
                // carries its own independent version check (defense in depth, same as save's).
                val current = findEntityById(id)
                if (current != null && expectedRevision(currentFormData) != currentRevision(current)) {
                    throw staleRevisionConflict()
                }
                deleteEntityById(id)
                refreshGridData()
                editorDialog.close()
                if (com.vaadin.flow.component.UI.getCurrent() != null) UiNotifications.deleted(this)
                onAfterDelete()
            } catch (ex: Exception) {
                // Delete-button click listener: must not let an exception escape. Logged with the
                // entity id so a failed delete is discoverable.
                logRejection("Failed to delete entity id=$id", ex)
                if (com.vaadin.flow.component.UI.getCurrent() != null) UiNotifications.error(tr(MESSAGE_ERROR_PREFIX_KEY, UiNotifications.resolveMessage(this, ex)))
            }
        }
    }
}
