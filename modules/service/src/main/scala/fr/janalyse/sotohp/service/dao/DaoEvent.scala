package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service
import io.scalaland.chimney.Transformer
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}

import java.net.URL
import scala.util.Try

case class DaoEventAttachment(
  storeId: StoreId,
  eventMediaDirectory: EventMediaDirectory
) derives LMDBCodecJson

object DaoEventAttachment {
  given Transformer[EventAttachment, DaoEventAttachment] =
    Transformer
      .define[EventAttachment, DaoEventAttachment]
      .withFieldComputed(_.storeId, _.store.id)
      .buildTransformer
}

case class DaoEvent(
  id: EventId,
  attachment: DaoEventAttachment,
  name: EventName,
  description: Option[EventDescription],
  location: Option[DaoLocation],
  timestamp: Option[ShootDateTime],
  originalId: Option[OriginalId],
  publishedOn: Option[String],
  keywords: Set[Keyword]
) derives LMDBCodecJson

object DaoEvent {
  given Transformer[Event, DaoEvent] =
    Transformer
      .define[Event, DaoEvent]
      .withFieldComputed(_.publishedOn, _.publishedOn.map(_.toString))
      .buildTransformer
}
