package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{Event, Keyword, Location, MediaAccessKey, MediaDescription, Orientation, Original, ShootDateTime, Starred}
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema
import zio.json.JsonCodec

case class ApiMedia(
  accessKey: MediaAccessKey,
  original: ApiOriginal,
  events: List[ApiEvent],
  description: Option[MediaDescription],
  starred: Starred,
  keywords: Set[Keyword],
  orientation: Option[Orientation],      // override original's orientation
  shootDateTime: Option[ShootDateTime],  // override original's cameraShotDateTime
  userDefinedLocation: Option[ApiLocation], // replace the original's location (user-defined or deducted location)
  deductedLocation: Option[ApiLocation] // location deducted from near-by (time, space) localized photos
) derives JsonCodec
