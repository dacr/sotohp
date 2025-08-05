package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.{Dimension, Original, OriginalId}
import fr.janalyse.sotohp.processor.model.OriginalMiniatures
import io.scalaland.chimney.Transformer
import zio.lmdb.json.LMDBCodecJson

case class DaoOriginalMiniature(
  size: Int,
  dimension: DaoDimension
) derives LMDBCodecJson

case class DaoOriginalMiniatures(
  originalId: OriginalId,
  status: DaoProcessedStatus,
  miniatures: Map[Int, DaoOriginalMiniature]
) derives LMDBCodecJson

object DaoOriginalMiniatures {
  given Transformer[OriginalMiniatures, DaoOriginalMiniatures] =
    Transformer
      .define[OriginalMiniatures, DaoOriginalMiniatures]
      .withFieldComputed(_.originalId, _.original.id)
      .buildTransformer

}
