package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.{Dimension, Original, OriginalId}
import fr.janalyse.sotohp.processor.model.OriginalMiniatures
import fr.janalyse.sotohp.service
import io.scalaland.chimney.Transformer
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}
import zio.lmdb.schema.LMDBSchema

case class DaoOriginalMiniature(
  size: Int,
  dimension: DaoDimension
) derives LMDBCodecJson, LMDBSchema

case class DaoOriginalMiniatures(
  originalId: OriginalId,
  status: DaoProcessedStatus,
  miniatures: Map[Int, DaoOriginalMiniature]
) derives LMDBCodecJson, LMDBSchema

object DaoOriginalMiniatures {
  given Transformer[OriginalMiniatures, DaoOriginalMiniatures] =
    Transformer
      .define[OriginalMiniatures, DaoOriginalMiniatures]
      .withFieldComputed(_.originalId, _.original.id)
      .buildTransformer

}
