package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{given,*}

case class DaoState(
  originalId: OriginalId,
  originalHash: Option[OriginalHash],
  originalAddedOn: AddedOn,
  originalLastChecked: LastChecked,
  mediaAccessKey: MediaAccessKey,
  mediaLastSynchronized: Option[LastSynchronized]
) derives LMDBCodecJson
