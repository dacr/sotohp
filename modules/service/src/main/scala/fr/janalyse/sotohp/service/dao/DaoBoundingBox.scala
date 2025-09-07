package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.{Height, Width}
import fr.janalyse.sotohp.processor.model.{BoundingBox, BoxHeight, BoxWidth, XAxis, YAxis}
import fr.janalyse.sotohp.service
import io.scalaland.chimney.Transformer
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{given,*}

case class DaoBoundingBox(
  x: Double,
  y: Double,
  width: Double,
  height: Double
) derives LMDBCodecJson

object DaoBoundingBox {
  given Transformer[BoundingBox, DaoBoundingBox] =
    Transformer
      .define[BoundingBox, DaoBoundingBox]
      .withFieldComputed(_.x, _.x.value)
      .withFieldComputed(_.y, _.y.value)
      .withFieldComputed(_.width, _.width.value)
      .withFieldComputed(_.height, _.height.value)
      .buildTransformer

  given Transformer[DaoBoundingBox, BoundingBox] =
    Transformer
      .define[DaoBoundingBox, BoundingBox]
      .withFieldComputed(_.x, d => XAxis(d.x))
      .withFieldComputed(_.y, d => YAxis(d.y))
      .withFieldComputed(_.width, d => BoxWidth(d.width))
      .withFieldComputed(_.height, d => BoxHeight(d.height))
      .buildTransformer
}
