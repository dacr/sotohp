package fr.janalyse.sotohp.media.model

type AltitudeMeanSeaLevel = Double // https://en.wikipedia.org/wiki/Sea_level

import fr.janalyse.sotohp.media.model.DecimalDegrees.*
import fr.janalyse.sotohp.media.model.DegreeMinuteSeconds.*

import scala.annotation.targetName
import scala.util.{Failure, Success, Try}

object DecimalDegrees {
  opaque type LatitudeDecimalDegrees  = Double // https://en.wikipedia.org/wiki/Decimal_degrees
  opaque type LongitudeDecimalDegrees = Double // https://en.wikipedia.org/wiki/Decimal_degrees

  object LatitudeDecimalDegrees  {
    def apply(value: Double): LatitudeDecimalDegrees = value
  }
  object LongitudeDecimalDegrees {
    def apply(value: Double): LongitudeDecimalDegrees = value
  }

  extension (dd: LatitudeDecimalDegrees) {
    @targetName("toDegreeMinuteSeconds_latitude")
    def toDegreeMinuteSeconds: LatitudeDegreeMinuteSeconds = ??? // https://en.wikipedia.org/wiki/Decimal_degrees
  }
  extension (dd: LongitudeDecimalDegrees) {
    @targetName("toDegreeMinuteSeconds_longitude")
    def toDegreeMinuteSeconds: LongitudeDegreeMinuteSeconds = ??? // https://en.wikipedia.org/wiki/Decimal_degrees
  }

  extension (dd: LatitudeDecimalDegrees) {
    def doubleValue: Double = dd
    def toRadians: Double   = dd.toRadians
  }
  extension (dd: LongitudeDecimalDegrees) {
    @targetName("doubleValue_longitude")
    def doubleValue: Double = dd
    @targetName("doubleValue_toRadians")
    def toRadians: Double   = dd.toRadians
  }

}

object DegreeMinuteSeconds {
  // TODO also support those notations "45/1 20/1 43377720/1000000 N" (latitude) and "6/1 37/1 1979399/1000000 E" (longitude) see com.drewnoakes::metadata-extractor implementation
  val latitudeDmsRE  = """([-+]?\d+)°\s*([-+]?\d+)['′]\s*([-+]?\d+(?:[.,]\d+)?)(?:(?:")|(?:'')|(?:′′)|(?:″))\s+([NS])""".r
  val longitudeDmsRE = """([-+]?\d+)°\s*([-+]?\d+)['′]\s*([-+]?\d+(?:[.,]\d+)?)(?:(?:")|(?:'')|(?:′′)|(?:″))\s+([EW])""".r

  private def normalize(dmsFullSpec: String): String = {
    dmsFullSpec.trim
      .replaceAll("[,]", ".")
  }

  private def convert(d: String, m: String, s: String, ref: String) = {
    val dd =
      d.toDouble +
        m.toDouble / 60d +
        s.toDouble / 3600d
    if ("NE".contains(ref.toUpperCase)) dd else -dd
  }

  opaque type LatitudeDegreeMinuteSeconds  = String // https://en.wikipedia.org/wiki/Decimal_degrees
  opaque type LongitudeDegreeMinuteSeconds = String // https://en.wikipedia.org/wiki/Decimal_degrees

  object LatitudeDegreeMinuteSeconds {
    def fromSpec(dmsFullSpec: String): Try[LatitudeDegreeMinuteSeconds]             = {
      if (!DegreeMinuteSeconds.latitudeDmsRE.matches(dmsFullSpec))
        Failure(IllegalArgumentException(s"given DegreeMinuteSeconds latitude spec ($dmsFullSpec) is invalid"))
      else Success(normalize(dmsFullSpec))
    }
    def fromSpec(dmsSpec: String, dmsRef: String): Try[LatitudeDegreeMinuteSeconds] = {
      fromSpec(s"$dmsSpec $dmsRef")
    }

  }

  object LongitudeDegreeMinuteSeconds {
    def fromSpec(dmsFullSpec: String): Try[LongitudeDegreeMinuteSeconds]             = {
      if (!DegreeMinuteSeconds.longitudeDmsRE.matches(dmsFullSpec))
        Failure(IllegalArgumentException(s"given DegreeMinuteSeconds longitude spec ($dmsFullSpec) is invalid"))
      else Success(normalize(dmsFullSpec))
    }
    def fromSpec(dmsSpec: String, dmsRef: String): Try[LongitudeDegreeMinuteSeconds] = {
      fromSpec(s"$dmsSpec $dmsRef")
    }
  }

  extension (dms: LatitudeDegreeMinuteSeconds) {
    // def toLatitudeDecimalDegrees: LatitudeDecimalDegrees = dms match {
    @targetName("toDecimalDegrees_latitude")
    def toDecimalDegrees: LatitudeDecimalDegrees = dms match {
      case DegreeMinuteSeconds.latitudeDmsRE(d, m, s, ref) =>
        LatitudeDecimalDegrees(convert(d, m, s, ref))
    }
  }

  extension (dms: LongitudeDegreeMinuteSeconds) {
    // def toLongitudeDecimalDegrees: LongitudeDecimalDegrees = dms match {
    @targetName("toDecimalDegrees_longitude")
    def toDecimalDegrees: LongitudeDecimalDegrees = dms match {
      case DegreeMinuteSeconds.longitudeDmsRE(d, m, s, ref) =>
        LongitudeDecimalDegrees(convert(d, m, s, ref))
    }
  }
}

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
