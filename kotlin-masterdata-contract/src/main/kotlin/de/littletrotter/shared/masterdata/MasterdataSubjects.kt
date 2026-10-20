package de.littletrotter.shared.masterdata

/** NATS subject names of the master data replication; `masterdata.>` is the stream's filter. */
object MasterdataSubjects {

  const val ALL = "masterdata.>"

  const val CUSTOMER_UPSERTED = "masterdata.customer.upserted"
  const val CUSTOMER_DELETED = "masterdata.customer.deleted"
  const val SELLER_UPSERTED = "masterdata.seller.upserted"
  const val SELLER_DELETED = "masterdata.seller.deleted"
  const val ARTICLE_UPSERTED = "masterdata.article.upserted"
  const val ARTICLE_DELETED = "masterdata.article.deleted"
  const val TAX_RATE_UPSERTED = "masterdata.taxrate.upserted"
  const val TAX_RATE_DELETED = "masterdata.taxrate.deleted"
  const val PAYMENT_TERM_UPSERTED = "masterdata.paymentterm.upserted"
  const val PAYMENT_TERM_DELETED = "masterdata.paymentterm.deleted"
  const val B2B_PROFILE_UPSERTED = "masterdata.b2bprofile.upserted"
  const val B2B_PROFILE_DELETED = "masterdata.b2bprofile.deleted"
  const val NUMBER_RANGE_UPSERTED = "masterdata.numberrange.upserted"
  const val NUMBER_RANGE_DELETED = "masterdata.numberrange.deleted"

  /** A replica asks for the complete inventory (consumer to producer). */
  const val SNAPSHOT_REQUESTED = "masterdata.snapshot.requested"

  /** One slice of the inventory (producer to consumer). */
  const val SNAPSHOT_CHUNK = "masterdata.snapshot.chunk"
}
