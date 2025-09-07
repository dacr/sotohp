package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service
import io.scalaland.chimney.Transformer
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{given,*}

case class DaoMedia(
  accessKey: MediaAccessKey,
  originalId: OriginalId,
  events: Set[EventId],
  description: Option[MediaDescription],
  starred: Starred,
  keywords: Set[Keyword],
  orientation: Option[Orientation],         // override original's orientation
  shootDateTime: Option[ShootDateTime],     // override original's cameraShotDateTime
  userDefinedLocation: Option[DaoLocation], // replace the original's location (user-defined or deducted location)
  deductedLocation: Option[DaoLocation]
) derives LMDBCodecJson

object DaoMedia {
  given transformer:Transformer[Media, DaoMedia] =
    Transformer
      .define[Media, DaoMedia]
      .withFieldComputed(_.events, _.events.map(_.id).toSet)
      .withFieldComputed(_.originalId, _.original.id)
      .buildTransformer
}
