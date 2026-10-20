#!/usr/bin/env python3
# Project: Little Trotter X-Invoice
# Copyright: Stefan Zils 2026
#
# Disclaimer: This software is provided "as is", without warranty of any kind,
# express or implied, including but not limited to the warranties of
# merchantability, fitness for a particular purpose, and noninfringement.

"""Generates the draw.io canvas sources from the AsciiDoc canvases.

The two canvases have two renderings: the HTML page docToolchain produces, and a draw.io
file offered next to it for anyone who wants to edit or present the canvas as a diagram.
Both must show the same thing, and the .adoc is the single source — the draw.io files
were hand-written once and had already drifted (the HTML carried content the diagram
never got).

Layout is NOT derived: box positions, sizes, colors and cell ids are fixed per canvas in
LAYOUTS below, matching the grid the canvases were designed on. Only the section TEXT
comes from the .adoc, keyed by the `[.canvas-<slug>]` role that already precedes every
section heading. A section in the .adoc with no box in the layout (or the other way
round) is an error, not a silent omission — that is the check that keeps the two
representations from drifting again.
"""

from __future__ import annotations

import argparse
import base64
import html
import re
import shutil
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DOC_DIR = PROJECT_ROOT / "doc"
HTML_DIR = PROJECT_ROOT / "html"

# Shared cell styles, lifted verbatim from the hand-built canvases so regenerating
# changes only the text, never the look.
BOX_STYLE = (
    "rounded=0;whiteSpace=wrap;html=1;align=left;verticalAlign=top;spacing=8;"
    "fontSize=10;fontColor=#202124;fillColor=#FFFFFF;strokeColor=#DADCE0;strokeWidth=2;"
)
RISK_BOX_STYLE_AIC = (
    "rounded=0;whiteSpace=wrap;html=1;align=left;verticalAlign=top;spacing=8;"
    "fontSize=10;fontColor=#202124;fillColor=#FCEEEE;strokeColor=#D93025;strokeWidth=3;"
)
RISK_BOX_STYLE_ACC = (
    "rounded=0;whiteSpace=wrap;html=1;align=left;verticalAlign=top;spacing=8;"
    "fontSize=10;fontColor=#202124;fillColor=#FDF3F2;strokeColor=#D93025;strokeWidth=3;"
)
# Header: brand mark, a small uppercase eyebrow naming the canvas, the product as the
# dominant line, and the version flush right. It spans exactly the frame's outer edges
# so header and canvas read as one block.
EYEBROW_STYLE = (
    "text;html=1;align=left;verticalAlign=top;spacing=0;"
    "fontSize=11;fontStyle=1;fontColor=#1A73E8;"
)
TITLE_STYLE = (
    "text;html=1;align=left;verticalAlign=bottom;spacing=0;"
    "fontSize=22;fontStyle=1;fontColor=#202124;"
)
META_STYLE = (
    "text;html=1;align=right;verticalAlign=bottom;spacing=0;"
    "fontSize=11;fontColor=#5F6368;"
)
BRAND_ICON = DOC_DIR / "images" / "little-trotter-head-icon.svg"
HEADER_HEIGHT = 40
HEADER_ICON_GAP = 12
# Offset of a section's diagram below its heading and text, so the text above stays
# readable at the narrowest box the diagram sits in.
IMAGE_TOP = 170
FRAME_STYLE = "rounded=0;html=1;fillColor=#FFFFFF;strokeColor=#DADCE0;strokeWidth=3;"

ACCENT = "#1A73E8"
DANGER = "#D93025"

# Every cell is shifted by this margin so nothing sits at a negative coordinate: draw.io
# adds a whole extra page to the left/top for any cell outside the page origin, which
# showed up as a large unused area on the left when the file was opened.
PAGE_MARGIN_X = 40
PAGE_MARGIN_Y = 20

# One entry per canvas: the .adoc it is generated from, the .drawio it writes, the page
# size, and the boxes. Both canvases share one grid: 10px gutters, 20px frame padding on
# every side, full-width bands 130px high and every body row 220px, so rows line up
# edge to edge and a box spanning two rows is exactly 2 x 220 + 10. A box is
# (cell id, canvas-role slug, x, y, width, height, heading
# color, box style, accent-bar color or None). The bar is the thin colored strip the
# communication canvas uses to tell its sections apart; the inception canvas has none.
LAYOUTS = {
    "architecture-inception-canvas": {
        "diagram_id": "aic-canvas",
        "diagram_name": "Architecture Inception Canvas",
        "page": (1230, 930),
        "frame": (-20, 50, 1230, 860),
        "boxes": [
            ("business", "business", 0, 70, 1190, 130, ACCENT, BOX_STYLE, None),
            ("functional", "functional", 0, 210, 290, 220, ACCENT, BOX_STYLE, None),
            ("context", "context", 300, 210, 490, 450, ACCENT, BOX_STYLE, None),
            ("org", "org-constraints", 800, 210, 390, 220, ACCENT, BOX_STYLE, None),
            ("quality", "quality", 0, 440, 290, 220, ACCENT, BOX_STYLE, None),
            ("tech", "tech-constraints", 800, 440, 390, 220, ACCENT, BOX_STYLE, None),
            ("hypotheses", "hypotheses", 0, 670, 590, 220, ACCENT, BOX_STYLE, None),
            ("risks", "risks", 600, 670, 590, 220, DANGER, RISK_BOX_STYLE_AIC, None),
        ],
    },
    "architecture-communication-canvas": {
        "diagram_id": "acc-canvas",
        "diagram_name": "Architecture Communication Canvas",
        "page": (1230, 930),
        "frame": (-20, 50, 1230, 860),
        "boxes": [
            ("value", "value", 0, 70, 290, 220, ACCENT, BOX_STYLE, "#1A73E8"),
            (
                "functions",
                "functions",
                300,
                70,
                290,
                220,
                "#8E24AA",
                BOX_STYLE,
                "#8E24AA",
            ),
            (
                "decisions",
                "decisions",
                600,
                70,
                290,
                450,
                "#F4511E",
                BOX_STYLE,
                "#F4511E",
            ),
            (
                "technologies",
                "technologies",
                900,
                70,
                290,
                450,
                "#E65100",
                BOX_STYLE,
                "#E65100",
            ),
            (
                "stakeholders",
                "stakeholders",
                0,
                300,
                290,
                220,
                "#188038",
                BOX_STYLE,
                "#188038",
            ),
            ("quality", "quality", 300, 300, 290, 220, "#455A64", BOX_STYLE, "#455A64"),
            ("context", "context", 0, 530, 590, 220, ACCENT, BOX_STYLE, "#1A73E8"),
            (
                "components",
                "components",
                600,
                530,
                590,
                220,
                "#188038",
                BOX_STYLE,
                "#188038",
            ),
            (
                "risks",
                "risks",
                0,
                760,
                1190,
                130,
                DANGER,
                RISK_BOX_STYLE_ACC,
                "#D93025",
            ),
        ],
    },
}


def parse_sections(
    adoc: Path,
) -> tuple[str, dict[str, tuple[str, list[str]]], list[str]]:
    """Splits a canvas .adoc into its `[.canvas-<slug>]` sections.

    Returns the document title, `{slug: (heading, body lines)}` and the footer
    block after the trailing `'''` (template credits and cross-links). The
    footer is deliberately NOT part of any section — the draw.io file has no
    use for it — but the HTML mirror renders it below the canvas, because it
    carries the CC BY attribution.
    """
    lines = adoc.read_text(encoding="utf-8").splitlines()

    title = ""
    for line in lines:
        if line.startswith("= "):
            title = line[2:].strip()
            break

    sections: dict[str, tuple[str, list[str]]] = {}
    slug: str | None = None
    heading = ""
    body: list[str] = []
    footer: list[str] = []
    in_footer = False

    def flush() -> None:
        if slug is not None:
            sections[slug] = (heading, body.copy())

    for line in lines:
        if in_footer:
            footer.append(line)
            continue
        role = re.fullmatch(r"\[\.canvas-([a-z-]+)\]", line.strip())
        if role:
            flush()
            slug = role.group(1)
            heading = ""
            body = []
            continue
        if slug is None:
            continue
        if re.fullmatch(r"\[\[[^\]]+\]\]|\.[A-Z].*", line.strip()):
            continue  # legacy-anchor and block-title lines carry no canvas text
        if (
            line.strip() == "'''"
        ):  # start of the footer — nothing below belongs to a section
            flush()
            slug = None
            in_footer = True
            continue
        if not heading and line.startswith("== "):
            heading = line[3:].strip()
            continue
        # Skip the blank line that always follows a heading; keep the rest verbatim,
        # blank lines included (they become paragraph breaks).
        if not body and not line.strip():
            continue
        body.append(line)

    if not in_footer:
        flush()
    return title, sections, footer


def inline_markup(text: str) -> str:
    """Converts AsciiDoc inline markup to the HTML subset draw.io labels render."""
    # Links first: their targets must not be mistaken for emphasis markers.
    text = re.sub(r"link:[^\[]+\[([^\]]*)\]", r"\1", text)
    text = re.sub(r"https?://\S+?\[([^\]]*)\]", r"\1", text)
    text = re.sub(r"\*([^*]+)\*", r"<b>\1</b>", text)
    # Underscores only pair as italics between word boundaries — identifiers like
    # kotlin_invoice must survive untouched.
    text = re.sub(r"(?<![\w`])_([^_]+)_(?![\w`])", r"<i>\1</i>", text)
    text = re.sub(r"`([^`]+)`", r"\1", text)
    return text


def render_body(body: list[str]) -> str:
    """Turns a section's AsciiDoc lines into one draw.io label string.

    Bullets become "• " lines, an `image::` macro becomes the same italic pointer at
    the HTML rendering the hand-built canvases already used (a draw.io label cannot
    embed the SVG), and a blank line becomes a paragraph break.
    """
    parts: list[str] = []
    pending_break = False

    for raw in body:
        line = raw.rstrip()
        if not line:
            pending_break = bool(parts)
            continue

        image = re.fullmatch(r"image::([^\[]+)\[([^,\]]*)[^\]]*\]", line)
        if image:
            # Diagram images are embedded directly as graphic cells in draw.io;
            # omit placeholder text so it does not appear above the image.
            continue
        elif line.startswith("* "):
            rendered = "• " + inline_markup(line[2:].strip())
        else:
            rendered = inline_markup(line)

        if parts:
            parts.append("<br><br>" if pending_break else "<br>")
        parts.append(rendered)
        pending_break = False

    return "".join(parts)


def cell(
    cell_id: str, value: str, style: str, x: int, y: int, width: int, height: int
) -> str:
    escaped = html.escape(value, quote=True)
    return (
        f'        <mxCell id="{cell_id}" value="{escaped}" style="{style}" '
        f'vertex="1" parent="1">\n'
        f'          <mxGeometry x="{x + PAGE_MARGIN_X}" y="{y + PAGE_MARGIN_Y}" '
        f'width="{width}" height="{height}" '
        f'as="geometry"/>\n'
        f"        </mxCell>\n"
    )


def resolve_image_ref(
    img_src: str,
    img_file: Path,
    width: int,
    embed_svg_defs: bool,
    defs: list[str],
) -> tuple[str, str | None]:
    """Resolves an `image::` source to (draw.io style ref, HTML mirror data URI).

    Prefers the pre-committed vector sibling (<stem>-canvas.svg) embedded as a
    shared <defs> resource (draw.io's own "Compress repeated images" format),
    referenced via image=def(<id>) — the payload stays out of the style
    attribute, where large base64 data URIs break in draw.io 31.x, and scales
    crisply. Falls back to the JPEG sibling (<stem>-canvas.jpg, ~20-30 KB,
    below that threshold), then rsvg-convert to PNG, then raw base64 (which may
    show a placeholder in draw.io). The second element is the data URI for the
    HTML mirror, or None when nothing could be embedded.
    """
    if not img_file.is_file():
        return f"images/{img_src}", None

    mime = {
        "svg": "image/svg+xml",
        "png": "image/png",
        "jpg": "image/jpeg",
        "jpeg": "image/jpeg",
    }.get(img_file.suffix.lstrip("."), "application/octet-stream")

    if img_src.endswith(".svg") or img_src.endswith(".png"):
        stem = Path(img_src).stem
        svg_sibling = DOC_DIR / "images" / f"{stem}-canvas.svg"
        def_id = re.sub(r"[^A-Za-z0-9_-]", "-", stem)
        if embed_svg_defs and svg_sibling.is_file():
            uri = f"data:image/svg+xml;base64,{_b64(svg_sibling)}"
            defs.append(f'<def id="{def_id}" data="{uri}"/>')
            return f"def({def_id})", uri
        jpeg_sibling = DOC_DIR / "images" / f"{stem}-canvas.jpg"
        if jpeg_sibling.is_file():
            uri = f"data:image/jpeg;base64,{_b64(jpeg_sibling)}"
            return _style_ref(uri), uri
        if img_src.endswith(".svg") and shutil.which("rsvg-convert"):
            png_bytes = subprocess.check_output(
                [
                    "rsvg-convert",
                    "-w",
                    str((width - 20) * 2),
                    "-f",
                    "png",
                    str(img_file),
                ]
            )
            uri = f"data:image/png;base64,{base64.b64encode(png_bytes).decode('ascii')}"
            return _style_ref(uri), uri

    uri = f"data:{mime};base64,{_b64(img_file)}"
    return _style_ref(uri), uri


def _style_ref(uri: str) -> str:
    """Escapes ';' in a data URI: draw.io's style parser splits on it."""
    return uri.replace(";base64,", "%3Bbase64,", 1)


def _b64(path: Path) -> str:
    return base64.b64encode(path.read_bytes()).decode("ascii")


def read_attribute(adoc: Path, name: str) -> str:
    """Returns a document attribute (`:name: value`) from the .adoc header, or ""."""
    match = re.search(
        rf"^:{re.escape(name)}:\s*(.*)$", adoc.read_text(encoding="utf-8"), re.MULTILINE
    )
    return match.group(1).strip() if match else ""


def split_title(title: str) -> tuple[str, str]:
    """Splits "Product — Canvas name" into (product, canvas name)."""
    product, _, canvas = title.partition(" — ")
    return product.strip(), canvas.strip()


def brand_icon_style_ref() -> str:
    """The brand mark as a small PNG data URI for a draw.io image style.

    Rasterised because the vector source is too large for a style attribute (draw.io
    breaks on long data URIs there). The mark is part of the canvas identity, so a
    missing source or rasteriser stops the generator instead of silently drawing a
    header without it.
    """
    if not BRAND_ICON.is_file():
        raise SystemExit(f"[error] brand mark not found: {BRAND_ICON}")
    if not shutil.which("rsvg-convert"):
        raise SystemExit(
            "[error] rsvg-convert not found; it rasterises the brand mark for the "
            "draw.io header (macOS: brew install librsvg)"
        )
    png = subprocess.check_output(
        ["rsvg-convert", "-w", str(HEADER_HEIGHT * 2), "-f", "png", str(BRAND_ICON)]
    )
    return _style_ref(f"data:image/png;base64,{base64.b64encode(png).decode('ascii')}")


def header_cells(layout: dict, title: str, version: str) -> list[str]:
    frame_x, _, frame_width, _ = layout["frame"]
    product, canvas = split_title(title)
    text_x = frame_x + HEADER_HEIGHT + HEADER_ICON_GAP
    text_width = frame_width - HEADER_HEIGHT - HEADER_ICON_GAP
    cells = [
        cell(
            "brand",
            "",
            "shape=image;html=1;imageAspect=1;aspect=fixed;"
            f"image={brand_icon_style_ref()};",
            frame_x,
            0,
            HEADER_HEIGHT,
            HEADER_HEIGHT,
        )
    ]
    eyebrow = (
        f'<span style="letter-spacing:0.12em">{html.escape(canvas.upper())}</span>'
    )
    cells += [
        cell("eyebrow", eyebrow, EYEBROW_STYLE, text_x, 0, text_width, 14),
        cell("title", product, TITLE_STYLE, text_x, 14, text_width, 26),
        cell("meta", f"arc42 · v{version}", META_STYLE, text_x, 0, text_width, 40),
    ]
    return cells


def check_sections(
    name: str, layout: dict, sections: dict[str, tuple[str, list[str]]]
) -> None:
    """Fails on any drift between the .adoc sections and the layout boxes."""
    known = {slug for _, slug, *_ in layout["boxes"]}
    missing = known - sections.keys()
    if missing:
        raise SystemExit(
            f"{name}: no [.canvas-*] section in the .adoc for: "
            f"{', '.join(sorted(missing))}"
        )
    extra = sections.keys() - known
    if extra:
        raise SystemExit(
            f"{name}: .adoc section without a box in the layout: "
            f"{', '.join(sorted(extra))}"
        )


def cell_value(heading: str, heading_color: str, section_body: list[str]) -> str:
    """The one label both representations share: draw.io escapes it into the
    value attribute, the HTML mirror injects it verbatim (it is plain HTML)."""
    return (
        f"<font color='{heading_color}'>"
        f"<b style='font-size:13px'>{heading}</b></font>"
        f"<br>{render_body(section_body)}"
    )


def build_drawio(
    name: str,
    layout: dict,
    title: str,
    version: str,
    sections: dict[str, tuple[str, list[str]]],
    embed_svg_defs: bool = True,
) -> str:
    check_sections(name, layout, sections)

    page_width, page_height = layout["page"]
    page_width += PAGE_MARGIN_X
    page_height += PAGE_MARGIN_Y
    frame_x, frame_y, frame_width, frame_height = layout["frame"]

    # Shared <defs> resources live directly inside <mxGraphModel> — the same place
    # draw.io's own "Compress repeated images" writer puts them — and are referenced
    # from cell styles via image=def(<id>).
    defs: list[str] = []
    head = [
        # Banner for humans and AI agents alike: this file is output, the .adoc is
        # the source.
        f"<!-- GENERATED FILE - DO NOT EDIT. Generated from doc/{name}.adoc by "
        "scripts/generate-canvas-drawio.py; any manual change here is overwritten by "
        "the next build. Edit the .adoc and rerun the generator. -->\n",
        '<mxfile host="drawio-desktop" agent="generate-canvas-drawio.py" '
        'version="24.7.5" type="device">\n',
        f'  <diagram id="{layout["diagram_id"]}" name="{layout["diagram_name"]}">\n',
        f'    <mxGraphModel dx="0" dy="0" grid="1" gridSize="10" guides="1" '
        f'tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" '
        f'pageWidth="{page_width}" '
        f'pageHeight="{page_height}" math="0" shadow="0">\n',
    ]
    body = [
        "      <root>\n",
        '        <mxCell id="0"/>\n',
        '        <mxCell id="1" parent="0"/>\n',
        *header_cells(layout, title, version),
        cell("frame", "", FRAME_STYLE, frame_x, frame_y, frame_width, frame_height),
    ]

    for cell_id, slug, x, y, width, height, heading_color, style, bar_color in layout[
        "boxes"
    ]:
        heading, section_body = sections[slug]
        value = cell_value(heading, heading_color, section_body)
        body.append(cell(cell_id, value, style, x, y, width, height))
        if bar_color:
            bar_style = f"rounded=0;html=1;fillColor={bar_color};strokeColor=none;"
            body.append(cell(f"{cell_id}-bar", "", bar_style, x, y, 6, height))

        for line in section_body:
            img_match = re.fullmatch(
                r"image::([^\[]+)\[([^,\]]*)[^\]]*\]", line.strip()
            )
            if img_match:
                img_src = img_match.group(1)
                img_file = HTML_DIR / "images" / img_src
                if not img_file.is_file():
                    img_file = DOC_DIR / "images" / img_src

                image_ref, _ = resolve_image_ref(
                    img_src, img_file, width, embed_svg_defs, defs
                )

                img_style = (
                    "shape=image;html=1;verticalLabelPosition=bottom;verticalAlign=top;"
                    f"imageAspect=1;aspect=fixed;image={image_ref};"
                )
                body.append(
                    cell(
                        f"{cell_id}-img",
                        "",
                        img_style,
                        x + 10,
                        y + IMAGE_TOP,
                        width - 20,
                        height - IMAGE_TOP - 10,
                    )
                )

    body += [
        "      </root>\n",
        "    </mxGraphModel>\n",
        "  </diagram>\n",
        "</mxfile>\n",
    ]
    if defs:
        head.append("      <defs>" + "".join(defs) + "</defs>\n")
    return "".join(head + body)


# The mirror renders the canvas exactly as draw.io does: the same absolute
# geometry from LAYOUTS, the same label markup (cell_value is an HTML subset),
# the same embedded diagram payload. No draw.io CLI needed in the build.
MIRROR_CSS = """\
html { background: #F8F9FA; }
body { margin: 0; padding: 24px; font-family: Helvetica, Arial, sans-serif; }
.canvas-wrap { overflow-x: auto; }
.canvas { position: relative; margin: 0 auto; background: #FFFFFF; }
.frame { position: absolute; box-sizing: border-box; background: #FFFFFF;
         border: 3px solid #DADCE0; }
.header { position: absolute; display: flex; align-items: flex-end; gap: 12px; }
.header img { width: 40px; height: 40px; flex: none; }
.header-text { display: flex; flex-direction: column; min-width: 0; }
.eyebrow { font-size: 11px; font-weight: 700; letter-spacing: 0.12em;
           line-height: 14px; color: #1A73E8; }
.header h1 { margin: 0; font-size: 22px; font-weight: 700; line-height: 26px;
             letter-spacing: -0.01em; color: #202124; }
.meta { margin-left: auto; font-size: 11px; line-height: 14px; color: #5F6368;
        font-variant-numeric: tabular-nums; white-space: nowrap; }
.tile { position: absolute; box-sizing: border-box; overflow: hidden; padding: 8px;
        font-size: 10px; line-height: 1.45; color: #202124; }
.bar { position: absolute; left: 0; top: 0; width: 6px; height: 100%; }
.tile-img { position: absolute; object-fit: contain; }
.credits { margin: 20px auto 0; font-size: 12px; line-height: 1.6;
           color: #5F6368; }
.credits a { color: #1A73E8; }
.back-to-index {
  position: fixed;
  right: 1.5rem;
  bottom: 1.5rem;
  z-index: 1200;
  display: inline-flex;
  align-items: stretch;
  margin: 0;
  padding: 0;
  background: #1A73E8;
  color: #FFFFFF !important;
  font-family: Helvetica, Arial, sans-serif;
  text-decoration: none !important;
  white-space: nowrap;
  cursor: pointer;
}
.back-to-index:hover,
.back-to-index:focus-visible {
  background: #1557B0;
  color: #FFFFFF !important;
  text-decoration: none !important;
}
.back-to-index__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2.75rem;
  background: #202124;
  color: #FFFFFF;
  flex: 0 0 auto;
}
.back-to-index__arrow {
  font-size: 1.25rem;
  font-weight: 700;
  line-height: 1;
}
.back-to-index__copy {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 0.1rem;
  padding: 0.5rem 1rem 0.5rem 0.875rem;
  min-width: 0;
}
.back-to-index__eyebrow {
  font-size: 0.625rem;
  color: #FFFFFF;
  opacity: 0.85;
}
.back-to-index__title {
  font-size: 0.875rem;
  line-height: 1.15;
  font-weight: 700;
  color: #FFFFFF;
}
@media print {
  .back-to-index { display: none; }
}
"""


def _style_edges(style: str) -> tuple[str, str, str]:
    """fillColor / strokeColor / strokeWidth from a draw.io box style."""
    fill = re.search(r"fillColor=([^;]+)", style)
    stroke = re.search(r"strokeColor=([^;]+)", style)
    stroke_w = re.search(r"strokeWidth=([^;]+)", style)
    return (
        fill.group(1) if fill else "#FFFFFF",
        stroke.group(1) if stroke else "#DADCE0",
        stroke_w.group(1) if stroke_w else "2",
    )


def render_footer_html(lines: list[str]) -> str:
    """The [small]#...# credits block below the canvas, links included."""
    text = "\n".join(lines).strip()
    text = re.sub(r"^\[small\]#", "", text)
    if text.endswith("#"):
        text = text[:-1]
    text = html.escape(text)
    text = text.replace(" +\n", "<br>\n")
    text = re.sub(r"link:([^\[\s]+)\[([^\]]*)\]", r'<a href="\1">\2</a>', text)
    text = re.sub(r"(https?://[^\s\[]+)\[([^\]]*)\]", r'<a href="\1">\2</a>', text)
    return text


def build_html_mirror(
    name: str,
    layout: dict,
    title: str,
    version: str,
    sections: dict[str, tuple[str, list[str]]],
    footer: list[str],
    embed_svg_defs: bool = True,
) -> str:
    check_sections(name, layout, sections)
    page_width, page_height = layout["page"]
    page_width += PAGE_MARGIN_X
    page_height += PAGE_MARGIN_Y
    frame_x, frame_y, frame_width, frame_height = layout["frame"]
    product, canvas = split_title(title)

    parts = [
        f"<!-- GENERATED FILE - DO NOT EDIT. Generated from doc/{name}.adoc by "
        "scripts/generate-canvas-drawio.py (draw.io mirror); any manual change "
        "here is overwritten by the next build. Edit the .adoc and rerun the "
        "generator. -->\n",
        '<!DOCTYPE html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n',
        '<meta name="viewport" content="width=device-width, initial-scale=1">\n',
        f"<title>{html.escape(title)}</title>\n",
        f"<style>\n{MIRROR_CSS}</style>\n</head>\n<body>\n",
        '<div class="canvas-wrap">\n',
        f'<div class="canvas" style="width:{page_width}px;height:{page_height}px">\n',
        f'  <div class="frame" style="left:{frame_x + PAGE_MARGIN_X}px;'
        f"top:{frame_y + PAGE_MARGIN_Y}px;width:{frame_width}px;"
        f'height:{frame_height}px"></div>\n',
        f'  <header class="header" style="left:{frame_x + PAGE_MARGIN_X}px;'
        f"top:{PAGE_MARGIN_Y}px;width:{frame_width}px;height:{HEADER_HEIGHT}px\">\n"
        '    <img src="images/little-trotter-head-icon.svg" alt="Little Trotter">\n'
        '    <div class="header-text">\n'
        f'      <span class="eyebrow">{html.escape(canvas.upper())}</span>\n'
        f"      <h1>{html.escape(product)}</h1>\n"
        "    </div>\n"
        f'    <span class="meta">arc42 · v{html.escape(version)}</span>\n'
        "  </header>\n",
    ]

    for _cell_id, slug, x, y, width, height, heading_color, style, bar_color in layout[
        "boxes"
    ]:
        heading, section_body = sections[slug]
        fill, stroke, stroke_w = _style_edges(style)
        tile_style = (
            f"left:{x + PAGE_MARGIN_X}px;top:{y + PAGE_MARGIN_Y}px;"
            f"width:{width}px;height:{height}px;background:{fill};"
            f"border:{stroke_w}px solid {stroke};"
        )
        parts.append(f'  <div class="tile" style="{tile_style}">\n')
        if bar_color:
            parts.append(
                f'    <div class="bar" style="background:{bar_color}"></div>\n'
            )
        parts.append(f"    {cell_value(heading, heading_color, section_body)}\n")
        for line in section_body:
            img_match = re.fullmatch(
                r"image::([^\[]+)\[([^,\]]*)[^\]]*\]", line.strip()
            )
            if img_match:
                img_src = img_match.group(1)
                img_file = HTML_DIR / "images" / img_src
                if not img_file.is_file():
                    img_file = DOC_DIR / "images" / img_src
                _, uri = resolve_image_ref(img_src, img_file, width, embed_svg_defs, [])
                if uri:
                    parts.append(
                        f'    <img class="tile-img" alt="" '
                        f'style="left:10px;top:{IMAGE_TOP}px;width:{width - 20}px;'
                        f'height:{height - IMAGE_TOP - 10}px" src="{uri}">\n'
                    )
        parts.append("  </div>\n")

    parts.append("</div>\n</div>\n")
    if footer:
        parts.append(f'<div class="credits" style="max-width:{page_width}px">\n')
        parts.append(f"  {render_footer_html(footer)}\n</div>\n")
    parts.append(
        '  <a href="index.html" class="back-to-index" rel="home" '
        f'aria-label="Back from {html.escape(title)} to the documentation index" '
        'title="Back to documentation index">\n'
        '    <span class="back-to-index__icon" aria-hidden="true">\n'
        '      <span class="back-to-index__arrow">&larr;</span>\n'
        '    </span>\n'
        '    <span class="back-to-index__copy">\n'
        '      <span class="back-to-index__eyebrow">Documentation Hub</span>\n'
        '      <span class="back-to-index__title">Return to index</span>\n'
        '    </span>\n'
        '  </a>\n'
    )
    parts.append("</body>\n</html>\n")
    return "".join(parts)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generates the draw.io canvas sources from the AsciiDoc canvases."
    )
    parser.add_argument(
        "--embed-svg-defs",
        action=argparse.BooleanOptionalAction,
        default=False,
        help=(
            "embed canvas diagrams as shared <defs> SVG resources referenced via "
            "image=def(<id>); default (False) embeds inline %%3B-escaped base64 JPEG "
            "siblings for maximum compatibility with draw.io desktop and CLI export"
        ),
    )
    parser.add_argument(
        "--html-mirror",
        action="store_true",
        help=(
            "write self-contained HTML mirrors of the canvases into html/ "
            "(build output, not committed) instead of the .drawio sources "
            "into doc/"
        ),
    )
    args = parser.parse_args()

    if args.html_mirror and not HTML_DIR.is_dir():
        print(f"[error] html/ missing — run the site assembly first: {HTML_DIR}",
              file=sys.stderr)
        return 1

    for name, layout in LAYOUTS.items():
        adoc = DOC_DIR / f"{name}.adoc"
        if not adoc.is_file():
            print(f"[error] canvas source not found: {adoc}", file=sys.stderr)
            return 1

        title, sections, footer = parse_sections(adoc)
        version = read_attribute(adoc, "revnumber")
        if args.html_mirror:
            target = HTML_DIR / f"{name}.html"
            document = build_html_mirror(
                name, layout, title, version, sections, footer, args.embed_svg_defs
            )
        else:
            target = DOC_DIR / f"{name}.drawio"
            document = build_drawio(
                name, layout, title, version, sections, args.embed_svg_defs
            )
        target.write_text(document, encoding="utf-8")
        print(
            f"[ok] {target.relative_to(PROJECT_ROOT)} generated from {adoc.name} "
            f"({len(layout['boxes'])} sections)"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
