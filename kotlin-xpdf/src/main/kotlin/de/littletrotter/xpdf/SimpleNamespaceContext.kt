package de.littletrotter.xpdf

import javax.xml.namespace.NamespaceContext

/** Minimal NamespaceContext backed by a prefix→URI map, for use with javax.xml.xpath. */
internal class SimpleNamespaceContext(private val map: Map<String, String>) : NamespaceContext {
    override fun getNamespaceURI(prefix: String): String = map[prefix] ?: javax.xml.XMLConstants.NULL_NS_URI
    override fun getPrefix(namespaceURI: String): String? = map.entries.firstOrNull { it.value == namespaceURI }?.key
    override fun getPrefixes(namespaceURI: String): Iterator<String> =
        map.entries.filter { it.value == namespaceURI }.map { it.key }.iterator()
}
