package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.OriginalId
import fr.janalyse.sotohp.processor.model.{FaceId, PersonId}
import fr.janalyse.sotohp.service.json.{*, given}

import zio.json.{DeriveJsonCodec, JsonCodec, jsonHint}
import sttp.tapir.Schema

case class ApiDetectedFace(
  faceId: FaceId,
  originalId: OriginalId,
  box: ApiBoundingBox,
  identifiedPersonId: Option[PersonId]
)

object ApiDetectedFace {
  given JsonCodec[ApiDetectedFace] = DeriveJsonCodec.gen

  given apiDetectedFaceSchema: Schema[ApiDetectedFace] = Schema.derived[ApiDetectedFace].name(Schema.SName("DetectedFace"))
}
