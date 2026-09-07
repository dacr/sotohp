package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.OriginalId
import fr.janalyse.sotohp.processor.model.OriginalMediaFeatures
import fr.janalyse.sotohp.service
import io.scalaland.chimney.Transformer
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}
import zio.lmdb.schema.LMDBSchema

/** The whole-image embedding vector, keyed by `OriginalId`.
  *
  * `Array[Float]` round-trips through jsoniter as a JSON number array with no custom
  * codec (same as `DaoFaceFeatures`).
  */
case class DaoMediaFeatures(
  originalId: OriginalId,
  features: Array[Float]
) derives LMDBCodecJson, LMDBSchema

/** Per-photo "media features have been computed" marker (mirrors `DaoOriginalFaceFeatures`). */
case class DaoOriginalMediaFeatures(
  originalId: OriginalId,
  status: DaoProcessedStatus
) derives LMDBCodecJson, LMDBSchema

object DaoOriginalMediaFeatures {
  given Transformer[OriginalMediaFeatures, DaoOriginalMediaFeatures] =
    Transformer
      .define[OriginalMediaFeatures, DaoOriginalMediaFeatures]
      .withFieldComputed(_.originalId, _.original.id)
      .buildTransformer
}
