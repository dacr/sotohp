package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{Original, OriginalId}
import fr.janalyse.sotohp.processor.model.{FaceId, OriginalFaces}
import fr.janalyse.sotohp.service.json.{*, given}
import zio.json.{DeriveJsonCodec, JsonCodec, jsonHint}
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.encodedName
import io.scalaland.chimney.Transformer

case class ApiOriginalFaces(
  originalId: OriginalId,
  facesIds: List[FaceId]
)

object ApiOriginalFaces {
  given apiOriginalFacesTransformer: Transformer[OriginalFaces, ApiOriginalFaces] =
    Transformer
      .define[OriginalFaces, ApiOriginalFaces]
      .withFieldComputed(_.facesIds, _.faces.map(_.faceId))
      .withFieldComputed(_.originalId, _.original.id)
      .buildTransformer

  given JsonCodec[ApiOriginalFaces] = DeriveJsonCodec.gen

  given apiDetectedSchema: Schema[ApiOriginalFaces] = Schema.derived[ApiOriginalFaces].name(Schema.SName("OriginalFaces"))
}
