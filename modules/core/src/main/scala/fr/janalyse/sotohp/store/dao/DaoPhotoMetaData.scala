package fr.janalyse.sotohp.store.dao
import zio.json.*

import java.time.OffsetDateTime

case class DaoPhotoMetaData(
  dimension: Option[DaoDimension2D],
  shootDateTime: Option[OffsetDateTime],
  orientation: Option[Int],
  cameraName: Option[String],
  tags: Map[String, String]
) derives JsonCodec
