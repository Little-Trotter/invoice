package de.littletrotter.infrastructure.quote

import java.net.URLDecoder

private val ENCODED_NAME = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
private val PLAIN_NAME = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)

/**
 * The file name a download is offered under. The component names it RFC 5987 style
 * (`filename*=UTF-8''…`); a plain `filename=` is tolerated, [fallback] applies when neither is present.
 */
internal fun downloadFileName(disposition: String?, fallback: String): String {
    val encoded = disposition?.let { ENCODED_NAME.find(it) }?.groupValues?.get(1)
    val plain = disposition?.let { PLAIN_NAME.find(it) }?.groupValues?.get(1)
    return encoded?.let { URLDecoder.decode(it, Charsets.UTF_8) } ?: plain ?: fallback
}
