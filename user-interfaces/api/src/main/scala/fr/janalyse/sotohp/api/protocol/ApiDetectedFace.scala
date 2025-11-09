package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.OriginalId
import fr.janalyse.sotohp.processor.model.{FaceId, PersonId}
import fr.janalyse.sotohp.service.json.{*, given}
import zio.json.{DeriveJsonCodec, JsonCodec, jsonHint}
import sttp.tapir.Schema

import java.time.OffsetDateTime

case class ApiDetectedFace(
  faceId: FaceId,
  originalId: OriginalId,
  box: ApiBoundingBox,
  identifiedPersonId: Option[PersonId],
  inferredIdentifiedPersonId: Option[PersonId],
  timestamp: OffsetDateTime
)

object ApiDetectedFace {
  given JsonCodec[ApiDetectedFace] = DeriveJsonCodec.gen

  given apiDetectedFaceSchema: Schema[ApiDetectedFace] = Schema.derived[ApiDetectedFace].name(Schema.SName("DetectedFace"))
}
