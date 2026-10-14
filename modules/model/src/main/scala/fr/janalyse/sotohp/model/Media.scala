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
  deductedLocation: Option[Location],    // location deducted from near-by (time, space) localized photos
  userDefinedPlace: Option[Place],       // replace the deducted place (user-defined or corrected textual place)
  deductedPlace: Option[Place]           // textual place (town/region/country) deduced from the effective location
) {
  def timestamp: OffsetDateTime = Media.computeTimestamp(shootDateTime, bag, original)

  def place: Option[Place] = userDefinedPlace.orElse(deductedPlace)

  // Each source is checked on its own, so a legacy (0, 0) point never hides a valid fallback.
  def location: Option[Location] =
    userDefinedLocation
      .filter(_.isValid)
      .orElse(deductedLocation.filter(_.isValid))
      .orElse(original.location.filter(_.isValid))
      .orElse(bag.flatMap(_.location).filter(_.isValid))

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
