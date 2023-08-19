package fr.janalyse.sotohp.model

import java.time.OffsetDateTime

case class PhotoMetaData(
  dimension: Option[Dimension2D],
  shootDateTime: Option[OffsetDateTime],
  orientation: Option[PhotoOrientation],
  cameraName: Option[String],
  tags: Map[String, String]
)
