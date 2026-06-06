package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service
import io.scalaland.chimney.Transformer
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}
import zio.lmdb.schema.LMDBSchema

import java.net.URL
import scala.util.Try

case class DaoBagAttachment(
  storeId: StoreId,
  bagMediaDirectory: BagMediaDirectory
) derives LMDBCodecJson, LMDBSchema

object DaoBagAttachment {
  given Transformer[BagAttachment, DaoBagAttachment] =
    Transformer
      .define[BagAttachment, DaoBagAttachment]
      .withFieldComputed(_.storeId, _.store.id)
      .buildTransformer
}

case class DaoBag(
  id: BagId,
  attachment: DaoBagAttachment,
  name: BagName,
  description: Option[BagDescription],
  location: Option[DaoLocation],
  timestamp: Option[ShootDateTime],
  originalId: Option[OriginalId],
  publishedOn: Option[String],
  keywords: Set[Keyword]
) derives LMDBCodecJson

object DaoBag {
  given Transformer[Bag, DaoBag] =
    Transformer
      .define[Bag, DaoBag]
      .withFieldComputed(_.publishedOn, _.publishedOn.map(_.toString))
      .buildTransformer
}
