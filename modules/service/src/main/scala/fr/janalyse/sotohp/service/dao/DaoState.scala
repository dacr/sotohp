package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.*
import zio.lmdb.json.LMDBCodecJson

case class DaoState(
  originalId: OriginalId,
  originalHash: Option[OriginalHash],
  originalAddedOn: AddedOn,
  originalLastChecked: LastChecked,
  mediaAccessKey: MediaAccessKey,
  mediaLastSynchronized: Option[LastSynchronized]
) derives LMDBCodecJson
