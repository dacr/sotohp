package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.{Original, OriginalId}
import fr.janalyse.sotohp.processor.model.OriginalClassifications
import fr.janalyse.sotohp.service
import io.scalaland.chimney.Transformer
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{given,*}

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
