package fr.janalyse.sotohp.model

import java.time.OffsetDateTime

case class Media(
  original: Original,
  bag: Option[Bag],
  description: Option[MediaDescription],
  starred: Starred,
  keywords: Set[Keyword],
  orientation: Option[Orientation],      // override original's orientation
  shootDateTime: Option[ShootDateTime],  // override original's cameraShotDateTime
  userDefinedLocation: Option[Location], // replace the original's location (user-defined or deducted location)
  deductedLocation: Option[Location]     // location deducted from near-by (time, space) localized photos
) {
  def timestamp: OffsetDateTime = Media.computeTimestamp(shootDateTime, bag, original)

  def location: Option[Location] =
    userDefinedLocation
      .orElse(deductedLocation)
      .orElse(original.location)
      .orElse(bag.flatMap(_.location))
      .filter(l => l.latitude.doubleValue != 0d && l.longitude.doubleValue != 0d) // TODO fix location data

  def allKeywords: Set[Keyword] = keywords ++ bag.toList.flatMap(_.keywords)
}

object Media {
  def computeTimestamp(mediaShootDateTime: Option[ShootDateTime], bag: Option[Bag], original: Original): OffsetDateTime = {
    mediaShootDateTime
      .orElse(original.cameraShootDateTime)
      .orElse(bag.flatMap(_.timestamp))
      .map(_.offsetDateTime)
      .getOrElse(original.fileLastModified.offsetDateTime)
  }
}
