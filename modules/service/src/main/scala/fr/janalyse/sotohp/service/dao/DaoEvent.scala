package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.media.model.*
import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson

case class DaoEvent(
  id: EventId,
  storeId: StoreId,
  mediaRelativeDirectory: EventMediaDirectory,
  name: EventName,
  description: Option[EventDescription],
  keywords: Set[Keyword]
) derives LMDBCodecJson
