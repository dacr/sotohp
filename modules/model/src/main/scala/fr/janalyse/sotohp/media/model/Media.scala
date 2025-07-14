package fr.janalyse.sotohp.media.model

import wvlet.airframe.ulid.ULID

opaque type MediaAccessKey = ULID
object MediaAccessKey {
  def apply(id: ULID): MediaAccessKey = id
}

opaque type MediaDescription = String
object MediaDescription {
  def apply(description: String): MediaDescription = description
  extension (mediaDescription: MediaDescription) {
    def text: String = mediaDescription
  }
}

opaque type Starred = Boolean
object Starred {
  def apply(starred: Boolean): Starred = starred
  extension (starred: Starred) {
    def value: Boolean = starred
  }
}

enum MediaKind(code: Int) {
  case Photo extends MediaKind(0)
  case Video extends MediaKind(1)
}

case class Media(
  accessKey: MediaAccessKey,
  kind: MediaKind,
  original: Original,
  event: Option[Event],
  description: Option[MediaDescription],
  starred: Starred,
  keywords: Set[Keyword],
  orientation: Option[Orientation],     // override original's orientation
  shootDateTime: Option[ShootDateTime], // override original's cameraShotDateTime
  location: Option[Location]            // replace the original's location (user-defined or deducted location)
)
