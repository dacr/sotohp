package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.model.{BoundingBox, FaceId}
import fr.janalyse.sotohp.service.json.{*, given}
import io.scalaland.chimney.*
import io.scalaland.chimney.dsl.*
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.encodedName
import zio.json.*

case class ApiBoundingBox(
  x: Double,
  y: Double,
  width: Double,
  height: Double
)

object ApiBoundingBox {
  given JsonCodec[ApiBoundingBox] = DeriveJsonCodec.gen

  given apiBoundingBoxSchema: Schema[ApiBoundingBox] = Schema.derived[ApiBoundingBox].name(Schema.SName("BoundingBox"))

  implicit val boundingBoxTransformer: Transformer[BoundingBox, ApiBoundingBox] =
    Transformer
      .define[BoundingBox, ApiBoundingBox]
      .withFieldComputed(_.x, _.x.value)
      .withFieldComputed(_.y, _.y.value)
      .withFieldComputed(_.width, _.width.value)
      .withFieldComputed(_.height, _.height.value)
      .buildTransformer
}

case class ApiFace(
  originalId: OriginalId,
  faceId: FaceId,
  box: ApiBoundingBox
)

object ApiFace {
  given JsonCodec[ApiFace] = DeriveJsonCodec.gen

  given apiFaceSchema: Schema[ApiFace] = Schema.derived[ApiFace].name(Schema.SName("Face"))
}
