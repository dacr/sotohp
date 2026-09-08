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
  id: String,                // originalId
  timestamp: OffsetDateTime, // Media timestamp, allowing changes by the users
  originalId: String,
  // ----------------- ORIGINAL FILE INFO -----------------
  fileSize: Long,
  filePath: String,
  fileHash: Option[String],
  // ----------------- USER DATA -----------------
  bag: Option[String],       // the default attached bag
  keywords: List[String],
  description: Option[String],
  autoDescription: Option[String], // model-generated image-to-text caption
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
  // ----------------- PLACE (reverse-geocoded from GPS) -----------------
  placeStreet: Option[String],
  placeTown: Option[String],
  placeRegion: Option[String],
  placeCountry: Option[String],
  placeCountryCode: Option[String],
  // ----------------- AI -----------------
  classifications: List[String],
  // Distinct detected object labels, joined by "; ".
  detectedObjects: String,
  detectedObjectsCount: Int,
  detectedFacesCount: Int,
  // People positively identified on the media's faces, one "lastName, firstName" entry per
  // person (blank parts dropped), entries joined by "; ". The person's free-text description is
  // deliberately left out - it is notes, not something to match photos on.
  identifiedPersons: String,
  hasProcessingIssue: Boolean
) derives JsonCodec

object SaoMedia {

  def fromMedia(bag: MediaBag): SaoMedia = {
    import bag.media
    val mediaBagName       = media.bag.map(_.name.text)
    val keywords           = media.keywords ++ media.bag.toList.flatMap(_.keywords)
    val location           = media.location
    val place              = media.place
    val hasProcessingIssue = (
      bag.processedObjects.exists(_.status.successful == false) ||
        bag.processedFaces.exists(_.status.successful == false) ||
        bag.processedClassifications.exists(_.status.successful == false) ||
        bag.processedNormalized.exists(_.status.successful == false) ||
        bag.processedMiniatures.exists(_.status.successful == false)
    )
    SaoMedia(
      id = media.original.id.asString,
      timestamp = media.timestamp,
      originalId = media.original.id.asString,
      // ----------------- ORIGINAL FILE INFO -----------------
      fileSize = media.original.fileSize.value,
      filePath = media.original.relativeMediaPath.toString,
      fileHash = bag.state.originalHash.map(_.code),
      // ----------------- USER DATA -----------------
      bag = mediaBagName,
      keywords = keywords.map(_.text).toList,
      description = media.description.map(_.text),
      autoDescription = bag.autoDescription.map(_.trim).filter(_.nonEmpty),
      // ----------------- CAMERA DATA -----------------
      shootDateTime = media.original.cameraShootDateTime.map(_.offsetDateTime),
      camera = media.original.cameraName.map(_.text),
      artist = media.original.artistInfo.map(_.artist),
      aperture = media.original.aperture.map(_.sexy),
      exposureTime = media.original.exposureTime.map(_.sexy),
      iso = media.original.iso.map(_.selected),
      focalLength = media.original.focalLength.map(_.sexy),
      // ----------------- GPS -----------------
      place = location.map(loc => SaoGeoPoint(lat = loc.latitude.doubleValue, lon = loc.longitude.doubleValue)),
      placeAltitude = location.flatMap(_.altitude.map(_.value)),
      placeDeducted = if (media.userDefinedLocation.isEmpty && media.deductedLocation.isDefined) Some(true) else None,
      placeStreet = place.flatMap(_.street),
      placeTown = place.flatMap(_.town),
      placeRegion = place.flatMap(_.region),
      placeCountry = place.flatMap(_.country),
      placeCountryCode = place.flatMap(_.countryCode),
      // ----------------- AI -----------------
      classifications = bag.processedClassifications.map(_.classifications.map(_.name)).getOrElse(Nil),
      detectedObjects = bag.processedObjects.map(_.objects.map(_.name).distinct.mkString("; ")).getOrElse(""),
      detectedObjectsCount = bag.processedObjects.map(_.objects.size).getOrElse(0),
      detectedFacesCount = bag.processedFaces.map(_.faces.size).getOrElse(0),
      identifiedPersons = bag.persons
        .map { person =>
          List(person.lastName.text, person.firstName.text)
            .map(_.trim)
            .filter(_.nonEmpty)
            .mkString(", ")
        }
        .mkString("; "),
      hasProcessingIssue = hasProcessingIssue
    )
  }
}
