package fr.janalyse.sotohp.media.model

import scala.util.Try

case class Location(
  latitude: LatitudeDecimalDegrees,
  longitude: LongitudeDecimalDegrees,
  altitude: Option[AltitudeMeanSeaLevel]
)

object Location {
  def apply(
    latitudeDMS: LatitudeDegreeMinuteSeconds,
    longitudeDMS: LongitudeDegreeMinuteSeconds,
    altitudeMeanSeaLevel: Option[AltitudeMeanSeaLevel] = None
  ): Location = {
    Location(
      latitudeDMS.toDecimalDegrees,
      longitudeDMS.toDecimalDegrees,
      altitudeMeanSeaLevel
    )
  }

  def fromDecimalDegrees(
    latitudeDMS: LatitudeDecimalDegrees,
    longitudeDMS: LongitudeDecimalDegrees,
    altitudeMeanSeaLevel: Option[AltitudeMeanSeaLevel] = None
  ): Location = {
    Location(
      latitudeDMS,
      longitudeDMS,
      altitudeMeanSeaLevel
    )
  }

  def fromLocationSpecs(
    latitudeSpec: String,
    longitudeSpec: String,
    altitudeMeanSeaLevel: Option[AltitudeMeanSeaLevel] = None,
    deducted: Boolean = false
  ): Try[Location] = {
    for {
      latitudeDMS  <- LatitudeDegreeMinuteSeconds.fromSpec(latitudeSpec)
      longitudeDMS <- LongitudeDegreeMinuteSeconds.fromSpec(longitudeSpec)
    } yield Location(
      latitudeDMS,
      longitudeDMS,
      altitudeMeanSeaLevel
    )
  }

  extension (from: Location) {
    def distanceTo(to: Location): Double = {
      val earthRadius    = 6371000d
      val deltaLatitude  = to.latitude.toRadians - from.latitude.toRadians
      val deltaLongitude = to.longitude.toRadians - from.longitude.toRadians

      val a =
        Math.sin(deltaLatitude / 2) * Math.sin(deltaLatitude / 2) +
          Math.sin(deltaLongitude / 2) * Math.sin(deltaLongitude / 2) *
          Math.cos(from.latitude.toRadians) *
          Math.cos(to.latitude.toRadians)

      val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

      earthRadius * c
    }
  }

}
