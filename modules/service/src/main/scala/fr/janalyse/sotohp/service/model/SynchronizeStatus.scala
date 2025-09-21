package fr.janalyse.sotohp.service.model

import java.time.OffsetDateTime

case class SynchronizeStatus(
  running: Boolean,
  lastUpdated: Option[OffsetDateTime],
  processedCount: Long,
  startedAt: Option[OffsetDateTime]
)

object SynchronizeStatus {
  val empty: SynchronizeStatus = SynchronizeStatus(false, None, 0, None)
}
