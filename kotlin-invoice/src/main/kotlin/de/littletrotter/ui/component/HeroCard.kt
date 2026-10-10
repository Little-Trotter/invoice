package de.littletrotter.ui.component

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span

class HeroCard(name: String = "", subtitle: String = "", avatarText: String = "+") : Div() {
    private val avatar = Span(avatarText).apply { addClassName("sp-hero-avatar") }
    private val nameSpan = Span(name).apply { addClassName("sp-hero-name") }
    private val subSpan = Span(subtitle).apply { addClassName("sp-hero-sub") }

    init {
        addClassName("sp-dialog-hero")
        val textDiv = Div().apply {
            addClassName("sp-hero-text")
            add(nameSpan, subSpan)
        }
        add(avatar, textDiv)
    }

    fun setName(value: String) {
        nameSpan.text = value
    }

    fun setAvatar(text: String) {
        avatar.text = text
    }
}
