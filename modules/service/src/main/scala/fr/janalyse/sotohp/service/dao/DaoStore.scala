package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}
import zio.lmdb.schema.LMDBSchema

case class DaoStore(
  id: StoreId,
  name: Option[StoreName],
  ownerId: OwnerId,
  baseDirectory: BaseDirectoryPath,
  includeMask: Option[IncludeMask],
  ignoreMask: Option[IgnoreMask]
) derives LMDBCodecJson, LMDBSchema
