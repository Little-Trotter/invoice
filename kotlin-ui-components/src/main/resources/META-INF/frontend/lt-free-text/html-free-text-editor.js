/**
 * Wraps a locally bundled Tiptap editor as a plain custom element for HtmlFreeTextEditor.kt.
 * Everything about the storage syntax lives on the server (EditorHtml / FreeTextMarkup); this file
 * only ever talks HTML. The ProseMirror schema built from the extensions below is the first
 * allowlist: whatever it cannot represent (links, images, colors, headings, …) is dropped on
 * paste and drop. It is defence in depth, not the only safeguard — the server's
 * FreeTextMarkup.fromHtml() allowlist sanitises again, independently, on every value this element
 * ever reports back.
 *
 * Rendered in the light DOM on purpose: the page's theme tokens and the stylesheet below then
 * apply without any shadow-root plumbing.
 */
import { Editor } from '@tiptap/core';
import { Document } from '@tiptap/extension-document';
import { Paragraph } from '@tiptap/extension-paragraph';
import { Text } from '@tiptap/extension-text';
import { HardBreak } from '@tiptap/extension-hard-break';
import { Bold } from '@tiptap/extension-bold';
import { Italic } from '@tiptap/extension-italic';
import { Underline } from '@tiptap/extension-underline';
import { Strike } from '@tiptap/extension-strike';
import { BulletList, OrderedList, ListItem } from '@tiptap/extension-list';
import { UndoRedo } from '@tiptap/extensions';

const STYLE_ID = 'lt-html-free-text-editor-style';
const CHANGE_DEBOUNCE_MS = 250;

const ICON_ATTRS = 'viewBox="0 0 24 24" width="18" height="18" fill="currentColor" aria-hidden="true"';
const ICONS = {
  bulletList: `<svg ${ICON_ATTRS}><circle cx="5" cy="7" r="1.6"/><circle cx="5" cy="12" r="1.6"/><circle cx="5" cy="17" r="1.6"/><path d="M9 6h11v2H9zM9 11h11v2H9zM9 16h11v2H9z"/></svg>`,
  orderedList: `<svg ${ICON_ATTRS}><path d="M3 5h2v4H4V6H3zM3 11h3v1H4v1h2v1H3v-1.5l1.6-1.5H3zM3 16h3v4H3v-1h2v-.5H4v-1h1V17H3zM9 6h11v2H9zM9 11h11v2H9zM9 16h11v2H9z"/></svg>`,
};
const LETTER_BUTTONS = { bold: 'B', italic: 'I', underline: 'U', strike: 'S' };
const TOGGLES = ['bold', 'italic', 'underline', 'strike', 'bulletList', 'orderedList'];
const TOOLBAR_ORDER = [
  ['bold', 'italic', 'underline', 'strike'],
  ['bulletList', 'orderedList'],
];
const LABEL_PROPERTIES = {
  bold: 'boldLabel',
  italic: 'italicLabel',
  underline: 'underlineLabel',
  strike: 'strikeLabel',
  bulletList: 'bulletLabel',
  orderedList: 'numberedLabel',
};

// One global stylesheet shared by every instance, so the widget reads correctly in both the light
// and dark theme without a theme-specific branch: both themes simply set different values for the
// same Lumo tokens, and this only ever needs writing once per page.
function ensureStyle() {
  if (document.getElementById(STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = `
    lt-html-free-text-editor { display: block; }
    lt-html-free-text-editor .lt-ft-toolbar {
      display: flex;
      flex-wrap: wrap;
      gap: var(--lumo-space-xs, 0.25rem);
      padding: var(--lumo-space-xs, 0.25rem);
      background: var(--lumo-contrast-5pct, #f3f3f3);
      border: 1px solid var(--lumo-contrast-20pct, #ccc);
      border-bottom: 0;
      border-radius: var(--lumo-border-radius-m, 4px) var(--lumo-border-radius-m, 4px) 0 0;
    }
    lt-html-free-text-editor .lt-ft-group {
      display: flex;
      gap: 2px;
      padding-right: var(--lumo-space-xs, 0.25rem);
      margin-right: var(--lumo-space-xs, 0.25rem);
      border-right: 1px solid var(--lumo-contrast-20pct, #ccc);
    }
    lt-html-free-text-editor .lt-ft-group:last-child { border-right: 0; margin-right: 0; padding-right: 0; }
    lt-html-free-text-editor .lt-ft-toolbar button {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-width: var(--lumo-size-s, 1.75rem);
      height: var(--lumo-size-s, 1.75rem);
      padding: 0 var(--lumo-space-xs, 0.25rem);
      border: 0;
      border-radius: var(--lumo-border-radius-s, 3px);
      background: transparent;
      color: var(--lumo-body-text-color, #000);
      font: inherit;
      font-size: var(--lumo-font-size-s, 0.875rem);
      cursor: pointer;
    }
    lt-html-free-text-editor .lt-ft-toolbar button:hover:not(:disabled) { background: var(--lumo-contrast-10pct, #e5e5e5); }
    lt-html-free-text-editor .lt-ft-toolbar button:focus-visible {
      outline: 2px solid var(--lumo-primary-color, #1676f3);
      outline-offset: 1px;
    }
    lt-html-free-text-editor .lt-ft-toolbar button[aria-pressed="true"] {
      background: var(--lumo-primary-color-10pct, rgba(22, 118, 243, 0.1));
      color: var(--lumo-primary-text-color, var(--lumo-primary-color, #1676f3));
    }
    lt-html-free-text-editor .lt-ft-toolbar button:disabled { opacity: 0.4; cursor: default; }
    lt-html-free-text-editor .lt-ft-content {
      box-sizing: border-box;
      min-height: 8em;
      padding: var(--lumo-space-s, 0.5rem) var(--lumo-space-m, 1rem);
      background: var(--lumo-base-color, #fff);
      color: var(--lumo-body-text-color, #000);
      border: 1px solid var(--lumo-contrast-20pct, #ccc);
      border-radius: 0 0 var(--lumo-border-radius-m, 4px) var(--lumo-border-radius-m, 4px);
      font-family: var(--lumo-font-family, inherit);
      font-size: var(--lumo-font-size-m, 1rem);
      line-height: var(--lumo-line-height-m, 1.5);
      white-space: pre-wrap;
      word-wrap: break-word;
      outline: none;
    }
    lt-html-free-text-editor .lt-ft-content:focus {
      border-color: var(--lumo-primary-color, #1676f3);
      box-shadow: 0 0 0 1px var(--lumo-primary-color, #1676f3);
    }
    lt-html-free-text-editor .lt-ft-content p { margin: 0 0 var(--lumo-space-xs, 0.25rem); }
    lt-html-free-text-editor .lt-ft-content ul,
    lt-html-free-text-editor .lt-ft-content ol { margin: 0; padding-left: 1.5em; }
    lt-html-free-text-editor .lt-ft-content ul { list-style: disc; }
    lt-html-free-text-editor .lt-ft-content ol { list-style: decimal; }
    lt-html-free-text-editor .lt-ft-content li > p { margin: 0; }
    /* Label, helper and counter take the look of a Lumo text field's, so the editor sits among
       the form's other fields as one of them. */
    .lt-html-freetext-editor__label {
      display: block;
      color: var(--lumo-secondary-text-color, #555);
      font-size: var(--lumo-font-size-s, 0.875rem);
      font-weight: 500;
      line-height: 1;
      padding: var(--lumo-space-s, 0.5rem) 0 var(--lumo-space-xs, 0.25rem);
    }
    .lt-html-freetext-editor__foot {
      display: flex;
      justify-content: space-between;
      gap: var(--lumo-space-m, 1rem);
      color: var(--lumo-secondary-text-color, #555);
      font-size: var(--lumo-font-size-xs, 0.8125rem);
      padding-top: var(--lumo-space-xs, 0.25rem);
    }
    /* An invalid editor takes Lumo's error colours; Lumo redefines them per theme, so the mark
       reads in light and dark mode alike. */
    .lt-html-freetext-editor[invalid] .lt-ft-toolbar,
    .lt-html-freetext-editor[invalid] .lt-ft-content {
      border-color: var(--lumo-error-color, #d32f2f);
    }
    .lt-html-freetext-editor[invalid] .lt-ft-content {
      background: var(--lumo-error-color-10pct, rgba(211, 47, 47, 0.1));
    }
    .lt-html-freetext-editor__error {
      display: block;
      color: var(--lumo-error-text-color, var(--lumo-error-color, #d32f2f));
      font-size: var(--lumo-font-size-s, 0.875rem);
      line-height: var(--lumo-line-height-xs, 1.25);
      margin-top: 0.25rem;
    }
    .lt-html-freetext-editor__counter { white-space: nowrap; font-variant-numeric: tabular-nums; }
  `;
  document.head.appendChild(style);
}

class LtHtmlFreeTextEditor extends HTMLElement {
  static get observedAttributes() {
    return ['aria-label', 'aria-labelledby', 'aria-invalid'];
  }

  connectedCallback() {
    if (this._editor) return;
    ensureStyle();
    this.textContent = '';
    this._toolbar = this._buildToolbar();
    const host = document.createElement('div');
    this.append(this._toolbar, host);

    this._editor = new Editor({
      element: host,
      content: this._html || '',
      extensions: [
        Document,
        Paragraph,
        Text,
        HardBreak,
        Bold,
        Italic,
        Underline,
        Strike,
        BulletList,
        OrderedList,
        ListItem,
        UndoRedo,
      ],
      editable: !this._readOnly,
      editorProps: {
        attributes: { class: 'lt-ft-content', role: 'textbox', 'aria-multiline': 'true' },
      },
      onUpdate: () => this._scheduleChange(),
      // A click on Save right after typing must not outrun the debounce: blur precedes the click,
      // and the flushed event is queued before the click on the same Flow channel, so the server
      // binder already holds the typed text when the save handler reads it.
      onBlur: () => this._flushChange(),
      onTransaction: () => this._syncToolbar(),
    });
    this._shortcutListener = (event) => this._onShortcutKey(event);
    this.addEventListener('keydown', this._shortcutListener, true);
    this._syncAria();
    this._labelToolbar();
    this._syncToolbar();
  }

  _onShortcutKey(event) {
    // A keyboard save (Ctrl/Cmd+S, Ctrl/Cmd+Enter) fires without any blur, so the debounced change
    // would still be pending. Capture phase: this runs before Vaadin's document-level shortcut
    // listener, and the flushed event is queued ahead of the shortcut event on the same Flow channel.
    if (event.ctrlKey || event.metaKey) this._flushChange();
  }

  disconnectedCallback() {
    // Vaadin re-parents elements; the editor is torn down with the node and rebuilt from the last
    // content when the element is attached again.
    if (!this._editor) return;
    this.removeEventListener('keydown', this._shortcutListener, true);
    this._flushChange();
    this._html = this._editor.getHTML();
    this._editor.destroy();
    this._editor = null;
    this._toolbar = null;
    this.textContent = '';
  }

  attributeChangedCallback() {
    this._syncAria();
  }

  _buildToolbar() {
    const bar = document.createElement('div');
    bar.className = 'lt-ft-toolbar';
    bar.setAttribute('role', 'toolbar');
    TOOLBAR_ORDER.forEach((names) => {
      const group = document.createElement('div');
      group.className = 'lt-ft-group';
      names.forEach((name) => group.append(this._buildButton(name)));
      bar.append(group);
    });
    return bar;
  }

  _buildButton(name) {
    const button = document.createElement('button');
    button.type = 'button';
    button.dataset.cmd = name;
    if (LETTER_BUTTONS[name]) {
      const letter = document.createElement('span');
      letter.textContent = LETTER_BUTTONS[name];
      letter.style.fontWeight = name === 'bold' ? '700' : '500';
      if (name === 'italic') letter.style.fontStyle = 'italic';
      if (name === 'underline') letter.style.textDecoration = 'underline';
      if (name === 'strike') letter.style.textDecoration = 'line-through';
      button.append(letter);
    } else {
      button.innerHTML = ICONS[name];
    }
    if (TOGGLES.includes(name)) button.setAttribute('aria-pressed', 'false');
    // Keep the caret and selection in the content; a click on a button must not steal focus.
    button.addEventListener('mousedown', (event) => event.preventDefault());
    button.addEventListener('click', () => this._runCommand(name));
    return button;
  }

  _runCommand(name) {
    if (!this._editor || !this._editor.isEditable) return;
    const chain = this._editor.chain().focus();
    switch (name) {
      case 'bold':
        chain.toggleBold().run();
        break;
      case 'italic':
        chain.toggleItalic().run();
        break;
      case 'underline':
        chain.toggleUnderline().run();
        break;
      case 'strike':
        chain.toggleStrike().run();
        break;
      case 'bulletList':
        chain.toggleBulletList().run();
        break;
      default:
        chain.toggleOrderedList().run();
    }
  }

  _labelToolbar() {
    Object.entries(LABEL_PROPERTIES).forEach(([name, property]) => {
      const text = this[property];
      const button = this._toolbar && this._toolbar.querySelector(`button[data-cmd="${name}"]`);
      if (!text || !button) return;
      button.setAttribute('aria-label', text);
      button.setAttribute('title', text);
    });
  }

  _syncToolbar() {
    if (!this._toolbar || !this._editor) return;
    const editable = this._editor.isEditable;
    this._toolbar.querySelectorAll('button').forEach((button) => {
      const name = button.dataset.cmd;
      if (TOGGLES.includes(name)) button.setAttribute('aria-pressed', String(this._editor.isActive(name)));
      button.disabled = !editable;
    });
  }

  // The accessible name and the invalid flag belong on the element that carries role=textbox, but
  // the server only knows the host element.
  _syncAria() {
    if (!this._editor) return;
    const dom = this._editor.view.dom;
    ['aria-label', 'aria-labelledby', 'aria-invalid'].forEach((attribute) => {
      if (this.hasAttribute(attribute)) dom.setAttribute(attribute, this.getAttribute(attribute));
      else dom.removeAttribute(attribute);
    });
  }

  _scheduleChange() {
    if (this._changeTimer) clearTimeout(this._changeTimer);
    this._changeTimer = setTimeout(() => this._flushChange(), CHANGE_DEBOUNCE_MS);
  }

  _flushChange() {
    if (!this._changeTimer) return;
    clearTimeout(this._changeTimer);
    this._changeTimer = null;
    if (!this._editor) return;
    this.dispatchEvent(
      new CustomEvent('html-changed', {
        detail: { html: this._editor.getHTML() },
        bubbles: true,
        composed: true,
      }),
    );
  }

  // --- Server-callable API (HtmlFreeTextEditor.kt) ---

  setHtml(html) {
    if (this._changeTimer) {
      clearTimeout(this._changeTimer);
      this._changeTimer = null;
    }
    this._html = html;
    // emitUpdate=false: the server just computed this HTML from the value it already has, so
    // reporting it back as a change would be an echo, not news.
    if (this._editor) this._editor.commands.setContent(html, { emitUpdate: false });
  }

  // Inserted as a plain text node, never parsed: a placeholder like {{name}} must stay literal text.
  insertText(text) {
    if (!this._editor || !text) return;
    this._editor.chain().focus().insertContent({ type: 'text', text }).run();
  }

  focus() {
    if (this._editor) this._editor.commands.focus();
  }

  setReadOnly(readOnly) {
    this._readOnly = readOnly;
    if (!this._editor) return;
    this._editor.setEditable(!readOnly, false);
    this._syncToolbar();
  }
}

customElements.define('lt-html-free-text-editor', LtHtmlFreeTextEditor);
