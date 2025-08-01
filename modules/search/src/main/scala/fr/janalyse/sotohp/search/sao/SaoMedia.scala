package fr.janalyse.sotohp.search.sao

import zio.json.JsonCodec
import fr.janalyse.sotohp.model.{Media, State}

import java.time.OffsetDateTime
import scala.util.matching.Regex

// SearchAccessObject GeoPoint
case class SaoGeoPoint(
  lat: Double,
  lon: Double
) derives JsonCodec

// SearchAccessObject Photo
case class SaoMedia(
  id: String,
  timestamp: OffsetDateTime,
  // ----------------- ORIGINAL FILE INFO -----------------
  filePath: String,
  fileSize: Long,
  fileLastUpdated: OffsetDateTime,
  fileHash: Option[String],
  // ----------------- USER DATA -----------------
  event: Option[String], // the default attached event
  keywords: List[String],
  description: Option[String],
  // ----------------- CAMERA DATA -----------------
  shootDateTime: Option[OffsetDateTime],
  camera: Option[String],
  artist: Option[String],
  aperture: Option[String],
  exposureTime: Option[String],
  iso: Option[Double],
  focalLength: Option[String],
  // ----------------- GPS -----------------
  place: Option[SaoGeoPoint],
  placeAltitude: Option[Double],
  placeDeducted: Option[Boolean],
  // ----------------- AI -----------------
  classifications: List[String],
  detectedObjects: List[String],
  detectedObjectsCount: Int,
  detectedFacesCount: Int
) derives JsonCodec

object SaoMedia {

  def fromMedia(state: State, media: Media): SaoMedia = {
    val event =
      media.events
        .find(_.attachment.isDefined)
        .map(_.name.text)

    val keywords = media.keywords ++ media.events.flatMap(_.keywords)
    val location = media.location.orElse(media.original.location)
    SaoMedia(
      id = state.mediaAccessKey.asString,
      timestamp = media.timestamp,
      // ----------------- ORIGINAL FILE INFO -----------------
      filePath = media.original.mediaPath.toString,
      fileSize = media.original.fileSize.value,
      fileLastUpdated = media.original.fileLastModified.offsetDateTime,
      fileHash = state.originalHash.map(_.code),
      // ----------------- USER DATA -----------------
      event = event,
      keywords = keywords.map(_.text).toList,
      description = media.description.map(_.text),
      // ----------------- CAMERA DATA -----------------
      shootDateTime = media.original.cameraShootDateTime.map(_.offsetDateTime),
      camera = media.original.cameraName.map(_.text),
      artist = media.original.artistInfo.map(_.artist),
      aperture = media.original.aperture.map(_.sexy),
      exposureTime = media.original.exposureTime.map(_.sexy),
      iso = media.original.iso.map(_.selected),
      focalLength = media.original.focalLength.map(_.sexy),
      // ----------------- GPS -----------------
      place = location.map(place => SaoGeoPoint(lat = place.latitude.doubleValue, lon = place.longitude.doubleValue)),
      placeAltitude = location.flatMap(_.altitude.map(_.value)),
      placeDeducted = if (location.isDefined) Some(media.location.isDefined) else None,
      // ----------------- AI -----------------
      classifications = Nil,
      detectedObjects = Nil,
      detectedObjectsCount = 0,
      detectedFacesCount = 0
    )
  }
}
