#!/usr/bin/env python3
"""
Automatisierte Erstellung aller Benutzerhandbuch-Screenshots für
Little Trotter X-Invoice. Voraussetzung: Stack läuft lokal auf
https://localhost (Caddy-Edge, internes TLS).

Ausführung:
    python3 arc42-docs/scripts/capture-user-guide-screenshots.py
    oder: make -C arc42-docs screenshots
"""

import asyncio
import os
import re
import subprocess
import sys
from pathlib import Path

try:
    from playwright.async_api import async_playwright
except ImportError:
    print(
        "Fehler: Playwright ist nicht installiert. Bitte mit "
        "'pip install playwright' installieren."
    )
    sys.exit(1)

BASE_URL = os.environ.get("APP_URL", "https://localhost")
SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
IMAGE_DIR = REPO_ROOT / "arc42-docs" / "doc" / "images" / "user-guide"

VIEWPORT = {"width": 1440, "height": 900}

# Beim Lauf übersprungene Bilder: (Name, Grund)
SKIPPED = []
# Cache: Rechnungsnummer -> Detail-URL (wird beim ersten Öffnen über das Grid ermittelt)
INVOICE_URLS = {}
MAX_CANDIDATES = 25

GRID_ROWS_JS = """() => {
    const grid = document.querySelector('vaadin-grid');
    if (!grid || !grid.shadowRoot) return null;
    const text = (cell) => {
        const slot = cell.querySelector('slot');
        const node = slot ? slot.assignedNodes()[0] : null;
        return node ? (node.innerText || '').trim() : '';
    };
    const headerRows = grid.shadowRoot.querySelectorAll('#header tr');
    const headerRow = headerRows[headerRows.length - 1];
    const headers = headerRow
        ? Array.from(headerRow.children).map(text) : [];
    const rows = Array.from(grid.shadowRoot.querySelectorAll('#items tr'))
        .filter(tr => !tr.hidden)
        .map(tr => Array.from(tr.children).map(text));
    return { headers, rows };
}"""


def skip(name, reason):
    print(f"ÜBERSPRUNGEN: {name} — {reason}")
    SKIPPED.append((name, reason))


async def settle(page, ms=800):
    await page.wait_for_load_state("networkidle")
    await page.wait_for_timeout(ms)


async def close_dialog(page):
    """Schließt einen offenen Dialog (Abbrechen/Schließen, sonst Escape)."""
    btn = page.locator(
        'vaadin-dialog-overlay vaadin-button:has-text("Abbrechen"), '
        'vaadin-dialog-overlay vaadin-button:has-text("Schließen"), '
        'vaadin-confirm-dialog-overlay vaadin-button:has-text("Abbrechen")'
    )
    if await btn.count() > 0:
        await btn.first.click()
    else:
        await page.keyboard.press("Escape")
    await page.wait_for_timeout(400)


async def overview_rows(page):
    """Liest die Zeilen der Rechnungsübersicht als Liste von {Spaltenkopf: Text}."""
    await page.goto(f"{BASE_URL}/", wait_until="networkidle")
    await page.wait_for_timeout(1200)
    data = await page.evaluate(GRID_ROWS_JS)
    if not data or not data["rows"]:
        return []
    headers = data["headers"]
    result = []
    for cells in data["rows"]:
        row = {h: (cells[i] if i < len(cells) else "") for i, h in enumerate(headers)}
        row["_nr"] = cells[0] if cells else ""
        if row["_nr"]:
            result.append(row)
    return result


def pos_count(row):
    digits = re.sub(r"\D", "", row.get("Pos.", ""))
    return int(digits) if digits else 0


async def invoice_url(page, number):
    """Öffnet die Rechnung per Klick auf die Zeile im Grid und merkt sich die URL."""
    if number in INVOICE_URLS:
        return INVOICE_URLS[number]
    await page.goto(f"{BASE_URL}/", wait_until="networkidle")
    await page.wait_for_timeout(1200)
    cell = page.locator("vaadin-grid-cell-content", has_text=number).first
    if await cell.count() == 0:
        return None
    await cell.click()
    try:
        await page.wait_for_url(re.compile(r"/invoice/\d+"), timeout=8000)
    except Exception:
        return None
    await settle(page)
    INVOICE_URLS[number] = page.url
    return page.url


async def open_invoice(page, number):
    url = await invoice_url(page, number)
    if url is None:
        return False
    if page.url != url:
        await page.goto(url, wait_until="networkidle")
        await page.wait_for_timeout(800)
    return True


async def click_tab(page, pattern):
    """Klickt den ersten Reiter, dessen Text dem regulären Ausdruck entspricht."""
    tab = page.locator("vaadin-tab").filter(has_text=re.compile(pattern))
    if await tab.count() == 0:
        return False
    await tab.first.click()
    await page.wait_for_timeout(700)
    return True


async def find_invoice(page, rows, predicate, check):
    """Prüft bis zu MAX_CANDIDATES Zeilen; check(page) liefert True, wenn die
    Rechnung die Vorbedingung erfüllt. Die Seite bleibt auf der Treffer-Rechnung."""
    for row in [r for r in rows if predicate(r)][:MAX_CANDIDATES]:
        if await open_invoice(page, row["_nr"]) and await check(page):
            return row
    return None


async def scan_invoices(page, check, limit=200):
    """Prüft Rechnungen absteigend nach Id (ab der neuesten der Übersicht),
    bis check(page) True liefert. Die Seite bleibt auf der Treffer-Rechnung;
    liefert deren Id oder None."""
    await page.goto(f"{BASE_URL}/", wait_until="networkidle")
    await page.wait_for_timeout(1200)
    await page.locator("vaadin-grid-cell-content").filter(
        has_text=re.compile(r"^[A-Z]{3}\d{3}-\d{4}-\d{2}-\d{5}$")
    ).first.click()
    try:
        await page.wait_for_url(re.compile(r"/invoice/\d+"), timeout=8000)
    except Exception:
        return None
    newest = int(re.search(r"/invoice/(\d+)", page.url).group(1))
    for invoice_id in range(newest, max(newest - limit, 0), -1):
        await page.goto(f"{BASE_URL}/invoice/{invoice_id}", wait_until="networkidle")
        await page.wait_for_timeout(500)
        if await check(page):
            return invoice_id
    return None


async def _capture_message_center(page):
    """Capture message center screenshots (19, 20, 02)."""
    # 19 Message Center mit Fehlern & Warnungen (KoSIT / VIES)
    print("-> 19-meldungen-message-center-fehler.png")
    await page.goto(f"{BASE_URL}/messages", wait_until="networkidle")
    await page.wait_for_timeout(800)
    severity = page.locator(
        'vaadin-multi-select-combo-box[aria-label="Schweregrad"]'
    )
    if await severity.count() > 0:
        await severity.first.click()
        await page.wait_for_timeout(500)
        error_item = page.locator(
            "vaadin-multi-select-combo-box-item", has_text="Fehler"
        )
        await error_item.first.click()
        await page.keyboard.press("Escape")
        await page.wait_for_timeout(1000)
    else:
        skip(
            "19-meldungen-message-center-fehler.png",
            'Filter "Schweregrad" nicht gefunden',
        )
    await page.screenshot(path=IMAGE_DIR / "19-meldungen-message-center-fehler.png")

    # 20 Rechnungsempfänger Übersicht
    print("-> 20-rechnungsempfaenger-uebersicht-funktionen.png")
    await page.goto(f"{BASE_URL}/recipients", wait_until="networkidle")
    await page.wait_for_timeout(800)
    await page.screenshot(
        path=IMAGE_DIR / "20-rechnungsempfaenger-uebersicht-funktionen.png"
    )

    # 02 Empfänger anlegen Dialog
    print("-> 02-empfaenger-anlegen.png")
    new_rec_btn = page.locator(
        'vaadin-button:has-text("Neu"), vaadin-button[aria-label*="Neu"]'
    )
    if await new_rec_btn.count() > 0:
        await new_rec_btn.first.click()
        await page.wait_for_timeout(800)
        await page.screenshot(path=IMAGE_DIR / "02-empfaenger-anlegen.png")
        esc = page.locator(
            'vaadin-dialog-overlay vaadin-button:has-text("Abbrechen")'
        )
        if await esc.count() > 0:
            await esc.click()
            await page.wait_for_timeout(400)


async def _capture_invoice_list_and_export(page):
    """Capture invoice list, export, and basic invoice screenshots (15, 12, 18, 04)."""
    # 15 Sammel-Export Dialog
    print("-> 15-excel-export-alle-rechnungen.png")
    await page.goto(f"{BASE_URL}/", wait_until="networkidle")
    await page.wait_for_timeout(800)
    export_btn = page.locator(
        '#invoice-list-export-button, vaadin-button:has-text("Exportieren")'
    )
    if await export_btn.count() > 0:
        await export_btn.first.click()
        await page.wait_for_timeout(600)
        await page.screenshot(
            path=IMAGE_DIR / "15-excel-export-alle-rechnungen.png"
        )
        esc_exp = page.locator(
            'vaadin-dialog-overlay vaadin-button:has-text("Abbrechen")'
        )
        if await esc_exp.count() > 0:
            await esc_exp.click()

    rows = await overview_rows(page)
    print(f"   {len(rows)} Rechnungen in der Übersicht gefunden")
    if not rows:
        skip("alle Rechnungsdetail-Bilder", "Rechnungsübersicht ist leer")

    drafts = [r for r in rows if r.get("Status", "") == "Entwurf"]
    base_row = (drafts or rows or [None])[0]

    # 12, 18, 04: Basic invoice, positions, add position
    print(
        "-> 12-b2b-rechnung-kopf-erfassen.png, "
        "18-excel-export-einzelrechnung-positionen.png, "
        "04-position-anlegen.png"
    )
    if base_row and await open_invoice(page, base_row["_nr"]):
        await page.screenshot(path=IMAGE_DIR / "12-b2b-rechnung-kopf-erfassen.png")
        if await click_tab(page, r"^\s*Positionen"):
            await page.screenshot(
                path=IMAGE_DIR / "18-excel-export-einzelrechnung-positionen.png"
            )
            add_material = page.locator(
                "vaadin-button", has_text="Material hinzufügen"
            )
            if await add_material.count() == 0:
                async def is_draft_with_add(pg):
                    has_mark = await pg.locator("text=ENTWURF").count() > 0
                    has_word = await pg.locator("text=Entwurf").count() > 0
                    if not has_mark and not has_word:
                        return False
                    if not await click_tab(pg, r"^\s*Positionen"):
                        return False
                    add_button = pg.locator(
                        "vaadin-button", has_text="Material hinzufügen",
                    )
                    return await add_button.count() > 0

                await scan_invoices(page, is_draft_with_add, limit=700)
                add_material = page.locator(
                    "vaadin-button", has_text="Material hinzufügen"
                )

            if await add_material.count() > 0:
                await add_material.first.click()
                await page.wait_for_timeout(1000)
                await page.screenshot(path=IMAGE_DIR / "04-position-anlegen.png")
                await close_dialog(page)
            else:
                skip(
                    "04-position-anlegen.png",
                    'Button "Material hinzufügen" nicht gefunden',
                )
        else:
            skip(
                "18-excel-export-einzelrechnung-positionen.png, "
                "04-position-anlegen.png",
                'Reiter "Positionen" nicht gefunden',
            )
    elif rows:
        skip("12/18/04/17", "Basisrechnung ließ sich nicht öffnen")
    return rows


async def _capture_seller_and_materials(page):
    """Capture seller profile and material master data screenshots (08, 21)."""
    # 08 Ausstellerprofil mit DE und LU USt-IdNr.
    print("-> 08-anbieter-mit-de-und-lu-vat.png")
    await page.goto(f"{BASE_URL}/sender-profiles", wait_until="networkidle")
    await page.wait_for_timeout(800)
    first_seller = page.locator("vaadin-grid-cell-content").filter(
        has_text=re.compile(r"(GmbH|UG|KG|AG|SE)\b")
    ).first
    if await first_seller.count() > 0:
        await first_seller.click()
        await page.wait_for_timeout(1200)
        await click_tab(page, r"Steuer")
        vat = page.get_by_text(re.compile(r"^LU\d{8}$")).first
        if await vat.count() > 0:
            await vat.scroll_into_view_if_needed()
            await page.wait_for_timeout(400)
        await page.screenshot(path=IMAGE_DIR / "08-anbieter-mit-de-und-lu-vat.png")
        esc_seller = page.locator(
            'vaadin-dialog-overlay vaadin-button:has-text("Abbrechen")'
        )
        if await esc_seller.count() > 0:
            await esc_seller.click()

    # 21 Kunden-Artikel-Nr. (BT-156) im Materialstamm
    print("-> 21-material-kunden-artikel-nr.png")
    await page.goto(f"{BASE_URL}/articles", wait_until="networkidle")
    await page.wait_for_timeout(800)
    found_ref = False
    for catalogue_tab in (
        r"^\s*Material",
        r"^\s*Einheitspreis",
        r"^\s*Arbeitsstunden",
    ):
        if found_ref or not await click_tab(page, catalogue_tab):
            continue
        sorter = page.locator(
            "vaadin-grid-sorter", has_text="Positionsnummer"
        ).first
        for _ in range(2):
            if await sorter.get_attribute("direction") == "desc":
                break
            await sorter.click()
            await page.wait_for_timeout(800)
        numbers = page.locator("vaadin-grid-cell-content").filter(
            has_text=re.compile(r"^ART-\d{4}-\d{5}$")
        )
        for idx in range(min(await numbers.count(), MAX_CANDIDATES)):
            await numbers.nth(idx).click()
            await page.wait_for_timeout(1000)
            dialog = page.locator("vaadin-dialog-overlay")
            if await dialog.get_by_text(re.compile(r"KD-")).count() > 0:
                found_ref = True
                break
            await close_dialog(page)
    if not found_ref:
        print(
            "   Hinweis: kein Material mit Kennung gefunden, "
            "zeige den leeren Bereich"
        )
        await click_tab(page, r"^\s*Material")
        first = page.locator("vaadin-grid-cell-content").filter(
            has_text=re.compile(r"^ART-\d{4}-\d{5}$")
        ).first
        if await first.count() > 0:
            await first.click()
            await page.wait_for_timeout(1000)
            found_ref = True
    if found_ref:
        section = page.get_by_text("Empfängerspezifische Kennungen").first
        if await section.count() > 0:
            await section.evaluate("e => e.scrollIntoView({block: 'center'})")
            await page.wait_for_timeout(400)
        await page.screenshot(path=IMAGE_DIR / "21-material-kunden-artikel-nr.png")
        await close_dialog(page)
    else:
        skip("21-material-kunden-artikel-nr.png", "kein Material in der Liste")


async def _capture_reference_and_system(page):
    """Capture reference data and system screenshots (14, 24, 13, 16, 27, 28)."""
    # 14 Wording Option Branche
    print("-> 14-wording-option-branche.png")
    await page.goto(f"{BASE_URL}/system/einstellungen", wait_until="networkidle")
    await page.wait_for_timeout(800)
    branche_elem = page.locator('text=Begriffe nach Branche')
    if await branche_elem.count() > 0:
        await branche_elem.first.scroll_into_view_if_needed()
        await page.wait_for_timeout(400)
    await page.screenshot(path=IMAGE_DIR / "14-wording-option-branche.png")

    # 24 Referenzdaten Gruppen
    print("-> 24-referenzdaten-uebersicht-gruppen.png")
    await page.goto(f"{BASE_URL}/referenzdaten", wait_until="networkidle")
    await page.wait_for_timeout(800)
    await page.screenshot(
        path=IMAGE_DIR / "24-referenzdaten-uebersicht-gruppen.png"
    )

    # 13 Referenzdaten Zahlungsbedingungen editierbar
    print("-> 13-referenzdaten-abaenderbar.png")
    first_term = page.locator("vaadin-grid-cell-content").filter(
        has_text=re.compile(r"Tage")
    ).first
    if await first_term.count() > 0:
        await first_term.dblclick()
        await page.wait_for_timeout(1000)
        if await page.locator("vaadin-dialog-overlay").count() == 0:
            await first_term.click()
            await page.wait_for_timeout(1000)
        await page.screenshot(path=IMAGE_DIR / "13-referenzdaten-abaenderbar.png")
        await close_dialog(page)
    else:
        skip("13-referenzdaten-abaenderbar.png", "keine Zahlungsziele in der Liste")

    # 16 Rechnungsstatistik Bereich
    print("-> 16-rechnungsstatistik-bereich.png")
    await page.goto(f"{BASE_URL}/statistics", wait_until="networkidle")
    await page.wait_for_timeout(800)
    await page.screenshot(path=IMAGE_DIR / "16-rechnungsstatistik-bereich.png")

    # 27 Zentrales System-Audit-Journal
    print("-> 27-system-audit-journal-gesamt.png")
    await page.goto(f"{BASE_URL}/system", wait_until="networkidle")
    await page.wait_for_timeout(800)
    if await click_tab(page, r"^\s*Protokoll"):
        await page.wait_for_timeout(1000)
        await page.screenshot(path=IMAGE_DIR / "27-system-audit-journal-gesamt.png")
    else:
        skip("27-system-audit-journal-gesamt.png",
             'Reiter "Protokoll" unter /system nicht gefunden')

    # 28 B2B-Profilverwaltung
    print("-> 28-b2b-profil-verwaltung-felder.png")
    await page.goto(
        f"{BASE_URL}/referenzdaten/e-rechnung/b2b-profile",
        wait_until="networkidle"
    )
    await page.wait_for_timeout(1000)
    edit = page.locator("vaadin-grid-cell-content vaadin-button")
    if await edit.count() > 0:
        await edit.first.click()
        await page.wait_for_timeout(1200)
        await page.screenshot(
            path=IMAGE_DIR / "28-b2b-profil-verwaltung-felder.png"
        )
        await close_dialog(page)
    else:
        skip(
            "28-b2b-profil-verwaltung-felder.png",
            "keine B2B-Profile in der Liste",
        )


async def _capture_invoice_specific_screenshots(page, rows, by_items):
    """Capture screenshots specific to invoice details (05, 11, 17, 07, 29, 26, RC)."""
    # 07 Internationale Rechnung mit Reverse Charge
    print("-> 07-internationale-rechnung-reverse-charge.png")
    rc_rows = [r for r in rows if r.get("RC", "").strip()]
    if rc_rows and await open_invoice(page, rc_rows[0]["_nr"]):
        await page.screenshot(
            path=IMAGE_DIR / "07-internationale-rechnung-reverse-charge.png"
        )
    else:
        skip(
            "07-internationale-rechnung-reverse-charge.png",
            "keine Rechnung mit Reverse-Charge-Kennzeichen in der Übersicht",
        )

    # 26 Validierungsbericht Dialog
    print("-> 26-validierungsbericht-dialog.png")

    async def has_findings_button(pg):
        if not await click_tab(pg, r"^\s*E-Rechnung\s*$"):
            return False
        return (
            await pg.locator(
                'vaadin-button:has-text("Validierungsbefunde")'
            ).count()
            > 0
        )

    hit = await find_invoice(page, rows, lambda _: True, has_findings_button)
    if hit:
        validate_btn = page.locator(
            'vaadin-button:has-text("Validierungsbefunde")'
        )
        await validate_btn.first.click()
        await page.wait_for_timeout(800)
        await page.screenshot(path=IMAGE_DIR / "26-validierungsbericht-dialog.png")
        await close_dialog(page)
    else:
        skip(
            "26-validierungsbericht-dialog.png",
            'keine Rechnung mit Button "Validierungsbefunde" gefunden',
        )

    # 05 Dienstreise mit Einzelposten
    print("-> 05-dienstreise-mit-eintraegen.png")

    async def has_trip(pg):
        if not await click_tab(pg, r"^\s*Positionen"):
            return False
        chip = pg.locator("vaadin-button").filter(
            has_text=re.compile(r"Dienstreise \([1-9]")
        )
        if await chip.count() == 0:
            return False
        await chip.first.click()
        await pg.wait_for_timeout(800)
        return True

    hit = await find_invoice(page, rows, lambda _: True, has_trip)
    if hit:
        await page.screenshot(path=IMAGE_DIR / "05-dienstreise-mit-eintraegen.png")
    else:
        skip(
            "05-dienstreise-mit-eintraegen.png",
            "keine Rechnung mit Dienstreise-Position gefunden",
        )

    # 11 Rechnung mit vielen Positionen ohne Belege
    print("-> 11-rechnung-viele-positionen-ohne-belege.png")

    async def has_no_receipt_badge(pg):
        if not await click_tab(pg, r"^\s*Positionen"):
            return False
        return await pg.locator("text=Kein Beleg").count() > 0

    hit = await find_invoice(
        page, by_items, lambda r: pos_count(r) >= 3, has_no_receipt_badge
    )
    if hit:
        await page.screenshot(
            path=IMAGE_DIR / "11-rechnung-viele-positionen-ohne-belege.png"
        )
    else:
        skip(
            "11-rechnung-viele-positionen-ohne-belege.png",
            'keine Rechnung mit >= 3 Positionen und Badge "Kein Beleg" gefunden',
        )

    # 17 Protokoll & 07 Storno & 29 E-Rechnung Wizard
    await _capture_journal_storno_ezrech(page)


async def _capture_journal_storno_ezrech(page):
    """Capture journal, storno, and e-invoice wizard screenshots (17, 07, 29)."""
    # 17 Protokoll-Reiter einer Rechnung mit Journaleinträgen
    print("-> 17-audit-journal-protokoll.png")

    async def has_journal(pg):
        if not await click_tab(pg, r"^\s*Protokoll"):
            return False
        await pg.wait_for_timeout(600)
        return await pg.get_by_text("Keine Einträge").count() == 0

    if await scan_invoices(page, has_journal):
        await page.screenshot(path=IMAGE_DIR / "17-audit-journal-protokoll.png")
    else:
        skip(
            "17-audit-journal-protokoll.png",
            "keine Rechnung mit Journaleinträgen gefunden",
        )

    # 07 Stornodialog Typ 384
    print("-> 07-storno-dialog-typ-384.png")

    async def has_storno_button(pg):
        return (
            await pg.locator(
                'vaadin-button:has-text("Stornieren")'
            ).count()
            > 0
        )

    hit = await scan_invoices(page, has_storno_button)
    if hit:
        await page.locator('vaadin-button:has-text("Stornieren")').first.click()
        await page.wait_for_timeout(800)
        await page.screenshot(path=IMAGE_DIR / "07-storno-dialog-typ-384.png")
        await close_dialog(page)
    else:
        skip(
            "07-storno-dialog-typ-384.png",
            'keine versandte Rechnung mit Button "Stornieren" gefunden',
        )

    # 29 Geführter E-Rechnungs-Assistent
    print("-> 29-e-rechnung-erfassung-wizard.png")

    async def has_e_invoice_edit_tab(pg):
        if (
            await pg.locator("text=ENTWURF").count() == 0
            and await pg.locator("text=Entwurf").count() == 0
        ):
            return False
        edit = pg.locator("vaadin-button", has_text="Bearbeiten")
        if await edit.count() == 0:
            return False
        await edit.first.click()
        await pg.wait_for_timeout(1200)
        return await click_tab(pg, r"^\s*E-Rechnung")

    hit = await scan_invoices(page, has_e_invoice_edit_tab, limit=700)
    if hit:
        await page.screenshot(path=IMAGE_DIR / "29-e-rechnung-erfassung-wizard.png")
    else:
        skip(
            "29-e-rechnung-erfassung-wizard.png",
            'kein Entwurf mit Reiter "E-Rechnung"'
            " im Bearbeitungsdialog gefunden",
        )


async def _capture_remaining_screenshots(page):
    """Capture receipt pool, e-invoice jobs, and PDF sample (09, 10, 06)."""
    # 09 Belege-Pool
    print("-> 09-belege-pool.png")
    await page.goto(f"{BASE_URL}/receipt-pool", wait_until="networkidle")
    await page.wait_for_timeout(800)
    await page.screenshot(path=IMAGE_DIR / "09-belege-pool.png")

    # 10 E-Auftrags-Schlange & PDF-Muster
    print("-> 10-e-auftrags-schlange.png & 06-beispiel-rechnung-pdf.png")
    await page.goto(f"{BASE_URL}/e-invoice-jobs", wait_until="networkidle")
    await page.wait_for_timeout(800)
    await page.screenshot(path=IMAGE_DIR / "10-e-auftrags-schlange.png")

    # Prefer a German invoice with GiroCode (Malerbetrieb Frings MBF011),
    # otherwise fall back to any available PDF in the jobs grid.
    mbf_cell = page.locator("vaadin-grid-cell-content").filter(
        has_text=re.compile(r"MBF011")
    ).first
    pdf_btn = None
    if await mbf_cell.count() > 0:
        target_slot = await mbf_cell.get_attribute("slot")
        btn_slot = await page.evaluate(
            """(slotName) => {
                const grid = document.querySelector("vaadin-grid");
                const rows = Array.from(grid.shadowRoot.querySelectorAll("#items tr"));
                for (const tr of rows) {
                    const slots = Array.from(tr.children).map(td => {
                        const s = td.querySelector("slot");
                        return s ? s.name : null;
                    });
                    if (slots.includes(slotName)) {
                        return slots[slots.length - 1];
                    }
                }
                return null;
            }""",
            target_slot,
        )
        if btn_slot:
            btn = page.locator(f'vaadin-grid-cell-content[slot="{btn_slot}"]').locator(
                "vaadin-button, a"
            ).filter(has_text="PDF").first
            if await btn.count() > 0:
                pdf_btn = btn

    if pdf_btn is None:
        buttons = await page.locator(
            'vaadin-button:has-text("PDF"), a:has-text("PDF")'
        ).all()
        if buttons:
            pdf_btn = buttons[0]

    if pdf_btn is not None:
        async with page.expect_download() as download_info:
            await pdf_btn.click()
        download = await download_info.value
        tmp_pdf = IMAGE_DIR / "temp-sample.pdf"
        await download.save_as(tmp_pdf)
        subprocess.run(
            [
                "pdftoppm",
                "-png",
                "-r",
                "150",
                "-f",
                "1",
                "-l",
                "1",
                str(tmp_pdf),
                str(IMAGE_DIR / "sample-page"),
            ],
            check=True,
        )
        sample_png = IMAGE_DIR / "sample-page-1.png"
        if sample_png.exists():
            sample_png.replace(IMAGE_DIR / "06-beispiel-rechnung-pdf.png")
        tmp_pdf.unlink(missing_ok=True)


async def _capture_mail_screenshots(page):
    """Capture mail templates, compose dialog, template editor, and mail journal (30, 31, 32, 33, 34, 35, 36)."""
    # 30 Cockpit mit Button 'E-Mail senden' (falls vorhanden)
    print("-> 30-rechnung-cockpit-email-senden.png & 36-rechnung-versand-historie.png")
    # Suche Rechnung mit E-Mail senden oder Versand-Historie
    for inv_id in range(1, 100):
        resp = await page.goto(f"{BASE_URL}/invoice/{inv_id}", wait_until="domcontentloaded")
        if resp and resp.status == 200:
            await page.wait_for_timeout(300)
            btn = page.locator('vaadin-button:has-text("E-Mail senden")')
            if await btn.count() > 0 and not (IMAGE_DIR / "30-rechnung-cockpit-email-senden.png").exists():
                await page.screenshot(path=IMAGE_DIR / "30-rechnung-cockpit-email-senden.png")
            hist = page.locator(".mail-history")
            if await hist.count() > 0 and not (IMAGE_DIR / "36-rechnung-versand-historie.png").exists():
                await page.screenshot(path=IMAGE_DIR / "36-rechnung-versand-historie.png")
            if (IMAGE_DIR / "30-rechnung-cockpit-email-senden.png").exists() and (IMAGE_DIR / "36-rechnung-versand-historie.png").exists():
                break

    # 32 Vorlagen-Übersicht
    print("-> 32-email-vorlagen-uebersicht.png")
    await page.goto(f"{BASE_URL}/mail/templates", wait_until="networkidle")
    await page.wait_for_timeout(800)
    await page.screenshot(path=IMAGE_DIR / "32-email-vorlagen-uebersicht.png")

    # 33 Vorlagen-Editor
    print("-> 33-email-vorlage-editor.png")
    edit_btn = page.locator('vaadin-button:has-text("Bearbeiten")')
    if await edit_btn.count() > 0:
        await edit_btn.first.click()
        await page.wait_for_timeout(800)
        await page.screenshot(path=IMAGE_DIR / "33-email-vorlage-editor.png")
        await page.keyboard.press("Escape")
        await page.wait_for_timeout(400)

    # 34 Vorlagen-Historie
    print("-> 34-email-vorlage-historie.png")
    hist_btn = page.locator('vaadin-button:has-text("Verlauf")')
    if await hist_btn.count() > 0:
        await hist_btn.first.click()
        await page.wait_for_timeout(800)
        await page.screenshot(path=IMAGE_DIR / "34-email-vorlage-historie.png")
        await page.keyboard.press("Escape")
        await page.wait_for_timeout(400)

    # 35 Versandverlauf (Mail-Journal)
    print("-> 35-email-versandverlauf.png")
    await page.goto(f"{BASE_URL}/mail/journal", wait_until="networkidle")
    await page.wait_for_timeout(800)
    await page.screenshot(path=IMAGE_DIR / "35-email-versandverlauf.png")


async def capture_all():
    IMAGE_DIR.mkdir(parents=True, exist_ok=True)
    print(
        f"Starte automatisierte Screenshot-Erfassung von {BASE_URL} "
        f"nach {IMAGE_DIR} ..."
    )

    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)
        page = await browser.new_page(viewport=VIEWPORT, ignore_https_errors=True)
        await page.route("**/VAADIN/push**", lambda route: route.abort())

        await _capture_message_center(page)
        await _capture_seller_and_materials(page)
        rows = await _capture_invoice_list_and_export(page)
        by_items = sorted(rows, key=pos_count, reverse=True)
        await _capture_invoice_specific_screenshots(page, rows, by_items)
        await _capture_remaining_screenshots(page)
        await _capture_reference_and_system(page)
        await _capture_mail_screenshots(page)
        await browser.close()

    if SKIPPED:
        print("\nÜbersprungene Bilder:")
        for name, reason in SKIPPED:
            print(f"  - {name}: {reason}")
        sys.exit(1)
    print("Fertig: Sämtliche Screenshots wurden erfolgreich aktualisiert.")


if __name__ == "__main__":
    asyncio.run(capture_all())
