package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.media.model.*
import zio.lmdb.json.LMDBCodecJson

case class DaoState(
  originalId: OriginalId,
  originalHash: Option[OriginalHash],
  originalAddedOn: AddedOn,
  originalLastChecked: LastChecked,
  mediaAccessKey: Option[MediaAccessKey],
  mediaLastSynchronized: Option[LastSynchronized]
) derives LMDBCodecJson
