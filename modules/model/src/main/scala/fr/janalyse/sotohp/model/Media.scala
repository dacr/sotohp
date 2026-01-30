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
      .orElse(original.cameraShootDateTime)
      .orElse(events.find(_.attachment.isDefined).flatMap(_.timestamp))
      .map(_.offsetDateTime)
      .getOrElse(original.fileLastModified.offsetDateTime)

  def location: Option[Location] =
    userDefinedLocation
      .orElse(deductedLocation)
      .orElse(original.location)
      .orElse(events.find(_.location.isDefined).flatMap(_.location))
      .filter(l => l.latitude.doubleValue != 0d && l.longitude.doubleValue != 0d) // TODO fix location data

  def rebuildAccessKey: MediaAccessKey = {
    //val timestamp = original.timestamp
    // TODO Migrate to something else as ULID are constrained to start from epoch ! 1947:07:01 15:00:00 +00:00 => -710154000000 for epoch millis !
    //val epoch = if (timestamp.getYear >= 1970) Try(timestamp.toInstant.toEpochMilli).toOption.getOrElse(0L) else 0L
    //val ulid = ULID.ofMillis(epoch)
    MediaAccessKey(timestamp, original.id.asUUID)
  }
  def allKeywords: Set[Keyword] = keywords ++ events.flatMap(_.keywords)
}
