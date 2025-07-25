package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.media.model.*
import io.scalaland.chimney.Transformer
import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson

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
  keywords: Set[Keyword]
) derives LMDBCodecJson
