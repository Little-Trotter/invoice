package de.littletrotter.shared.receipt.readability

import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.Random
import kotlin.math.roundToInt

/** The receipt shapes the readability gate covers. */
enum class SyntheticReceiptKind { KASSENBON, A4_SCAN, STAMPED, PHOTO_SKEW }

/** A generated receipt: the master bitmap at [SyntheticReceipts.MASTER_DPI] and its body text as ground truth for OCR. */
class SyntheticReceipt(val kind: SyntheticReceiptKind, val image: BufferedImage, val text: String) {
    /** Width of the receipt in inches, the size it is shown at. */
    val widthInches: Double get() = image.width.toDouble() / SyntheticReceipts.MASTER_DPI
}

/**
 * Generates fictional receipts with 6-8 pt text, colour, stamps and skew. Everything is drawn from
 * fixed strings and a seeded random source, so no real document data is involved and runs repeat exactly.
 */
object SyntheticReceipts {

    const val MASTER_DPI = 400

    private const val POINTS_PER_INCH = 72.0
    private const val SEED = 20261012L
    private const val MM_PER_INCH = 25.4
    private const val LINE_FACTOR = 1.35
    private const val SKEW_DEGREES = 4.0
    private const val NOISE_AMPLITUDE = 10
    private const val STAMP_ALPHA = 0.85f
    private const val STAMP_ANGLE_DEGREES = -14.0

    private val products = listOf(
        "Brot 500g", "Milch 1,5% 1l", "Apfel Elstar 1kg", "Butter 250g", "Kaffee Bohnen 500g",
        "Mineralwasser 6x1,5l", "Nudeln 500g", "Tomaten 500g", "Kaese Gouda 200g", "Joghurt 150g",
        "Haferflocken 500g", "Olivenoel 0,5l", "Schokolade 100g", "Zwiebeln 1kg", "Eier 10er",
    )

    fun create(kind: SyntheticReceiptKind): SyntheticReceipt = when (kind) {
        SyntheticReceiptKind.KASSENBON -> bon(kind, 80.0, 190.0)
        SyntheticReceiptKind.A4_SCAN -> a4(kind, stamp = false)
        SyntheticReceiptKind.STAMPED -> a4(kind, stamp = true)
        SyntheticReceiptKind.PHOTO_SKEW -> skewed(kind)
    }

    private fun px(points: Double): Double = points * MASTER_DPI / POINTS_PER_INCH

    private fun canvas(widthMm: Double, heightMm: Double): BufferedImage {
        val width = (widthMm / MM_PER_INCH * MASTER_DPI).roundToInt()
        val height = (heightMm / MM_PER_INCH * MASTER_DPI).roundToInt()
        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    }

    private fun graphics(image: BufferedImage, background: Color): Graphics2D {
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = background
        g.fillRect(0, 0, image.width, image.height)
        return g
    }

    private fun itemLines(random: Random, count: Int, width: Int): List<String> = (0 until count).map {
        val name = products[random.nextInt(products.size)]
        val price = "%d,%02d".format(random.nextInt(19) + 1, random.nextInt(100))
        name.padEnd(width - price.length - 5) + price + " EUR"
    }

    private fun drawLines(g: Graphics2D, lines: List<String>, font: Font, x: Double, top: Double): Double {
        g.font = font
        val step = font.size2D * LINE_FACTOR
        var y = top
        for (line in lines) {
            y += step
            g.drawString(line, x.toFloat(), y.toFloat())
        }
        return y
    }

    private fun bon(kind: SyntheticReceiptKind, widthMm: Double, heightMm: Double): SyntheticReceipt {
        val random = Random(SEED)
        val image = canvas(widthMm, heightMm)
        val g = graphics(image, Color(250, 250, 248))
        g.color = Color(20, 20, 20)
        val font = Font(Font.MONOSPACED, Font.PLAIN, 1).deriveFont(px(7.0).toFloat())
        val lines = listOf("SUPERMARKT MUSTER GMBH", "Musterweg 12  12345 Musterstadt", "Datum 12.10.2026  Kasse 3") +
            itemLines(random, 28, 30) + listOf("SUMME  87,42 EUR", "Bar  100,00 EUR", "Rueckgeld  12,58 EUR", "Vielen Dank fuer Ihren Einkauf")
        drawLines(g, lines, font, px(10.0), px(10.0))
        g.dispose()
        return SyntheticReceipt(kind, image, lines.joinToString("\n"))
    }

    private fun a4(kind: SyntheticReceiptKind, stamp: Boolean): SyntheticReceipt {
        val random = Random(SEED + 1)
        val image = canvas(210.0, 297.0)
        val g = graphics(image, Color.WHITE)
        g.color = Color(0, 70, 160)
        g.fillRect(px(40.0).toInt(), px(30.0).toInt(), px(150.0).toInt(), px(26.0).toInt())
        g.color = Color(20, 20, 20)
        val body = Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(px(8.0).toFloat())
        val small = Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(px(6.0).toFloat())
        val head = listOf("Rechnung Nr. 2026-0815  Musterfirma GmbH  Musterweg 12  12345 Musterstadt")
        val items = itemLines(random, 38, 80)
        val footer = listOf(
            "Zahlbar innerhalb von 14 Tagen ohne Abzug. Bankverbindung Musterbank IBAN DE00 0000 0000 0000 0000 00",
            "Es gelten unsere Allgemeinen Geschaeftsbedingungen. Gerichtsstand ist Musterstadt. Alle Preise in EUR.",
            "Geschaeftsfuehrer Max Mustermann  Handelsregister Musterstadt HRB 12345  Steuernummer 00/000/00000",
        )
        var y = drawLines(g, head, body, px(40.0), px(62.0))
        y = drawLines(g, items, body, px(40.0), y)
        drawLines(g, footer, small, px(40.0), y + px(20.0))
        if (stamp) drawStamp(g, image)
        g.dispose()
        return SyntheticReceipt(kind, image, (head + items + footer).joinToString("\n"))
    }

    private fun drawStamp(g: Graphics2D, image: BufferedImage) {
        val saved = g.transform
        g.rotate(Math.toRadians(STAMP_ANGLE_DEGREES), image.width * 0.7, image.height * 0.8)
        g.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, STAMP_ALPHA)
        g.color = Color(200, 20, 30)
        g.stroke = java.awt.BasicStroke(px(1.5).toFloat())
        val x = (image.width * 0.55).toInt()
        val y = (image.height * 0.8).toInt()
        g.drawRect(x, y, px(120.0).toInt(), px(34.0).toInt())
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont(px(16.0).toFloat())
        g.drawString("BEZAHLT 12.10.2026", x + px(8.0).toInt(), y + px(24.0).toInt())
        g.transform = saved
        g.composite = java.awt.AlphaComposite.SrcOver
    }

    private fun skewed(kind: SyntheticReceiptKind): SyntheticReceipt {
        val bon = bon(kind, 80.0, 190.0)
        val margin = (px(40.0)).toInt()
        val image = BufferedImage(bon.image.width + 2 * margin, bon.image.height + 2 * margin, BufferedImage.TYPE_INT_RGB)
        val g = graphics(image, Color.GRAY)
        g.paint = GradientPaint(0f, 0f, Color(120, 100, 80), 0f, image.height.toFloat(), Color(70, 60, 50))
        g.fillRect(0, 0, image.width, image.height)
        g.rotate(Math.toRadians(SKEW_DEGREES), image.width / 2.0, image.height / 2.0)
        g.drawImage(bon.image, margin, margin, null)
        g.dispose()
        addNoise(image, Random(SEED + 2))
        return SyntheticReceipt(kind, image, bon.text)
    }

    private fun addNoise(image: BufferedImage, random: Random) {
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val delta = random.nextInt(2 * NOISE_AMPLITUDE + 1) - NOISE_AMPLITUDE
                val rgb = image.getRGB(x, y)
                val r = (((rgb shr 16) and 0xFF) + delta).coerceIn(0, 0xFF)
                val gr = (((rgb shr 8) and 0xFF) + delta).coerceIn(0, 0xFF)
                val b = ((rgb and 0xFF) + delta).coerceIn(0, 0xFF)
                image.setRGB(x, y, (r shl 16) or (gr shl 8) or b)
            }
        }
    }
}
