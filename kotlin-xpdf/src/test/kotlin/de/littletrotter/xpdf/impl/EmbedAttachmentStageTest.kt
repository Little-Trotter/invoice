package de.littletrotter.xpdf.impl

import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import org.apache.xmpbox.XMPMetadata
import org.apache.xmpbox.xml.XmpSerializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/** [EmbedAttachmentStage] puts a file into `/EmbeddedFiles`, `/AF` (relationship `Data`) and the XMP description. */
class EmbedAttachmentStageTest {

  private val stage = EmbedAttachmentStage()
  private val xml = "<quote><id>Q-1</id></quote>".toByteArray()

  private fun attachment(name: String = "quote.xml", description: String = "Machine-readable offer") =
    EmbeddedAttachment(xml, name, "application/xml", description)

  private fun save(document: PDDocument): ByteArray {
    val out = ByteArrayOutputStream()
    document.save(out)
    return out.toByteArray()
  }

  private fun plainPdf(customize: (PDDocument) -> Unit = {}): ByteArray = PDDocument().use { doc ->
    doc.addPage(PDPage())
    customize(doc)
    save(doc)
  }

  @Test
  fun `attachment is embedded with data relationship, media type and content`() {
    val result = stage.embed(plainPdf(), attachment())

    Loader.loadPDF(result).use { doc ->
      val files = doc.documentCatalog.names.embeddedFiles.names
      val spec = files.getValue("quote.xml")
      assertEquals("Data", spec.cosObject.getNameAsString("AFRelationship"))
      assertEquals("application/xml", spec.embeddedFile.subtype)
      assertEquals("Machine-readable offer", spec.fileDescription)
      assertEquals("quote.xml", spec.fileUnicode)
      assertEquals(xml.size, spec.embeddedFile.size)
      assertTrue(spec.embeddedFile.toByteArray().contentEquals(xml))
      val associated = doc.documentCatalog.cosObject.getCOSArray(COSName.AF)
      assertEquals(1, associated.size())
    }
  }

  @Test
  fun `description lands in the XMP packet as dc description`() {
    val result = stage.embed(plainPdf(), attachment())

    Loader.loadPDF(result).use { doc ->
      val xmpText = String(doc.documentCatalog.metadata.createInputStream().readAllBytes(), Charsets.UTF_8)
      assertTrue(xmpText.contains("Machine-readable offer"))
      assertTrue(xmpText.contains("dc:description"))
      assertTrue(!xmpText.contains("fx:") && !xmpText.contains("orderx"))
    }
  }

  @Test
  fun `existing XMP packet and existing embedded files are kept`() {
    val base = plainPdf { doc ->
      val xmp = XMPMetadata.createXMPMetadata()
      xmp.createAndAddDublinCoreSchema().addCreator("Little Trotter")
      val bytes = ByteArrayOutputStream().also { XmpSerializer().serialize(xmp, it, true) }.toByteArray()
      val metadata = PDMetadata(doc)
      metadata.importXMPMetadata(bytes)
      doc.documentCatalog.metadata = metadata
    }
    val first = stage.embed(base, attachment("first.xml", "First"))
    val second = stage.embed(first, attachment("second.xml", "Second"))

    Loader.loadPDF(second).use { doc ->
      assertEquals(setOf("first.xml", "second.xml"), doc.documentCatalog.names.embeddedFiles.names.keys)
      assertEquals(2, doc.documentCatalog.cosObject.getCOSArray(COSName.AF).size())
      val xmpText = String(doc.documentCatalog.metadata.createInputStream().readAllBytes(), Charsets.UTF_8)
      assertTrue(xmpText.contains("Little Trotter"))
      assertTrue(xmpText.contains("First") && xmpText.contains("Second"))
    }
  }

  @Test
  fun `an embedded-file tree with child nodes is rejected instead of silently replaced`() {
    val withKids = plainPdf { doc ->
      val catalog = doc.documentCatalog
      val names = PDDocumentNameDictionary(catalog)
      val root = PDEmbeddedFilesNameTreeNode()
      val kid = PDEmbeddedFilesNameTreeNode()
      kid.names = mapOf("a.xml" to PDComplexFileSpecification())
      root.kids = listOf(kid)
      names.embeddedFiles = root
      catalog.names = names
    }

    assertThrows(IllegalArgumentException::class.java) { stage.embed(withKids, attachment()) }
  }
}
