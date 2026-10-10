package de.littletrotter.domain.masterdata

/**
 * How far a receipt's stored PDF was reduced from what was uploaded, on the way to
 * `ReceiptSizePolicy.TARGET_RECEIPT_SIZE`. The pool keeps only the derivative — never the
 * original bytes — so this is the only record of which reduction, if any, produced it; the
 * receipt list and audit trail read it directly off [de.littletrotter.domain.masterdata.Receipt].
 *
 * Ordered by how much the document changes: [NONE] keeps every byte, [IMAGES_DOWNSAMPLED] keeps
 * text and vectors but re-encodes embedded images, [RASTERISED] replaces every page with a single
 * rendered image and loses the text layer. A reducer tries them in this order and stops as soon
 * as the result meets the target.
 */
enum class ReceiptNormalisationStage {
    /** Uploaded bytes already met the target; stored unchanged. */
    NONE,

    /** Embedded images were downsampled/re-encoded; text, vectors and metadata are unchanged. */
    IMAGES_DOWNSAMPLED,

    /** Every page was rendered to an image and reassembled into a new PDF; the text layer is lost. */
    RASTERISED,
}
