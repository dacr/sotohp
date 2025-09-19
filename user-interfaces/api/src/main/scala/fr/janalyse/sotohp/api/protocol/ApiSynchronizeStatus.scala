package fr.janalyse.sotohp.api.protocol

import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

import java.time.OffsetDateTime

case class ApiSynchronizeStatus(
  running: Boolean,
  lastUpdated: Option[OffsetDateTime],
  processedCount: Long
)

object ApiSynchronizeStatus {
  given JsonCodec[ApiSynchronizeStatus] = DeriveJsonCodec.gen
  given Schema[ApiSynchronizeStatus]    = Schema.derived[ApiSynchronizeStatus].name(Schema.SName("SynchronizeStatus"))
}
