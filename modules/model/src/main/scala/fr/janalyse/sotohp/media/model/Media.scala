package fr.janalyse.sotohp.media.model

import wvlet.airframe.ulid.ULID

opaque type MediaAccessKey   = ULID
opaque type MediaDescription = String

object MediaAccessKey {
  def apply(id: ULID): MediaAccessKey = id
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
  starred: Boolean,
  keywords: Set[Keyword],
  orientation: Option[Orientation], // override original's orientation
  shootDateTime: Option[ShootDateTime], // override original's cameraShotDateTime
  location: Option[Location], // replace the original's location (user-defined or deducted location)
)
