package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service
import io.scalaland.chimney.Transformer
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}

import java.time.OffsetDateTime

case class DaoMedia(
  originalId: OriginalId,
  events: List[EventId],
  description: Option[MediaDescription],
  starred: Starred,
  keywords: Set[Keyword],
  orientation: Option[Orientation],         // override original's orientation
  shootDateTime: Option[ShootDateTime],     // override original's cameraShotDateTime
  userDefinedLocation: Option[DaoLocation], // replace the original's location (user-defined or deducted location)
  deductedLocation: Option[DaoLocation],    // from nearby photos
  timestamp: OffsetDateTime                 // keep the original's computed timestamp as it is used for indexing purposes
) derives LMDBCodecJson

object DaoMedia {
  given transformer:Transformer[Media, DaoMedia] =
    Transformer
      .define[Media, DaoMedia]
      .withFieldComputed(_.events, _.events.map(_.id))
      .withFieldComputed(_.originalId, _.original.id)
      .withFieldComputed(_.timestamp, _.original.timestamp)
      .buildTransformer
}
