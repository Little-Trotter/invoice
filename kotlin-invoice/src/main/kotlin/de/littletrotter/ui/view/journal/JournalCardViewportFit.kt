package de.littletrotter.ui.view.journal

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import java.util.UUID

/**
 * Lets a journal card ("journal-invoice-card--fill") reach down to the bottom edge of the browser
 * window, so the grid scrolls inside the card instead of the page growing. Invoice and offer
 * protocol share it so both tabs behave the same. The owner calls [register] on attach,
 * [refit] whenever the card was rebuilt and [unregister] on detach.
 */
class JournalCardViewportFit(private val owner: Component) {

    private val key = UUID.randomUUID().toString()

    /** How often the script was registered for this instance. */
    var registrations = 0
        private set

    fun register() {
        registrations++
        owner.element.executeJs(FIT_JS_REGISTER, owner.element, key)
    }

    /** The card is a fresh element after a rebuild; the registered script only re-measures. */
    fun refit() {
        if (owner.isAttached) owner.element.executeJs(FIT_JS_INVOKE, key)
    }

    fun unregister(ui: UI) {
        ui.page.executeJs(FIT_JS_UNREGISTER, key)
    }

    private companion object {
    // Measures the card's top edge and gives it the rest of the window height, minus what
    // its ancestors (composed tree, shadow roots included) still add below it in padding, border
    // and margin, so the bottom border lands on the window edge with no page scrollbar. The
    // floor is the card's own CSS min-height. One registry entry per panel (key), replaced
    // rather than stacked; the handler drops itself once its panel is gone from the document.
    private const val FIT_JS_REGISTER = """
        const panel = $0, key = $1;
        const registry = window.__ltJournalFit || (window.__ltJournalFit = new Map());
        if (registry.has(key)) registry.get(key).off();
        let frame = 0;
        const fit = () => {
          frame = 0;
          if (!panel.isConnected) { entry.off(); return; }
          const card = panel.querySelector('.journal-invoice-card');
          if (!card || card.getClientRects().length === 0) return;
          const floor = parseFloat(getComputedStyle(card).minHeight) || 0;
          let below = parseFloat(getComputedStyle(card).marginBottom) || 0;
          let el = card.assignedSlot || card.parentElement || (card.getRootNode() && card.getRootNode().host);
          while (el) {
            const s = getComputedStyle(el);
            below += (parseFloat(s.paddingBottom) || 0) + (parseFloat(s.borderBottomWidth) || 0)
              + (parseFloat(s.marginBottom) || 0);
            el = el.assignedSlot || el.parentElement || (el.getRootNode() && el.getRootNode().host);
          }
          const top = card.getBoundingClientRect().top;
          card.style.setProperty('--journal-card-height',
            Math.max(floor, Math.floor(window.innerHeight - top - below)) + 'px');
        };
        const schedule = () => { if (!frame) frame = requestAnimationFrame(fit); };
        const observer = new IntersectionObserver(schedule);
        observer.observe(panel);
        window.addEventListener('resize', schedule);
        const entry = {
          fit: schedule,
          off: () => {
            window.removeEventListener('resize', schedule);
            observer.disconnect();
            if (frame) cancelAnimationFrame(frame);
            registry.delete(key);
          },
        };
        registry.set(key, entry);
        schedule();
    """
    private const val FIT_JS_INVOKE = "const e = window.__ltJournalFit && window.__ltJournalFit.get($0); if (e) e.fit();"
    private const val FIT_JS_UNREGISTER = "const e = window.__ltJournalFit && window.__ltJournalFit.get($0); if (e) e.off();"
    }
}
