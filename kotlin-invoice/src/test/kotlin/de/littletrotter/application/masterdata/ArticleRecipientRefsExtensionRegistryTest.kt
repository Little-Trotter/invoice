package de.littletrotter.application.masterdata

import de.littletrotter.spi.masterdata.ArticleRecipientRefOption
import de.littletrotter.spi.masterdata.ArticleRecipientRefsExtension
import jakarta.enterprise.inject.Instance
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * [ArticleRecipientRefsExtensionRegistry] is the single point the article master-data
 * form uses to discover the (at most one) registered [ArticleRecipientRefsExtension].
 * The highest-risk case is the default, no-extension-on-the-classpath deployment: [enabledExtension]
 * must return `null`, not throw, when nothing is registered.
 */
class ArticleRecipientRefsExtensionRegistryTest {

    private fun fakeExtension(id: String) = object : ArticleRecipientRefsExtension {
        override val id: String = id
        override fun searchRecipients(filterText: String?, offset: Int, limit: Int): List<ArticleRecipientRefOption> = emptyList()
        override fun countRecipients(filterText: String?): Int = 0
        override fun recipientOption(recipientId: Long): ArticleRecipientRefOption? = null
    }

    @Test
    fun `happy path - enabledExtension returns the single registered extension`() {
        val extension = fakeExtension("erp-refs")

        val registry = ArticleRecipientRefsExtensionRegistry(listOf(extension))

        assertSame(extension, registry.enabledExtension())
    }

    @Test
    fun `boundary - enabledExtension returns null for an unregistered article, no extension on the classpath`() {
        val registry = ArticleRecipientRefsExtensionRegistry(emptyList())

        assertNull(registry.enabledExtension())
    }

    @Test
    fun `boundary - enabledExtension returns the first of several registered extensions deterministically`() {
        val first = fakeExtension("first")
        val second = fakeExtension("second")

        val registry = ArticleRecipientRefsExtensionRegistry(listOf(first, second))

        assertSame(first, registry.enabledExtension())
    }

    @Test
    fun `boundary - the CDI Instance-backed constructor materializes the injected extensions into the list`() {
        val extension = fakeExtension("cdi")
        val instance = mock<Instance<ArticleRecipientRefsExtension>>()
        whenever(instance.iterator()).thenReturn(mutableListOf(extension).iterator())

        val registry = ArticleRecipientRefsExtensionRegistry(instance)

        assertSame(extension, registry.enabledExtension())
    }
}
