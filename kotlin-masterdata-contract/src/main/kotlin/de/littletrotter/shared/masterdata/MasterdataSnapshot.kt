package de.littletrotter.shared.masterdata

/**
 * A replica asks for the complete inventory. [requestId] correlates the answer; [entityTypes]
 * narrows the request to some of [MasterdataEntityTypes.ALL], `null` asks for everything.
 */
data class SnapshotRequested(
  val requestId: String,
  val entityTypes: List<String>? = null,
)

/**
 * One slice of the snapshot answer. [entries] are the JSON texts of the upsert events of
 * [entityType] (the same shape the live subjects carry), so a consumer feeds them through the
 * code path of the live events. Chunks of one answer are numbered gapless from 0 by [sequence];
 * [last] marks the final one of the whole answer, which may carry no entries.
 */
data class SnapshotChunk(
  val requestId: String,
  val sequence: Int,
  val entityType: String,
  val last: Boolean,
  val entries: List<String>,
)

/** Names of the aggregate kinds a snapshot is made of, in the order they are delivered. */
object MasterdataEntityTypes {
  const val TAX_RATE = "taxrate"
  const val PAYMENT_TERM = "paymentterm"
  const val B2B_PROFILE = "b2bprofile"
  const val ARTICLE = "article"
  const val SELLER = "seller"
  const val CUSTOMER = "customer"
  const val NUMBER_RANGE = "numberrange"

  val ALL: List<String> = listOf(TAX_RATE, PAYMENT_TERM, B2B_PROFILE, ARTICLE, SELLER, CUSTOMER, NUMBER_RANGE)
}
