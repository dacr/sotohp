package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.tapir.Schema

import java.time.OffsetDateTime

case class ApiSynchronizeStatus(
  running: Boolean,
  lastUpdated: Option[OffsetDateTime],
  checkedCount: Long,
  processedCount: Long,
  startedAt: Option[OffsetDateTime],
)

object ApiSynchronizeStatus {
  given JsonValueCodec[ApiSynchronizeStatus] = JsonCodecMaker.make
  given Schema[ApiSynchronizeStatus]         = Schema.derived[ApiSynchronizeStatus].name(Schema.SName("SynchronizeStatus"))
}
