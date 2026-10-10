package de.littletrotter.ui.i18n

import com.vaadin.flow.component.Component

/**
 * Shared helper interface for view components and component-containing classes
 * to simplify translation key access. Provides a common `tr()` method that
 * delegates to [UiTranslations].
 *
 * Implement this interface and provide `this` (for Components) or an explicit
 * component reference (for non-Component classes) to use the helper.
 */
interface TranslationProvider {
    /**
     * Get the component reference for translation context (typically `this` for Components,
     * or a specific component field for non-Component classes like dialogs).
     */
    fun getComponent(): Component?

    /**
     * Translate a key using the provided component context.
     *
     * @param key the translation key
     * @param params optional format parameters
     * @return the translated string, or a placeholder if the key is not found
     */
    fun tr(key: String, vararg params: Any): String = UiTranslations.tr(getComponent(), key, *params)
}
