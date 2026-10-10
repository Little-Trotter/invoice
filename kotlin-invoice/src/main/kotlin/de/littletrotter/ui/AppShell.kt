package de.littletrotter.ui

import com.vaadin.flow.component.dependency.CssImport
import com.vaadin.flow.component.page.AppShellConfigurator
import com.vaadin.flow.component.page.Inline
import com.vaadin.flow.component.page.Push
import com.vaadin.flow.component.page.TargetElement
import com.vaadin.flow.server.AppShellSettings
import com.vaadin.flow.shared.communication.PushMode
import com.vaadin.flow.shared.ui.Transport

// Vaadin 25 removed the application-theme folder mechanism (@Theme) that used to pull in Lumo
// implicitly. Lumo therefore has to be loaded explicitly, and it must come first: the
// littletrotter stylesheet overrides Lumo tokens and component parts at equal specificity, so it
// only wins the cascade when it is injected after Lumo.
//
// This loads Lumo's design tokens and component styling only, not its utility CSS classes
// (e.g. text-l, m-m) — those live in a separate stylesheet Vaadin does not pull in
// automatically. The app styles every view through its own stylesheet, so that utility
// stylesheet is deliberately left out; a view that reaches for one of those utility class names
// today gets a silently inert attribute, not a build error. If utilities are ever genuinely
// needed, add `@StyleSheet(Lumo.UTILITY_STYLESHEET)` (from com.vaadin.flow.theme.lumo.Lumo) here,
// right after the Lumo CssImport below.
@CssImport("@vaadin/vaadin-lumo-styles/lumo.css")
@CssImport("./themes/littletrotter/styles.css")
// Long polling instead of WebSocket: a WebSocket open handshake runs PushHandler.onConnect on the
// Vert.x event loop, where it blocks on the Vaadin session lock (up to the lock timeout) whenever a
// request thread holds it, which drops connections under concurrent load. Long-polling push
// requests are plain servlet requests on worker threads, so waiting for the lock never stalls the
// event loop.
@Push(value = PushMode.AUTOMATIC, transport = Transport.LONG_POLLING)
class AppShell : AppShellConfigurator {

    override fun configurePage(settings: AppShellSettings) {
        // One head signet everywhere: the vector icon serves modern tabs at any size, the PNGs are
        // the fallback for browsers without SVG favicons (the 16 px cut drops the fine mane strokes,
        // which would blur into noise), and the touch icon covers home screens. The tile brings its
        // own navy ground so it reads on light and dark tab bars alike, which no page-side
        // colour-scheme switch could guarantee for bookmarks or touch icons.
        settings.addLink(
            "icons/favicon.svg",
            mapOf("rel" to "icon", "type" to "image/svg+xml", "sizes" to "any"),
        )
        settings.addFavIcon("icon", "icons/favicon-32.png", "32x32")
        settings.addFavIcon("icon", "icons/favicon-16.png", "16x16")
        settings.addLink("apple-touch-icon", "icons/apple-touch-icon-180.png")

        // Sync Lumo's dark attribute with the OS color scheme before first paint,
        // and keep following OS changes at runtime. This is the single source of
        // truth for dark mode: CSS reads it via html[theme~="dark"] (see
        // styles.css), so Vaadin/Lumo components and our own tokens flip together.
        settings.addInlineWithContents(
            TargetElement.HEAD,
            Inline.Position.PREPEND,
            """
            (function () {
              const mq = window.matchMedia('(prefers-color-scheme: dark)');
              const apply = () => document.documentElement.setAttribute('theme', mq.matches ? 'dark' : '');
              apply();
              mq.addEventListener('change', apply);
            })();
            """.trimIndent(),
            Inline.Wrapping.JAVASCRIPT,
        )

        // L15: Vaadin emits a RELATIVE <base href> ("." or "./..", one "../" per path segment of
        // the page that was loaded). A relative base is re-resolved against the current document
        // URL, and every server-side navigation is a history.pushState — so after navigating from
        // "/" to "/invoice/{id}" the base silently becomes "/invoice/" and everything the client
        // resolves against it (the Upload widget's VAADIN/dynamic/resource/... target, hence the
        // receipt upload/assign of ReceiptDialog) lands under /invoice/ instead of the servlet
        // root. Pinning the base to its absolute value at load time, while the document URL is
        // still the one the server computed the base for, makes it immune to later pushState.
        // APPEND: the <base> element must already exist when this runs.
        settings.addInlineWithContents(
            TargetElement.HEAD,
            Inline.Position.APPEND,
            BASE_HREF_PIN_SCRIPT,
            Inline.Wrapping.JAVASCRIPT,
        )
    }

    companion object {
        internal const val BASE_HREF_PIN_SCRIPT =
            "(function(){var b=document.querySelector('base[href]');if(b){b.setAttribute('href',b.href);}})();"
    }
}
