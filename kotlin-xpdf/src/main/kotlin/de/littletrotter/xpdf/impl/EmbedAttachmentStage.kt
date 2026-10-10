package de.littletrotter.xpdf.impl

import jakarta.enterprise.context.ApplicationScoped
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.apache.xmpbox.XMPMetadata
import org.apache.xmpbox.xml.DomXmpParser
import org.apache.xmpbox.xml.XmpSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Calendar

/**
 * One file to carry inside a PDF. The stage knows only bytes and a media type; what the bytes
 * mean (an offer's XML, an invoice's XML, anything else) is the caller's business.
 */
class EmbeddedAttachment(
  val content: ByteArray,
  val fileName: String,
  val mimeType: String,
  val description: String,
)

/**
 * Generic attachment embedding for PDF/A-3: the file goes into the catalog's `/EmbeddedFiles`
 * name tree, is listed in the catalog's `/AF` array with `/AFRelationship /Data` (the
 * relationship PDF/A-3 allows for content that is neither the source nor an alternative of the
 * page content), and its description is mirrored into the XMP packet as `dc:description` so a
 * reader that only looks at the metadata can tell what the file is.
 *
 * Deliberately no Factur-X or Order-X XMP extension schema: those mark a document as a hybrid
 * invoice or order, which would make validators demand a conforming XML. Documents carrying
 * other data stay plain PDF/A-3 files with an attachment.
 *
 * Attachments arrive as bytes handed over by the caller (read from object storage by reference);
 * nothing large ever travels through the job message itself.
 */
@ApplicationScoped
class EmbedAttachmentStage {

  /** Returns the re-saved PDF with [attachment] added; existing embedded files and metadata are kept. */
  fun embed(pdfBytes: ByteArray, attachment: EmbeddedAttachment): ByteArray =
    Loader.loadPDF(pdfBytes).use { document ->
      val spec = fileSpecification(document, attachment)
      registerEmbeddedFile(document, spec, attachment.fileName)
      listAsAssociatedFile(document, spec)
      mirrorDescriptionInXmp(document, attachment.description)
      val out = ByteArrayOutputStream()
      document.save(out)
      out.toByteArray()
    }

  private fun fileSpecification(document: PDDocument, attachment: EmbeddedAttachment): PDComplexFileSpecification {
    val embeddedFile = PDEmbeddedFile(document, ByteArrayInputStream(attachment.content))
    embeddedFile.subtype = attachment.mimeType
    embeddedFile.size = attachment.content.size
    val now = Calendar.getInstance()
    embeddedFile.creationDate = now
    embeddedFile.modDate = now

    val spec = PDComplexFileSpecification()
    spec.file = attachment.fileName
    spec.fileUnicode = attachment.fileName
    spec.fileDescription = attachment.description
    spec.embeddedFile = embeddedFile
    spec.embeddedFileUnicode = embeddedFile
    spec.cosObject.setName(COSName.getPDFName(AF_RELATIONSHIP), AF_RELATIONSHIP_DATA)
    return spec
  }

  private fun registerEmbeddedFile(document: PDDocument, spec: PDComplexFileSpecification, fileName: String) {
    val catalog = document.documentCatalog
    val names = catalog.names ?: PDDocumentNameDictionary(catalog).also { catalog.names = it }
    val tree = names.embeddedFiles ?: PDEmbeddedFilesNameTreeNode()
    require(tree.kids.isNullOrEmpty()) { "Embedded-file name tree with child nodes is not supported" }
    val entries = LinkedHashMap<String, PDComplexFileSpecification>(tree.names ?: emptyMap())
    entries[fileName] = spec
    tree.names = entries
    names.embeddedFiles = tree
  }

  private fun listAsAssociatedFile(document: PDDocument, spec: PDComplexFileSpecification) {
    val catalogDictionary = document.documentCatalog.cosObject
    val associated = catalogDictionary.getCOSArray(COSName.AF) ?: COSArray().also { catalogDictionary.setItem(COSName.AF, it) }
    associated.add(spec.cosObject)
  }

  private fun mirrorDescriptionInXmp(document: PDDocument, description: String) {
    val catalog = document.documentCatalog
    val existing = catalog.metadata
    val xmp = if (existing != null) DomXmpParser().parse(existing.createInputStream()) else XMPMetadata.createXMPMetadata()
    val dublinCore = xmp.dublinCoreSchema ?: xmp.createAndAddDublinCoreSchema()
    // The default-language description is one value per document, so a second attachment extends
    // it instead of silently replacing the first one's.
    val previous = dublinCore.getDescription(DESCRIPTION_LANGUAGE)
    dublinCore.addDescription(DESCRIPTION_LANGUAGE, if (previous == null) description else "$previous; $description")
    val serialized = ByteArrayOutputStream()
    XmpSerializer().serialize(xmp, serialized, true)
    val metadata = PDMetadata(document)
    metadata.importXMPMetadata(serialized.toByteArray())
    catalog.metadata = metadata
  }

  private companion object {
    const val AF_RELATIONSHIP = "AFRelationship"
    const val AF_RELATIONSHIP_DATA = "Data"
    const val DESCRIPTION_LANGUAGE = "x-default"
  }
}
