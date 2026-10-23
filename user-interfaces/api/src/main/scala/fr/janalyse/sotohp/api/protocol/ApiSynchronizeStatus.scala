package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.MediaAccessKey
import fr.janalyse.sotohp.service.json.given
import fr.janalyse.sotohp.service.model.{SynchronizeOutcome, SynchronizePhase, SynchronizeStatus}
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.dsl.*
import sttp.tapir.Schema

import java.time.OffsetDateTime

case class ApiSynchronizeProblem(
  at: OffsetDateTime,
  message: String,
  path: Option[String]
)

object ApiSynchronizeProblem {
  given JsonValueCodec[ApiSynchronizeProblem] = JsonCodecMaker.make
  given Schema[ApiSynchronizeProblem]         = Schema.derived[ApiSynchronizeProblem].name(Schema.SName("SynchronizeProblem"))
}

case class ApiSynchronizeCurrentMedia(
  accessKey: MediaAccessKey,
  path: String
)

object ApiSynchronizeCurrentMedia {
  given JsonValueCodec[ApiSynchronizeCurrentMedia] = JsonCodecMaker.make
  given Schema[ApiSynchronizeCurrentMedia]         = Schema.derived[ApiSynchronizeCurrentMedia].name(Schema.SName("SynchronizeCurrentMedia"))
}

case class ApiSynchronizeRun(
  addedThoseLastDays: Option[Int],
  startedAt: OffsetDateTime,
  finishedAt: OffsetDateTime,
  outcome: String, // "completed" | "stopped" | "failed"
  checkedCount: Long,
  toProcessCount: Long,
  processedCount: Long,
  problemCount: Long,
  failure: Option[String]
)

object ApiSynchronizeRun {
  given JsonValueCodec[ApiSynchronizeRun] = JsonCodecMaker.make
  given Schema[ApiSynchronizeRun]         = Schema.derived[ApiSynchronizeRun].name(Schema.SName("SynchronizeRun"))
}

case class ApiSynchronizeStatus(
  running: Boolean,
  phase: String, // "idle" | "scanning" | "processing" | "stopping"
  addedThoseLastDays: Option[Int],
  startedAt: Option[OffsetDateTime],
  lastUpdated: Option[OffsetDateTime],
  storeCount: Int,
  storeIndex: Int,
  currentStore: Option[String],
  checkedCount: Long,
  unreadableCount: Long,
  toProcessCount: Long,
  processedCount: Long,
  indexedCount: Long,
  processingStartedAt: Option[OffsetDateTime],
  remainingSeconds: Option[Long],
  currentMedia: Option[ApiSynchronizeCurrentMedia],
  unavailableSteps: List[String],
  problemCount: Long,
  problems: List[ApiSynchronizeProblem],
  lastRun: Option[ApiSynchronizeRun]
)

object ApiSynchronizeStatus {
  given Transformer[SynchronizePhase, String]   = _.toString.toLowerCase
  given Transformer[SynchronizeOutcome, String] = _.toString.toLowerCase

  def fromStatus(status: SynchronizeStatus): ApiSynchronizeStatus = status.transformInto[ApiSynchronizeStatus]

  given JsonValueCodec[ApiSynchronizeStatus] = JsonCodecMaker.make
  given Schema[ApiSynchronizeStatus]         = Schema.derived[ApiSynchronizeStatus].name(Schema.SName("SynchronizeStatus"))
}
