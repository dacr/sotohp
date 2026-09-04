package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.OriginalId
import fr.janalyse.sotohp.model.{FaceId, PersonId}
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema

import java.time.OffsetDateTime

case class ApiDetectedFace(
  faceId: FaceId,
  originalId: OriginalId,
  box: ApiBoundingBox,
  identifiedPersonId: Option[PersonId],
  inferredIdentifiedPersonId: Option[PersonId],
  inferredIdentifiedPersonConfidence: Option[Double],
  inferredTimestamp: Option[OffsetDateTime],
  inferredIgnore: Option[Boolean],
  timestamp: OffsetDateTime
)

object ApiDetectedFace {
  given JsonValueCodec[ApiDetectedFace] = JsonCodecMaker.make

  given apiDetectedFaceSchema: Schema[ApiDetectedFace] = Schema.derived[ApiDetectedFace].name(Schema.SName("DetectedFace"))
}
