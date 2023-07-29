package fr.janalyse.sotohp.store.dao
import zio.json.*

import java.time.OffsetDateTime
import java.util.UUID

case class DaoPhoto(
  id: UUID,
  timestamp: OffsetDateTime,
  source: DaoPhotoSource,
  metaData: Option[DaoPhotoMetaData],
  category: Option[String],
  place: Option[DaoGeoPoint]
) derives JsonCodec
