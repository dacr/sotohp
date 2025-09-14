package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service
import io.scalaland.chimney.Transformer
import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{given,*}

case class DaoEventAttachment(
  storeId: StoreId,
  eventMediaDirectory: EventMediaDirectory
) derives LMDBCodecJson

object DaoEventAttachment {
  given Transformer[EventAttachment, DaoEventAttachment] =
    Transformer.define[EventAttachment, DaoEventAttachment]
      .withFieldComputed(_.storeId, _.store.id)
      .buildTransformer

}

case class DaoEvent(
  id: EventId,
  attachment: Option[DaoEventAttachment], // for event based on a relative directory path within a given store
  name: EventName,
  description: Option[EventDescription],
  location: Option[DaoLocation],          // reference location for this event
  timestamp: Option[ShootDateTime],    // reference date time for this event,
  originalId: Option[OriginalId],      // reference/chosen original which will be shown when the event is displayed
  keywords: Set[Keyword]
) derives LMDBCodecJson
