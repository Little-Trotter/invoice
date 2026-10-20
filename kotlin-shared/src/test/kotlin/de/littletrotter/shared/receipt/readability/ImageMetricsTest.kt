package de.littletrotter.shared.receipt.readability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage

class ImageMetricsTest {

    private fun stripes(width: Int, height: Int, period: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, if ((x / period) % 2 == 0) Color.WHITE.rgb else Color.BLACK.rgb)
            }
        }
        return image
    }

    @Test
    fun `flat image has no edges`() {
        val flat = BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply { color = Color.WHITE; fillRect(0, 0, 300, 300); dispose() }
        }
        assertEquals(0.0, ImageMetrics.laplaceVariance(flat, 2.0), 1e-9)
    }

    @Test
    fun `sharp stripes score higher than blurred stripes`() {
        val sharp = stripes(600, 600, 4)
        val blurred = RasterTrial.blur(sharp, 2.0)
        assertTrue(ImageMetrics.laplaceVariance(sharp, 4.0) > 10 * ImageMetrics.laplaceVariance(blurred, 4.0))
    }

    @Test
    fun `laplace variance rejects a non positive display width`() {
        assertThrows(IllegalArgumentException::class.java) { ImageMetrics.laplaceVariance(stripes(10, 10, 2), 0.0) }
    }

    @Test
    fun `identical images have ssim one`() {
        val image = stripes(64, 64, 4)
        assertEquals(1.0, ImageMetrics.ssim(image, image), 1e-9)
    }

    @Test
    fun `different images have lower ssim`() {
        val a = stripes(64, 64, 4)
        val b = stripes(64, 64, 5)
        assertTrue(ImageMetrics.ssim(a, b) < 0.5)
    }

    @Test
    fun `blur lowers ssim monotonically`() {
        val sharp = stripes(128, 128, 3)
        val light = ImageMetrics.ssim(sharp, RasterTrial.blur(sharp, 0.7))
        val heavy = ImageMetrics.ssim(sharp, RasterTrial.blur(sharp, 2.0))
        assertTrue(light > heavy)
    }

    @Test
    fun `ssim of images smaller than one block is one`() {
        val tiny = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        assertEquals(1.0, ImageMetrics.ssim(tiny, tiny), 0.0)
    }

    @Test
    fun `resample keeps the image when the size matches`() {
        val image = stripes(20, 20, 2)
        assertTrue(ImageMetrics.resample(image, 20, 20) === image)
        assertEquals(10, ImageMetrics.resample(image, 10, 10).width)
    }
}
