package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.{Original, OriginalId}
import fr.janalyse.sotohp.model.{FaceId}
import fr.janalyse.sotohp.processor.model.{OriginalFaces}
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema
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

  given JsonValueCodec[ApiOriginalFaces] = JsonCodecMaker.make

  given apiDetectedSchema: Schema[ApiOriginalFaces] = Schema.derived[ApiOriginalFaces].name(Schema.SName("OriginalFaces"))
}
