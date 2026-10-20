package de.littletrotter.ui.component

import com.vaadin.flow.component.icon.VaadinIcon

/**
 * The single source of action icons, so the same action looks the same in the invoice and in the
 * offer; views pick an icon here instead of naming a [VaadinIcon] literally.
 */
object ActionIcons {
    val EDIT = VaadinIcon.PENCIL
    val DELETE = VaadinIcon.TRASH
    val ATTACHMENTS = VaadinIcon.PAPERCLIP
    val ADD = VaadinIcon.PLUS
    val NOTE = VaadinIcon.COMMENT
    val DOWNLOAD = VaadinIcon.DOWNLOAD
    val SPREADSHEET = VaadinIcon.FILE_TABLE
    val RELEASE = VaadinIcon.UNLINK
    val VIEW = VaadinIcon.EYE
    val REFRESH = VaadinIcon.REFRESH
    val ARCHIVE = VaadinIcon.ARCHIVE
    val CONFIRM = VaadinIcon.CHECK
    val CANCEL = VaadinIcon.CLOSE_SMALL
    val BACK = VaadinIcon.ARROW_LEFT
    val COPY = VaadinIcon.COPY
    val HISTORY = VaadinIcon.RECORDS
    val REVERT = VaadinIcon.ARROW_BACKWARD
    val PLACEHOLDERS = VaadinIcon.CURLY_BRACKETS
    val EXPAND = VaadinIcon.ANGLE_DOWN
    val COLLAPSE = VaadinIcon.ANGLE_UP
    val DETAILS = VaadinIcon.EXPAND_FULL
    val MAINTENANCE = VaadinIcon.COGS
    val RECALCULATE = VaadinIcon.CALC_BOOK
    val CLEAR_FILTERS = VaadinIcon.ERASER
    val SEARCH = VaadinIcon.SEARCH
    val NOTIFICATIONS = VaadinIcon.BELL
    val LOGOUT = VaadinIcon.SIGN_OUT
    val VERIFY = VaadinIcon.CHECK_CIRCLE_O
    val MORE = VaadinIcon.ELLIPSIS_DOTS_V
    val E_DOCUMENT_JOB = VaadinIcon.FILE_PROCESS
    val UPLOAD = VaadinIcon.UPLOAD
    val ASSIGN = VaadinIcon.LINK
    val SEND_MAIL = VaadinIcon.ENVELOPE_O
    val TEST_MAIL = VaadinIcon.ENVELOPE_O
    val OVERRIDE = VaadinIcon.WARNING
    val VALIDATION_REPORT = VaadinIcon.CLIPBOARD_CHECK
    val PDF = VaadinIcon.FILE_TEXT
    val INVOICE = VaadinIcon.FILE_TEXT
    val CREATE_INVOICE = VaadinIcon.FILE_ADD
    val XML = VaadinIcon.CODE
}
