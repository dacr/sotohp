package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.{*, given}
import zio.lmdb.json.LMDBCodecJson
import zio.lmdb.schema.LMDBSchema

case class DaoAsset(
  originalId: OriginalId,
  selectedBox: Option[DaoBoundingBox],
  description: Option[AssetDescription]
) derives LMDBCodecJson, LMDBSchema
