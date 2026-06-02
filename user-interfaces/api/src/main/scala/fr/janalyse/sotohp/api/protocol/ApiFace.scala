package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.{*, given}
import io.scalaland.chimney.*
import io.scalaland.chimney.dsl.*
import sttp.tapir.Schema

case class ApiBoundingBox(
  x: Double,
  y: Double,
  width: Double,
  height: Double
)

object ApiBoundingBox {
  given JsonValueCodec[ApiBoundingBox] = JsonCodecMaker.make

  given apiBoundingBoxSchema: Schema[ApiBoundingBox] = Schema.derived[ApiBoundingBox].name(Schema.SName("BoundingBox"))

  implicit val boundingBoxTransformer: Transformer[BoundingBox, ApiBoundingBox] =
    Transformer
      .define[BoundingBox, ApiBoundingBox]
      .withFieldComputed(_.x, _.x.value)
      .withFieldComputed(_.y, _.y.value)
      .withFieldComputed(_.width, _.width.value)
      .withFieldComputed(_.height, _.height.value)
      .buildTransformer

  implicit val boundingBoxTransformerReverted: Transformer[ApiBoundingBox, BoundingBox] =
    Transformer
      .define[ApiBoundingBox, BoundingBox]
      .withFieldComputed(_.x, v => XAxis.apply(v.x))
      .withFieldComputed(_.y, v => YAxis.apply(v.y))
      .withFieldComputed(_.width, v => BoxWidth.apply(v.width))
      .withFieldComputed(_.height, v => BoxHeight.apply(v.height))
      .buildTransformer
}

case class ApiFace(
  originalId: OriginalId,
  faceId: FaceId,
  box: ApiBoundingBox
)

object ApiFace {
  given JsonValueCodec[ApiFace] = JsonCodecMaker.make

  given apiFaceSchema: Schema[ApiFace] = Schema.derived[ApiFace].name(Schema.SName("Face"))
}
