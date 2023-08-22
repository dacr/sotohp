package fr.janalyse.sotohp.search.dao

import zio.json.JsonCodec
import fr.janalyse.sotohp.model.Photo

import java.time.OffsetDateTime

// SearchAccessObject GeoPoint
case class SaoGeoPoint(
  lat: Double,
  lon: Double
) derives JsonCodec

// SearchAccessObject Photo
case class SaoPhoto(
  id: String,
  timestamp: OffsetDateTime,
  filePath: String,
  fileSize: Long,
  fileHash: String,
  fileLastUpdated: OffsetDateTime,
  category: Option[String],
  shootDateTime: Option[OffsetDateTime],
  camera: Option[String],
  // tags: Map[String, String],
  keywords: List[String],
  classifications: List[String],
  detectedObjects: List[String],
  place: Option[SaoGeoPoint]
) derives JsonCodec

object SaoPhoto {
  def fromPhoto(photo: Photo): SaoPhoto = {
    SaoPhoto(
      id = photo.source.photoId.id.toString,
      timestamp = photo.timestamp,
      filePath = photo.source.original.path.toString,
      fileSize = photo.source.fileSize,
      fileHash = photo.source.fileHash.code,
      fileLastUpdated = photo.source.fileLastModified,
      category = photo.category.map(_.text),
      shootDateTime = photo.metaData.flatMap(_.shootDateTime),
      camera = photo.metaData.flatMap(_.cameraName),
      // tags = photo.metaData.map(_.tags).getOrElse(Map.empty),
      keywords = photo.description.map(_.keywords.toList).getOrElse(Nil),
      classifications = photo.foundClassifications.map(_.classifications.map(_.name).distinct).getOrElse(Nil),
      detectedObjects = photo.foundObjects.map(_.objects.map(_.name).distinct).getOrElse(Nil),
      place = photo.place.map(place => SaoGeoPoint(lat = place.latitude.doubleValue, lon = place.longitude.doubleValue))
    )
  }
}
