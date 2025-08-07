package fr.janalyse.sotohp.search.sao

import zio.json.JsonCodec
import fr.janalyse.sotohp.model.{Media, State}
import fr.janalyse.sotohp.search.model.MediaBag

import java.time.OffsetDateTime
import scala.util.matching.Regex

// SearchAccessObject GeoPoint
case class SaoGeoPoint(
  lat: Double,
  lon: Double
) derives JsonCodec

// SearchAccessObject Photo
case class SaoMedia(
  id: String,                // MediaAccessKey
  timestamp: OffsetDateTime, // Media timestamp, allowing changes by the users
  originalId: String,
  // ----------------- ORIGINAL FILE INFO -----------------
  fileSize: Long,
  filePath: String,
  // ----------------- USER DATA -----------------
  event: Option[String],     // the default attached event
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
  detectedFacesCount: Int,
  hasProcessingIssue: Boolean
) derives JsonCodec

object SaoMedia {

  def fromMedia(bag: MediaBag): SaoMedia = {
    import bag.media
    val event =
      media.events
        .find(_.attachment.isDefined)
        .map(_.name.text)

    val keywords           = media.keywords ++ media.events.flatMap(_.keywords)
    val location           = media.location.orElse(media.original.location)
    val hasProcessingIssue = (
      bag.processedObjects.exists(_.status.successful == false) ||
        bag.processedFaces.exists(_.status.successful == false) ||
        bag.processedClassifications.exists(_.status.successful == false) ||
        bag.processedNormalized.exists(_.status.successful == false) ||
        bag.processedMiniatures.exists(_.status.successful == false)
    )
    SaoMedia(
      id = media.accessKey.asString,
      timestamp = media.timestamp,
      originalId = media.original.id.asString,
      // ----------------- ORIGINAL FILE INFO -----------------
      fileSize = media.original.fileSize.value,
      filePath = media.original.mediaPath.path.toString,
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
      classifications = bag.processedClassifications.map(_.classifications.map(_.name)).getOrElse(Nil),
      detectedObjects = bag.processedObjects.map(_.objects.map(_.name)).getOrElse(Nil),
      detectedObjectsCount = bag.processedObjects.map(_.objects.size).getOrElse(0),
      detectedFacesCount = bag.processedFaces.map(_.faces.size).getOrElse(0),
      hasProcessingIssue = hasProcessingIssue
    )
  }
}
