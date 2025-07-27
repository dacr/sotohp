package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.media.model.*
import zio.lmdb.json.LMDBCodecJson

case class DaoMedia(
  accessKey: MediaAccessKey,
  kind: MediaKind,
  originalId: OriginalId,
  events: Set[EventId],
  description: Option[MediaDescription],
  starred: Starred,
  keywords: Set[Keyword],
  orientation: Option[Orientation],     // override original's orientation
  shootDateTime: Option[ShootDateTime], // override original's cameraShotDateTime
  location: Option[DaoLocation]         // replace the original's location (user-defined or deducted location)
) derives LMDBCodecJson
