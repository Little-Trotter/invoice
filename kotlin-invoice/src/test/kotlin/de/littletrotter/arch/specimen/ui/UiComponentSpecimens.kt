package de.littletrotter.arch.specimen.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.notification.Notification
import de.littletrotter.ui.component.AppDialog

/**
 * Test-only specimens for [de.littletrotter.arch.UiComponentArchitectureTest]: views that build
 * widgets past the shared components, proving the rules reject them. The last class is the
 * accepted way and must stay unreported.
 */
class ViolatingButtonCreator {
    fun plain(): Button = Button("Save")

    /** A different constructor overload is just as forbidden. */
    fun withListener(): Button = Button("Save") { }
}

class ViolatingDialogCreator {
    fun create(): Dialog = Dialog()
}

class ViolatingDialogSubclass : Dialog()

class ViolatingNotificationCaller {
    fun toast() {
        Notification.show("Saved")
    }
}

class CompliantAppDialogSubclass : AppDialog("Title")
