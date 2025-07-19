package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.media.model.*
import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson

case class DaoStore(
                     id: StoreId,
                     ownerId: OwnerId,
                     baseDirectory: BaseDirectoryPath,
                     includeMask: Option[IncludeMask],
                     ignoreMask: Option[IgnoreMask]
) derives LMDBCodecJson
