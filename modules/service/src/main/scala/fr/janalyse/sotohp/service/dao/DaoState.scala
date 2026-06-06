package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}
import zio.lmdb.schema.LMDBSchema

case class DaoState(
  originalId: OriginalId,
  originalHash: Option[OriginalHash],
  originalAddedOn: AddedOn,
  originalLastChecked: LastChecked,
  mediaLastSynchronized: Option[LastSynchronized]
) derives LMDBCodecJson, LMDBSchema
