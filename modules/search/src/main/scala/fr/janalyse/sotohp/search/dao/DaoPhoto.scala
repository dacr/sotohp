package fr.janalyse.sotohp.search.dao

import zio.json.JsonCodec

import java.time.OffsetDateTime

case class GeoPoint(
  lat: Double,
  lon: Double
) derives JsonCodec

case class Photo(
  // uuid: UUID,
  id: String,
  timestamp: OffsetDateTime,
  filePath: String,
  fileSize: Long,
  fileHash: String,
  fileLastUpdated: OffsetDateTime,
  category: Option[String],
  shootDateTime: Option[OffsetDateTime],
  camera: Option[String],
  tags: Map[String, String],
  keywords: List[String],
  classifications: List[String],
  detectedObjects: List[String],
  place: Option[GeoPoint]
) derives JsonCodec
