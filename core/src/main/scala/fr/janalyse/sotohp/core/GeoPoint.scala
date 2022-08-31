package fr.janalyse.sotohp.core

import zio.json.*

import scala.util.Try

case class GeoPoint(lat: Double, lon: Double)

object GeoPoint {
  given JsonCodec[GeoPoint] = DeriveJsonCodec.gen

  /*
  tags.gPSGPSLatitude : 45° 19' 19,29"
  tags.gPSGPSLatitudeRef : N
  tags.gPSGPSLongitude : 6° 32' 39,47"
  tags.gPSGPSLongitudeRef : E
   */

  val dmsRE = """[-+]?(\d+)[°]\s*(\d+)['′]\s*(\d+(?:[.,]\d+)?)(?:(?:")|(?:'')|(?:′′)|(?:″))""".r

  def degreesMinuteSecondsToDecimalDegrees(d: Double, m: Double, s: Double): Double = d + m / 60d + s / 3600d

  def degreesMinuteSecondsToDecimalDegrees(dms: String, ref: String): Try[Double] = Try {
    val dd = dms.trim match {
      case dmsRE(d, m, s) => degreesMinuteSecondsToDecimalDegrees(d.toDouble, m.toDouble, s.replaceAll(",", ".").toDouble)
    }
    if ("NE".contains(ref.trim.toUpperCase.head)) dd else -dd
  }

  def computeGeoPoint(photoTags: Map[String, String]): Option[GeoPoint] =
    // Degrees Minutes Seconds to Decimal Degrees
    for {
      latitude     <- photoTags.get("gPSGPSLatitude")
      latitudeRef  <- photoTags.get("gPSGPSLatitudeRef")
      longitude    <- photoTags.get("gPSGPSLongitude")
      longitudeRef <- photoTags.get("gPSGPSLongitudeRef")
      lat          <- degreesMinuteSecondsToDecimalDegrees(latitude, latitudeRef).toOption   // TODO enhance error processing
      lon          <- degreesMinuteSecondsToDecimalDegrees(longitude, longitudeRef).toOption // TODO enhance error processing
    } yield GeoPoint(lat, lon)
}
