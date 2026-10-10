// Standard offer (quotation), A4 — the Typst template for DIN 5008 letter layout, derived from the
// invoice template: same tokens, letterhead, address window, info cards, items table, totals and
// running footer, so an offer and an invoice of one seller read as one family.
//
// Two layers: the DIN 5008 letterhead (teal/orange bar, sender block, address window, the right
// column's info cards) sits at fixed points in millimetres-turned-points — a recipient reads the
// address through a windowed envelope, so that geometry cannot move. The subject line has a fixed
// DIN 5008 position too, but gives way downwards when the info cards grow past it.
// Everything below the subject line flows: each block is exactly as tall as its content. The
// running footer is the third fixed band, drawn at its own absolute position.
//
// An offer has no payment terms: after the totals come the closing text, the free-text note and
// the validity block (validity date, non-binding clause, VAT statement of the prices).
//
// Values arrive preformatted in `data.json` (amounts, dates, quantities are strings the Kotlin
// side formatted): the template never calculates, rounds or parses markup, so the figures on
// paper are the figures the domain computed. Strings are always inserted as plain text.
//
// Compiled with `typst compile --pdf-standard a-3b --ignore-system-fonts --font-path <fonts>`.

#let data = json("data.json")

// ── Palette — one tonal surface instead of a card border, two brand colours each
// pinned to a short, named list of places (§ design system) so a colour never drifts
// onto a third use by accident.
#let ink = rgb("#2C3E50") // body text, values
#let muted = rgb("#7F8C8D") // labels, sublines, footer
#let surface = rgb("#F4F6F8") // the one card/zebra fill; filled cards never carry a stroke
#let border = rgb("#D5D8DC") // hairlines only — a table's bottom edge, the return-line rule
#let teal = rgb("#16A085") // header bar and table head, nowhere else
#let accent = rgb("#E67E22") // header-bar block, gross total, footer line

// ── Typography scale (pt), six steps — every text size in the template names one of
// these; the shrink ladders below (`fit-line`, `fit-sender-name`) are the one exception,
// since they exist to recover from overflow, not to express the scale.
#let t-headline = 16pt // sender name
#let t-title = 14pt // subject line, the totals card's gross row
#let t-body = 9.5pt // totals net/vat rows, note text
#let t-table = 8.5pt // items table body, info-card rows, tax rows, item description
#let t-label = 7.5pt // card/table/note labels, sublines, footer, return line, QR caption

// ── Spacing grid (4 pt base) and the card/table measures built from it ───────────
#let s1 = 4pt
#let s2 = 8pt
#let s3 = 12pt
#let s4 = 16pt
#let s6 = 24pt
#let line-pitch = 12pt // row rhythm inside a card

// Card chrome: 6 pt is the padding a `card` keeps on every side and the gap between the two head
// cards; this single measure covers both for consistency.
#let card-inset = 6pt
#let card-gap = 6pt
// Wrapped values (a long customer number, a 60-char bank name) get a little extra leading so two
// stacked lines don't touch; used everywhere a card value or a note body may run onto a second line.
#let wrap-leading = 2.5pt

// Right head column: x/y are DIN 5008 fixed points (§ below), the width is the card's own.
#let head-col-w = 216pt
#let quote-info-label-w = 86pt
#let sender-info-label-w = 45pt

// Tax/totals cards sit right-aligned under the items table, both this wide.
#let summary-card-w = 270pt
#let tax-net-col-w = 60pt
#let tax-vat-col-w = 50pt
#let totals-label-w = 120pt

// Notes. One title column for both the note and the tax note, so their body text always starts
// at the same x — the wider of the two former widths, kept for the longer tax-note title.
#let note-indent = 11pt
#let note-title-w = 95pt

// Items table column widths — named so no literal point value floats free in the table build below.
#let col-pos-plain = 40pt
#let col-qty-plain = 67pt
#let col-unit-price-plain = 67pt
#let col-net-plain = 67pt
#let col-vat-min-plain = 48pt // "19,00 %" needs this to stay on one line
#let col-pos-detail = 30pt
#let col-period-detail = 50pt
#let col-qty-detail = 38pt
#let col-unit-detail = 55pt
#let col-unit-price-detail = 65pt
#let col-net-detail = 65pt
#let col-vat-min-detail = 40pt

// ── Page geometry (pt) ─────────────────────────────────────────────────────────
// Body area x 57..538, y 51..764; the running footer owns y 764..828 on every page, plus the
// 14 pt bottom margin below it. Nothing in the flow can reach into the footer band.
#let body-width = 481pt
#let footer-top = 764pt
// Height of the text flow on a page: from the 51 pt top margin down to the footer band.
#let flow-height = footer-top - 51pt

// Liberation Sans' hhea metrics: ascent 0.905 em, descent 0.212 em, line gap 0.033 em.
// Typst's named "ascender" edge reads the smaller typographic ascender instead, so the edges
// are given as explicit lengths.
#let ascent = 0.905em
#let descent = 0.212em
#let line-gap = 0.033em

// Line spacing formula: first baseline one ascent below the box top, advance by ascent + descent
// + line gap (1.149 em for Liberation Sans). Achieved in Typst via these line edges plus gap as
// leading.
#set text(
  font: "Liberation Sans",
  fill: ink,
  lang: data.language,
  hyphenate: false,
  // No hanging punctuation; every glyph stays inside its bounds.
  overhang: false,
  top-edge: ascent,
  bottom-edge: -descent,
)
#set par(leading: line-gap, spacing: 0pt, justify: false)
#set block(spacing: 0pt)
#set table.cell(breakable: false)

#set document(title: data.document.subject, author: data.sender.name)

// ── Helpers ────────────────────────────────────────────────────────────────────

#let blank(s) = s == none or s.trim() == ""

// A string with embedded line breaks, as separate printed lines.
#let text-lines(s) = s.split("\n").join(linebreak())

// Absolute placement relative to the current block's origin — used only in the head (DIN 5008
// fixed points) and the running footer.
#let at(x, y, body) = place(top + left, dx: x, dy: y, body)

// A fixed-width box with an alignment inside it, for the head and footer's own fixed columns.
#let wbox(width, al: left, body) = box(width: width, align(al, body))

// Content that is at least `h` tall, growing with the body when it wraps onto further lines.
#let min-height(h, body) = grid(columns: (0pt, 1fr), box(height: h), body)

// Break over-wide words between letters (zero-width spaces) so they don't overflow into the
// neighbouring column. Ordinary words still wrap at spaces; only pathologically wide words get
// character-level breaks.
#let wrap-long-words(s, avail) = context s.split(" ").map(word => {
  if measure(word).width > avail { word.clusters().join("\u{200B}") } else { word }
}).join(" ")

// True once `body`, already sized and styled, measures no wider than `width`. Only meaningful
// inside a `context`, where `measure` has page/font information to work with.
#let fits(width, body) = measure(body).width <= width

// The first candidate for which `ok` holds, or `none` — the shrink-then-shorten ladders below
// are both "try candidates in order, keep the first that fits", so this one loop serves both
// instead of writing it out three times.
#let first-fit(candidates, ok) = {
  let hit = none
  for c in candidates {
    if hit == none and ok(c) { hit = c }
  }
  hit
}

// Shrink `s` + `tail` through a ladder of sizes (largest first) and only truncate — with an
// ellipsis — if even the smallest size in the ladder still overflows `width`. Truncation eats
// into `s` only: `tail` is the part that must survive intact, unless even an ellipsis plus the
// tail alone overflows, in which case the whole line is cut from the end.
#let fit-line(width, sizes, s, tail: "", ..style) = context {
  let last = sizes.last()
  let picked = first-fit(sizes, size => fits(width, text(size: size, ..style, s + tail)))
  let shorten(head, keep) = {
    let clusters = head.clusters()
    let n = first-fit(
      range(clusters.len(), -1, step: -1),
      n => fits(width, text(size: last, ..style, clusters.slice(0, n).join() + "\u{2026}" + keep)),
    )
    if n == none { none } else { clusters.slice(0, n).join().trim() + "\u{2026}" + keep }
  }
  if picked != none {
    text(size: picked, ..style, s + tail)
  } else {
    let kept = if tail != "" { shorten(s, tail) } else { none }
    let line = if kept != none { kept } else { shorten(s + tail, "") }
    text(size: last, ..style, if line != none { line } else { "\u{2026}" })
  }
}

// Sender name: try the letterhead's display sizes as a single line first, so the common short
// name keeps today's look untouched. A name too wide even at the smallest of those wraps across
// the two lines the letterhead has room for, shrinking further until the wrapped block's measured
// height still fits two lines at that size; a name that would still need a third line is clipped.
#let fit-sender-name(name) = context {
  let one-line-sizes = (t-headline, 14pt, 12pt)
  let wrap-sizes = (12pt, 11pt, 10pt, 9pt)
  let one-line = first-fit(one-line-sizes, size => fits(241pt, text(size: size, weight: "bold", name)))
  if one-line != none {
    text(size: one-line, weight: "bold", name)
  } else {
    let wrapped = wrap-long-words(name, 241pt)
    let two-line-h(size) = measure(text(size: size, weight: "bold", "Ag" + linebreak() + "Ag")).height + 1pt
    let picked = first-fit(
      wrap-sizes,
      size => measure(box(width: 241pt, text(size: size, weight: "bold", wrapped))).height <= two-line-h(size),
    )
    let size = if picked != none { picked } else { wrap-sizes.last() }
    let body = box(width: 241pt, text(size: size, weight: "bold", wrapped))
    if picked != none { body } else { box(width: 241pt, height: two-line-h(size), clip: true, body) }
  }
}

// A content-sized card: its height always follows its body, so a wrapped value never overflows
// the frame drawn around it. Exactly two card styles exist: a plain tonal area (`bar: false`) and
// the same area with a 2 pt accent bar down its left edge for the one emphasised card —
// the same motif the free-text block and the letterhead bar already use. Neither ever gets a
// stroke; a filled area carries its own edge, a card border would just double it.
#let card(width, body, fill: surface, bar: false) = if bar {
  block(width: width, grid(
    columns: (2pt, 1fr),
    fill: (x, _) => if x == 0 { accent } else { fill },
    box(height: card-inset),
    block(width: 100%, inset: (x: card-inset, y: card-inset), body),
  ))
} else {
  block(width: width, fill: fill, inset: (x: card-inset, y: card-inset), body)
}

// A label/value grid: label bold and muted, value regular (or bold for row 0 when `first-bold`,
// or per-row via `bold`/`label-fill`/`value-fill`/`size` — the totals' gross row and other emphasised rows need their own colour and weight without a second component). Every row is at
// least `row-h` tall; a value that wraps grows the row instead of running past the card edge.
// `value-w` is the value column's resolved width (the caller already knows it: card width minus
// insets minus `label-w`) — text disables hyphenation everywhere, so a value with no natural break
// point (a customer number, an IBAN-less bank name) needs the same "break long words" fallback the
// items table already applies, or it silently overflows the card instead of wrapping.
// Labels are always left-aligned, whatever alignment the surrounding block was placed with, and
// are bold only where the card asks for it (info cards) or a row asks for it (gross total).
// `label-size` lets a card's labels sit a step below its values on the type scale (the info card:
// `t-label` labels next to `t-table` values) — `none` keeps the one-size behaviour every other
// caller relies on, and a row's own `size` override (the totals card's gross row) still wins for both columns of that one row.
#let kv-rows(
  rows, label-w, value-w, first-bold: false, value-align: right, row-h: line-pitch, size: t-table,
  label-size: none, label-bold: true,
) = grid(
  columns: (label-w, 1fr),
  row-gutter: 0pt,
  ..rows.enumerate().map(((i, row)) => {
    let bold = row.at("bold", default: false) or (first-bold and i == 0)
    let label-fill = row.at("label-fill", default: muted)
    let value-fill = row.at("value-fill", default: ink)
    let row-size = row.at("size", default: size)
    let row-label-size = row.at("size", default: if label-size == none { size } else { label-size })
    let label-weight = if row.at("label-bold", default: label-bold) { "bold" } else { "regular" }
    let value-text = wrap-long-words(row.value, value-w)
    (
      min-height(row-h, align(left, text(size: row-label-size, weight: label-weight, fill: label-fill, row.label))),
      min-height(row-h, {
        set par(leading: wrap-leading)
        align(value-align, text(
          size: row-size, weight: if bold { "bold" } else { "regular" }, fill: value-fill, value-text,
        ))
      }),
    )
  }).flatten(),
)

// ── Free text ──────────────────────────────────────────────────────────────────

#let free-run(run) = {
  let c = text(
    weight: if run.bold { "bold" } else { "regular" },
    style: if run.italic { "italic" } else { "normal" },
    run.text,
  )
  if run.underline { c = underline(c) }
  if run.strike { c = strike(c) }
  c
}

// One printed line: list levels indent by four non-breaking spaces each, then the mark.
#let free-line(line) = {
  let indent = "\u{00A0}" * (4 * line.level)
  let lead = if line.mark == none { indent } else { indent + line.mark + " " }
  [#lead#line.runs.map(free-run).join()]
}

// The orange rule runs along the full height of the block; the text sits 13 pt in and 8 pt down
// and is the only justified text of the offer. Minimum height 28.
#let free-text-block(ft) = grid(
  columns: (2pt, 1fr),
  fill: (x, _) => if x == 0 { accent } else { none },
  box(height: 28pt),
  block(inset: (left: 11pt, top: 8pt), {
    set text(size: t-body)
    set par(justify: true)
    ft.lines.map(free-line).join(linebreak())
  }),
)

#let free-text-inline = data.introText.present and not data.introText.afterItems
#let free-text-after = data.introText.present and data.introText.afterItems

// ── Page 1 head (letterhead, address window, info cards, subject) ──────────────

// The two info cards, content-sized: the offer-facts card (number, dates, references, always
// present) above the sender's tax IDs (only when the profile has any). Anchored at x = 265 pt,
// y = 22 pt; a card that grows only wraps its values, it never shrinks its type.
#let info-card(rows, label-w, first-bold: false) = card(head-col-w, kv-rows(
  rows.map(r => (label: r.label, value: r.value)), label-w, head-col-w - 2 * card-inset - label-w,
  first-bold: first-bold, label-size: t-label,
))

#let head-info-stack = {
  let cards = (info-card(data.quoteInfo, quote-info-label-w, first-bold: true),)
  if data.senderInfo.len() > 0 { cards.push(info-card(data.senderInfo, sender-info-label-w)) }
  stack(dir: ttb, spacing: card-gap, ..cards)
}

// Subject line: DIN 5008 form B sets it two blank lines below the address field (90 mm), which
// is local y = 228 pt — low enough never to show through the envelope window. A fixed position
// low enough for the tallest info-card stack would leave a dead gap above it on every ordinary
// offer, so it sits at 228 pt and moves down only when the cards reach further, always keeping
// `s3` clear of them. The head ends `s4` below the subject; with the 8 pt the flow adds before
// the table that is the 24 pt step that ties the subject to the content it introduces.
#let subject-min-y = 228pt
#let head-stack-y = 22pt

#let head = context {
  let subject = wbox(481pt, text(size: t-title, weight: "bold", data.document.subject))
  let subject-y = calc.max(subject-min-y, head-stack-y + measure(head-info-stack).height + s3)
  block(width: body-width, height: subject-y + measure(subject).height + s4, {
    // Letterhead bar: teal with an orange accent at its left end.
    at(0pt, 0pt, rect(width: 481pt, height: 9pt, fill: teal, stroke: none))
    at(0pt, 0pt, rect(width: 51pt, height: 9pt, fill: accent, stroke: none))

    // Sender name arrives raw; the template fits it to the letterhead width itself (see
    // fit-sender-name above).
    at(0pt, 20pt, fit-sender-name(data.sender.name))

    // Return line above the address window with its thin rule at the box bottom (y = 90). It
    // arrives raw too: the template shrinks it through smaller sizes to fit the 241 pt width and,
    // only if even the smallest size still overflows, shortens it with a trailing ellipsis.
    // The line is "name · street · postal code city"; a line too long even at 6 pt shortens the
  // name, never the address the post office needs to return the letter.
  let return-parts = data.sender.returnLine.split(" · ")
  at(0pt, 77pt, fit-line(
    241pt, (t-label, 7pt, 6.5pt, 6pt), return-parts.first(),
    tail: return-parts.slice(1).map(p => " · " + p).join(default: ""), fill: muted,
  ))
    at(0pt, 90pt, line(length: 241pt, stroke: 0.4pt + border))

    // Recipient: one closed block on a 16 pt pitch (DIN 5008, no blank line before the city);
    // a foreign address adds its country, in capitals, as the last line.
    at(0pt, 100pt, wbox(241pt, text(size: 10pt, weight: "bold", data.recipient.name)))
    at(0pt, 116pt, wbox(241pt, text(size: 10pt, data.recipient.street)))
    at(0pt, 132pt, wbox(241pt, text(size: 10pt, data.recipient.cityLine)))
    if data.recipient.countryLine != "" {
      at(0pt, 148pt, wbox(241pt, text(size: 10pt, data.recipient.countryLine)))
    }

    // Right column: the info cards, content-sized (see head-info-stack above).
    at(265pt, head-stack-y, head-info-stack)

    at(0pt, subject-y, subject)
  })
}

// ── Items table ────────────────────────────────────────────────────────────────

#let items = data.items
#let th = items.headers

// The plain variant's description column is `1fr` — no fixed width to measure the identifier
// line's segments against — so this mirrors the table's own `1fr` arithmetic (the fixed columns'
// widths, the VAT column only when `items.taxColumn` sets it, and the description cell's own
// 4pt+4pt inset) to get that one pixel width up front.
#let desc-avail-plain = (
  body-width - col-pos-plain - col-qty-plain - col-unit-price-plain - col-net-plain
    - (if items.taxColumn { col-vat-min-plain } else { 0pt }) - 8pt
)

// The detail variant's own `1fr` arithmetic: a different set of fixed columns (period, unit, no
// article-number columns since they were dropped in favour of the shared identifier line) leaves
// a different amount of room for the description column, so the identifier line's fitting check
// cannot share `desc-avail-plain` with the plain variant.
#let desc-avail-detail = (
  body-width - col-pos-detail - col-period-detail - col-qty-detail - col-unit-detail
    - col-unit-price-detail - col-net-detail
    - (if items.taxColumn { col-vat-min-detail } else { 0pt }) - 8pt
)

// One `idLines` segment — a bare value (the seller's own article number) or a muted label bound
// to an ink-coloured value with a non-breaking space ("Ref. KD-991", § option B) — boxed so the
// printed line can only break at the " · " separator between segments, never inside one. A
// segment alone wider than the description column would otherwise overflow into the quantity
// column instead of wrapping, so it falls back to `wrap-long-words`, the same break-anywhere
// mechanism the fixed-width columns already rely on for an unbreakable value with no natural
// break point — at the cost of the label/value colour split, which that fallback does not keep.
// `avail` is the calling variant's own description-column width (`desc-avail-plain`/`-detail`).
#let id-segment(seg, avail) = context {
  let joined = if blank(seg.label) { seg.value } else { seg.label + "\u{00A0}" + seg.value }
  let styled = if blank(seg.label) {
    text(fill: ink, seg.value)
  } else {
    text(fill: muted, seg.label) + "\u{00A0}" + text(fill: ink, seg.value)
  }
  if fits(avail, styled) { box(styled) } else { text(wrap-long-words(joined, avail)) }
}

#let qty-plain(row) = {
  if row.subRow {
    let q = text(size: t-table, fill: muted, row.quantity)
    if blank(row.unitLabel) { q } else { text(size: t-label, fill: muted, row.unitLabel) + "\u{00A0}" + q }
  } else {
    let q = text(size: t-table, weight: "bold", row.quantity)
    // The unit goes before the quantity, small and muted.
    if blank(row.unitLabel) { q } else { text(size: t-label, fill: muted, row.unitLabel) + "\u{00A0}" + q }
  }
}

// Both table variants print the same description cell, in the same order: the position name
// (BT-153); the identifier line (the bare seller article number, then "Ref. <buyer number>", §
// option B) — printed here in both variants now that the detail variant no longer carries its own
// article-number columns; the buyer's own italic article designation; the optional BT-154 position
// text, *after* the identifiers (an identifier belongs to the article name, not to free text about
// it) and suppressed when `tripSubline` already carries the row's purpose (a trip expense row,
// where printing it twice would repeat the same sentence) — Kotlin's `buildItemDescription`
// applies the same order and suppression; the trip subline itself is included. `avail` is the
// variant's own description-column width, for the identifier line's fitting check —
// the one thing that differs between `desc-plain` and `desc-detail`, since their fixed columns
// leave a different amount of room for this `1fr` column.
#let desc-body(row, avail) = {
  let parts = (
    if row.subRow { text(size: t-table, fill: muted, row.description) } else { text(size: t-table, row.description) },
  )
  // Two id lines, one printed line: "‹bare seller number› · Ref. ‹buyer number›". Article numbers
  // align across rows without asking for tabular figures: every digit of the bundled Liberation
  // Sans has the same advance width by design (it mirrors Arial's metrics), so its figures are
  // tabular already and a `number-width: "tabular"` request would have nothing to switch on.
  if row.idLines.len() > 0 {
    parts.push(text(size: t-label, fill: muted, row.idLines.map(seg => id-segment(seg, avail)).join(" · ")))
  }
  if not blank(row.buyerArticleDescriptionLine) {
    parts.push(text(size: t-label, fill: muted, style: "italic", row.buyerArticleDescriptionLine))
  }
  if not blank(row.itemDescription) and blank(row.tripSubline) {
    parts.push(text(size: t-table, fill: muted, text-lines(row.itemDescription)))
  }
  if not blank(row.tripSubline) { parts.push(text(size: t-label, fill: muted, row.tripSubline)) }
  parts.join(linebreak())
}

#let desc-plain(row) = desc-body(row, desc-avail-plain)
#let desc-detail(row) = desc-body(row, desc-avail-detail)

// Column: width, header, alignment, left/right indent, extra bottom padding, cell text size,
// bold flag, cell content, an optional `min-w` for an `auto`-width column (the VAT column: the
// header cell reserves this width even though its own text, "USt", is short, so the column never
// shrinks below what "19,00 %" needs and the percentage stays on one line).
#let col(w, head, al, li, ri, pad, size, content, bold: false, sub: false, min-w: none) = (
  w: w, head: head, al: al, li: li, ri: ri, pad: pad, size: size, content: content, bold: bold, sub: sub, min-w: min-w,
)

// Description is `1fr`: it takes whatever the fixed and auto columns leave, so widening the VAT
// column (see `col-vat-min-*`) narrows the description column by exactly that amount instead of
// overflowing the table. Widths still add up to the full 481 pt body width in every variant.
#let plain-columns = {
  let cols = (
    col(col-pos-plain, th.pos, left, 4pt, 4pt, 0pt, t-table, r => r.pos),
    col(1fr, th.description, left, 4pt, 4pt, 3pt, t-table, desc-plain),
    col(col-qty-plain, th.quantity, right, 0pt, 4pt, 0pt, t-table, qty-plain),
    col(col-unit-price-plain, th.unitPrice, right, 0pt, 4pt, 0pt, t-table, r => r.unitPrice),
    col(col-net-plain, th.net, right, 0pt, 4pt, 0pt, t-table, r => r.net, bold: true),
  )
  if items.taxColumn {
    cols.push(col(auto, th.vat, right, 0pt, 4pt, 0pt, t-table, r => r.vatPercent, min-w: col-vat-min-plain))
  }
  cols
}

#let detail-columns = {
  let cols = (
    col(col-pos-detail, th.pos, left, 3pt, 1pt, 3pt, t-table, r => r.pos),
    // Description absorbs the width the two article-number columns used to own — the identifier
    // line under the description (desc-body) now carries that data in both table variants.
    col(1fr, th.description, left, 4pt, 4pt, 3pt, t-table, desc-detail),
    col(col-period-detail, th.period, left, 4pt, 1pt, 3pt, t-label, r => r.servicePeriod),
    col(col-qty-detail, th.quantity, right, 0pt, 3pt, 3pt, t-table, r => r.quantity, sub: true),
    col(col-unit-detail, th.unit, left, 3pt, 1pt, 3pt, t-label, r => r.unitLabel),
    col(col-unit-price-detail, th.unitPrice, right, 0pt, 3pt, 3pt, t-table, r => r.unitPrice),
    col(col-net-detail, th.net, right, 0pt, 3pt, 3pt, t-table, r => r.net, bold: true),
  )
  if items.taxColumn {
    cols.push(col(auto, th.vat, right, 0pt, 3pt, 3pt, t-label, r => r.vatPercent, min-w: col-vat-min-detail))
  }
  cols
}

#let item-table = {
  let cols = if items.detailColumns { detail-columns } else { plain-columns }
  let row-min = if items.detailColumns { 28pt } else { 16pt }
  // A cell's own `width: 100%` would be circular for an `auto` column (the column's natural size
  // is what decides that 100%, not the other way round), so the one `auto` column (VAT) sizes its
  // cell box to its known min-width instead — the same value that already told the table not to
  // shrink the column below it.
  let cell-width(c) = if c.min-w != none { c.min-w } else { 100% }
  let header-cell(c) = block(
    width: cell-width(c), height: 16pt, inset: (left: c.li, right: c.ri),
    // Horizontally as the column aligns, vertically centred in the head bar.
    align(c.al + horizon, text(size: t-label, weight: "bold", fill: white, c.head)),
  )
  let body-cell(c, row) = {
    let v = (c.content)(row)
    let body = if type(v) == str {
      // An `auto` column (only VAT) has no fixed width to measure against yet; its own min-width
      // is the right stand-in, since that is the shortest the column is ever allowed to be.
      let avail = if c.min-w != none { c.min-w } else { c.w - c.li - c.ri }
      v.split("\n").map(l => wrap-long-words(l, avail)).join(linebreak())
    } else { v }
    // Spacing: 2 pt above the text, one line pitch per line, no spacing after the last line
    // (the cell padding handles spacing to the next row).
    text(
      size: c.size,
      fill: if c.sub and row.subRow { muted } else { ink },
      weight: if c.bold { "bold" } else { "regular" },
      block(width: cell-width(c), inset: (left: c.li, right: c.ri, top: 2pt, bottom: line-gap + c.pad), align(c.al, body)),
    )
  }
  table(
    columns: cols.map(c => c.w),
    inset: 0pt,
    // Every second item row (the 2nd, 4th, …) is tinted, counted over the whole table.
    fill: (_, y) => if y == 0 { teal } else if calc.even(y) { surface } else { none },
    stroke: (_, y) => if y == 0 { none } else { (bottom: 0.3pt + border) },
    table.header(repeat: true, ..cols.map(header-cell)),
    ..items.rows.map(row => cols.enumerate().map(((i, c)) => {
      // The first cell carries the row's minimum height; the others stretch with the row.
      if i == 0 { min-height(row-min, body-cell(c, row)) } else { body-cell(c, row) }
    })).flatten(),
  )
}

// ── Tax breakdown and totals (never split across pages) ────────────────────────

#let tax-cell(al, weight, fill, body, size: t-table) = min-height(line-pitch, align(al, text(size: size, weight: weight, fill: fill, body)))

// Right-aligned under the items table, content-sized: the frame closes flush under the last rate
// row instead of guessing at "several rows" from a row count. The header row reads as a label
// (`t-label`, muted), the rate rows as table body (`t-table`).
#let tax-table(tb) = card(summary-card-w, grid(
  columns: (1fr, tax-net-col-w, tax-vat-col-w),
  row-gutter: 0pt,
  tax-cell(left, "bold", muted, tb.headers.rate, size: t-label),
  tax-cell(right, "bold", muted, tb.headers.net, size: t-label),
  tax-cell(right, "bold", muted, tb.headers.vat, size: t-label),
  ..tb.rows.map(r => (
    tax-cell(left, "regular", ink, r.rate), tax-cell(right, "regular", ink, r.net), tax-cell(right, "regular", ink, r.vat),
  )).flatten(),
))

// Net and VAT at the body size; the gross total, set apart in accent colour at the title-near
// size, sits below a hairline rule instead of just another row, so it reads as the card's one
// conclusion rather than a fourth line among equals.
#let totals-card(t) = card(summary-card-w, {
  kv-rows(
    ((label: t.netLabel, value: t.net), (label: t.vatLabel, value: t.vat)),
    totals-label-w, summary-card-w - 2 * card-inset - totals-label-w, size: t-body, label-bold: false,
  )
  v(s1)
  line(length: summary-card-w - 2 * card-inset, stroke: 0.5pt + border)
  v(s1)
  kv-rows(
    ((label: t.grossLabel, value: t.gross, bold: true, label-bold: true, label-fill: accent, value-fill: accent),),
    totals-label-w, summary-card-w - 2 * card-inset - totals-label-w, size: t-title, label-bold: false,
  )
})

#let totals-flow = block(breakable: false, width: body-width, {
  if data.taxBreakdown.present {
    align(right, tax-table(data.taxBreakdown))
    v(s2)
  }
  align(right, totals-card(data.totals))
})

// ── Closing text, note and validity ─────────────────────────────────────────────

// A titled note: one title column width for every note, so the note and the validity block
// always start their body text at the same x — a muted label rather than teal (teal stays
// reserved for the header bar and the table head). Title and body are exactly as tall as their
// own content, so a following note starts right below the previous one's last line.
#let note(title, body) = grid(
  columns: (note-indent, note-title-w, s1, 1fr),
  [], text(size: t-label, weight: "bold", fill: muted, title), [],
  {
    set par(leading: wrap-leading)
    text(size: t-body, body)
  },
)

#let note-block = if data.note.present {
  note(data.note.title, data.note.lines.map(free-line).join(linebreak()))
}

#let validity-block = note(data.validity.title, data.validity.lines.join(linebreak()))

// The note and the validity block are one unit: whenever they fit on a page together they never
// split, so the validity statement is never left alone on a last page while the note stays on
// the page before. Only a block taller than a whole page flows instead.
#let summary-parts = {
  if data.note.present {
    note-block
    v(s2)
  }
  validity-block
}
#let summary = context {
  let h = measure(block(width: body-width, summary-parts)).height
  if h <= flow-height { block(breakable: false, width: body-width, summary-parts) } else { summary-parts }
}

// ── Running footer ─────────────────────────────────────────────────────────────

// Lines of one footer column, each at least 12 pt apart; a wrapping line pushes the next down.
#let footer-column(width, lines) = stack(..lines.map(l => min-height(line-pitch, wbox(width, l))))

#let footer = {
  let f = data.footer
  at(0pt, 8pt, rect(width: 481pt, height: 2pt, fill: accent, stroke: none))
  // Contact lines come raw now; the template fits each one to the 135 pt column itself (the
  // register column starts at x = 135) — shrinking first, then shortening with an ellipsis — and
  // never wraps them.
  at(0pt, 14pt, stack(..f.contact.map(l => box(height: 12pt, fit-line(
    135pt, (t-label, 7pt, 6.5pt, 6pt), l.text, fill: if l.muted { muted } else { ink },
  )))))
  at(135pt, 14pt, footer-column(160pt, f.register.map(l => text(size: t-label, fill: muted, l))))
  at(295pt, 14pt, footer-column(125pt, f.management.map(l => text(size: t-label, fill: muted, l))))
  at(420pt, 14pt, wbox(61pt, al: right, text(size: t-label, weight: "bold", context {
    data.document.pageLabel
    counter(page).display()
    " / "
    str(counter(page).final().first())
  })))
}

// The footer is drawn at its absolute band position so its geometry does not depend on how
// Typst positions a page footer; the 78 pt bottom margin keeps the flow above the band.
#set page(
  width: 595pt,
  height: 842pt,
  margin: (left: 57pt, right: 57pt, top: 51pt, bottom: 842pt - footer-top),
  foreground: place(top + left, dx: 57pt, dy: footer-top, box(width: body-width, height: 64pt, footer)),
)

// ── Document flow ──────────────────────────────────────────────────────────────

#head
// Free text block: 36 pt minimum height (minimum reserve for one or two lines), 8 pt top inset.
// Flow resumes 13 pt below the block.
#if free-text-inline {
  min-height(36pt, free-text-block(data.introText))
  v(13pt)
} else {
  v(8pt)
}
#item-table
#if data.items.unitFootnotes.len() > 0 {
  v(2pt)
  text(size: t-label, fill: muted, data.items.unitFootnotes.join(" · "))
}
#v(s2)
#totals-flow
#if free-text-after {
  v(6pt)
  free-text-block(data.introText)
  v(10pt)
}
#if data.closingText.present {
  v(s3)
  free-text-block(data.closingText)
}
#v(s4)
#summary
