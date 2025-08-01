package fr.janalyse.sotohp.model

import java.time.OffsetDateTime

case class Media(
  accessKey: MediaAccessKey,
  original: Original,
  events: List[Event],
  description: Option[MediaDescription],
  starred: Starred,
  keywords: Set[Keyword],
  orientation: Option[Orientation],     // override original's orientation
  shootDateTime: Option[ShootDateTime], // override original's cameraShotDateTime
  location: Option[Location]            // replace the original's location (user-defined or deducted location)
) {
  def timestamp: OffsetDateTime =
    shootDateTime
      .map(_.offsetDateTime)
      .orElse(original.cameraShootDateTime.map(_.offsetDateTime))
      .getOrElse(original.fileLastModified.offsetDateTime)
}
