package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.{Original, OriginalId}
import fr.janalyse.sotohp.processor.model.OriginalClassifications
import io.scalaland.chimney.Transformer
import zio.lmdb.json.LMDBCodecJson

case class DaoDetectedClassification(
  name: String,
  probability: Double
) derives LMDBCodecJson

case class DaoOriginalClassifications(
  originalId: OriginalId,
  status: DaoProcessedStatus,
  classifications: List[DaoDetectedClassification]
) derives LMDBCodecJson

object DaoOriginalClassifications {
  given Transformer[OriginalClassifications, DaoOriginalClassifications] =
    Transformer
      .define[OriginalClassifications, DaoOriginalClassifications]
      .withFieldComputed(_.originalId, _.original.id)
      .buildTransformer
}
