package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.model.*
import fr.janalyse.sotohp.service
import io.scalaland.chimney.Transformer
import zio.lmdb.json.LMDBCodecJson

import java.nio.file.Path
import fr.janalyse.sotohp.service.json.{*, given}
import zio.lmdb.schema.LMDBSchema

case class DaoNormalized(
  dimension: DaoDimension,
  path: NormalizedPath
) derives LMDBCodecJson, LMDBSchema

case class DaoOriginalNormalized(
  originalId: OriginalId,
  status: DaoProcessedStatus,
  normalized: Option[DaoNormalized]
) derives LMDBCodecJson, LMDBSchema

object DaoOriginalNormalized {
  given Transformer[OriginalNormalized, DaoOriginalNormalized] =
    Transformer
      .define[OriginalNormalized, DaoOriginalNormalized]
      .withFieldComputed(_.originalId, _.original.id)
      .buildTransformer
}
