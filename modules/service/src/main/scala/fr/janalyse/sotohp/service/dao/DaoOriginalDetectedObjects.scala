package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.{Original, OriginalId}
import fr.janalyse.sotohp.processor.model.OriginalDetectedObjects
import fr.janalyse.sotohp.service
import io.scalaland.chimney.Transformer
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{given,*}

case class DaoDetectedObject(
  name: String,
  probability: Double,
  box: DaoBoundingBox
) derives LMDBCodecJson

case class DaoOriginalDetectedObjects(
  originalId: OriginalId,
  status: DaoProcessedStatus,
  objects: List[DaoDetectedObject]
) derives LMDBCodecJson

object DaoOriginalDetectedObjects {
  given Transformer[OriginalDetectedObjects, DaoOriginalDetectedObjects] =
    Transformer
      .define[OriginalDetectedObjects, DaoOriginalDetectedObjects]
      .withFieldComputed(_.originalId, _.original.id)
      .buildTransformer
}