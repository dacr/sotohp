package fr.janalyse.sotohp.model

import java.time.OffsetDateTime

case class Media(
  accessKey: MediaAccessKey,
  original: Original,
  events: List[Event],
  description: Option[MediaDescription],
  starred: Starred,
  keywords: Set[Keyword],
  orientation: Option[Orientation],      // override original's orientation
  shootDateTime: Option[ShootDateTime],  // override original's cameraShotDateTime
  userDefinedLocation: Option[Location], // replace the original's location (user-defined or deducted location)
  deductedLocation: Option[Location] // location deducted from near-by (time, space) localized photos
) {
  def timestamp: OffsetDateTime =
    shootDateTime
      .map(_.offsetDateTime)
      .getOrElse(original.timestamp)

  def location: Option[Location] =
    userDefinedLocation
      .orElse(deductedLocation)
      .orElse(original.location)
      .filter(l => l.latitude.doubleValue != 0d && l.longitude.doubleValue != 0d) // TODO fix location data
}
