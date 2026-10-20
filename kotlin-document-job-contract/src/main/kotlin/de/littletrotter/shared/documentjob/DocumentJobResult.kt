package de.littletrotter.shared.documentjob

import de.littletrotter.shared.KositReportRefDto

/**
 * A job outcome as the worker publishes it. Unlike the request, every field is always written
 * (absent ones as `null`): readers have always been written against that shape.
 */
data class DocumentJobResult(
  val id: String?,
  val status: String?,
  val resultRefs: Map<String, String>?,
  val kositReportRef: KositReportRefDto?,
  val correlationId: String?,
)
