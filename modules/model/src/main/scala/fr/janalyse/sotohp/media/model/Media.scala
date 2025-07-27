package fr.janalyse.sotohp.media.model

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
)
