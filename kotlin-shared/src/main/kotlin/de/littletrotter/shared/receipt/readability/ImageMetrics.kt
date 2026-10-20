package de.littletrotter.shared.receipt.readability

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * Pure image measurements used to judge whether a scanned receipt page stays legible when printed:
 * edge sharpness (variance of the Laplacian) and structural similarity (SSIM) between two renderings.
 * No I/O, no PDF knowledge — callers hand in already decoded pixels.
 */
object ImageMetrics {

    /** Pixel density sharpness is compared at, so a small receipt and a full page score alike. */
    const val SHARPNESS_REFERENCE_DPI = 150

    private const val SSIM_BLOCK = 8
    private const val SSIM_K1 = 0.01
    private const val SSIM_K2 = 0.03
    private const val SSIM_DYNAMIC_RANGE = 255.0
    private const val MIN_SIDE = 3
    private const val LAPLACE_CENTER_WEIGHT = 4

    /**
     * Variance of the 3x3 Laplacian of the luminance, measured after resampling the image to
     * [SHARPNESS_REFERENCE_DPI] for a page shown [displayWidthInches] wide. A blurred or upscaled
     * scan has few strong edges and scores low; sharp text scores high. Returns 0 for images too
     * small to measure.
     */
    fun laplaceVariance(image: BufferedImage, displayWidthInches: Double): Double {
        require(displayWidthInches > 0.0) { "displayWidthInches must be positive" }
        val width = (displayWidthInches * SHARPNESS_REFERENCE_DPI).roundToInt().coerceAtLeast(MIN_SIDE)
        val height = (image.height.toDouble() * width / image.width).roundToInt().coerceAtLeast(MIN_SIDE)
        val luma = luminance(resample(image, width, height))
        return laplaceVariance(luma, width, height)
    }

    /**
     * Mean structural similarity of two images over non-overlapping 8x8 luminance blocks, in -1..1
     * (1 = identical). [candidate] is resampled to the size of [reference] first.
     */
    fun ssim(reference: BufferedImage, candidate: BufferedImage): Double {
        val width = reference.width
        val height = reference.height
        val a = luminance(reference)
        val b = luminance(resample(candidate, width, height))
        val c1 = (SSIM_K1 * SSIM_DYNAMIC_RANGE) * (SSIM_K1 * SSIM_DYNAMIC_RANGE)
        val c2 = (SSIM_K2 * SSIM_DYNAMIC_RANGE) * (SSIM_K2 * SSIM_DYNAMIC_RANGE)
        var sum = 0.0
        var blocks = 0
        for (y in 0..height - SSIM_BLOCK step SSIM_BLOCK) {
            for (x in 0..width - SSIM_BLOCK step SSIM_BLOCK) {
                sum += blockSsim(BlockPair(a, b, width, x, y), c1, c2)
                blocks++
            }
        }
        return if (blocks == 0) 1.0 else sum / blocks
    }

    private class BlockPair(val a: DoubleArray, val b: DoubleArray, val stride: Int, val x0: Int, val y0: Int)

    private fun blockSsim(block: BlockPair, c1: Double, c2: Double): Double {
        val n = (SSIM_BLOCK * SSIM_BLOCK).toDouble()
        var sumA = 0.0
        var sumB = 0.0
        for (dy in 0 until SSIM_BLOCK) {
            for (dx in 0 until SSIM_BLOCK) {
                val i = (block.y0 + dy) * block.stride + block.x0 + dx
                sumA += block.a[i]
                sumB += block.b[i]
            }
        }
        val meanA = sumA / n
        val meanB = sumB / n
        var varA = 0.0
        var varB = 0.0
        var cov = 0.0
        for (dy in 0 until SSIM_BLOCK) {
            for (dx in 0 until SSIM_BLOCK) {
                val i = (block.y0 + dy) * block.stride + block.x0 + dx
                val da = block.a[i] - meanA
                val db = block.b[i] - meanB
                varA += da * da
                varB += db * db
                cov += da * db
            }
        }
        varA /= n - 1
        varB /= n - 1
        cov /= n - 1
        return ((2 * meanA * meanB + c1) * (2 * cov + c2)) / ((meanA * meanA + meanB * meanB + c1) * (varA + varB + c2))
    }

    private fun laplaceVariance(luma: DoubleArray, width: Int, height: Int): Double {
        if (width < MIN_SIDE || height < MIN_SIDE) return 0.0
        var sum = 0.0
        var sumSquares = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val value = luma[i - 1] + luma[i + 1] + luma[i - width] + luma[i + width] - LAPLACE_CENTER_WEIGHT * luma[i]
                sum += value
                sumSquares += value * value
                count++
            }
        }
        val mean = sum / count
        return sumSquares / count - mean * mean
    }

    private fun luminance(image: BufferedImage): DoubleArray {
        val out = DoubleArray(image.width * image.height)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                val red = (rgb shr RED_SHIFT) and CHANNEL_MASK
                val green = (rgb shr GREEN_SHIFT) and CHANNEL_MASK
                val blue = rgb and CHANNEL_MASK
                out[y * image.width + x] = RED_WEIGHT * red + GREEN_WEIGHT * green + BLUE_WEIGHT * blue
            }
        }
        return out
    }

    /** Resample into an RGB bitmap; returns [image] itself when the size already matches. */
    fun resample(image: BufferedImage, width: Int, height: Int): BufferedImage {
        if (image.width == width && image.height == height) return image
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        // Plain bicubic aliases when shrinking by more than 2x; area averaging keeps thin strokes.
        val source: java.awt.Image = if (width * 2 < image.width) {
            image.getScaledInstance(width, height, java.awt.Image.SCALE_AREA_AVERAGING)
        } else {
            image
        }
        graphics.drawImage(source, 0, 0, width, height, null)
        graphics.dispose()
        return target
    }

    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val CHANNEL_MASK = 0xFF
    private const val RED_WEIGHT = 0.299
    private const val GREEN_WEIGHT = 0.587
    private const val BLUE_WEIGHT = 0.114
}
