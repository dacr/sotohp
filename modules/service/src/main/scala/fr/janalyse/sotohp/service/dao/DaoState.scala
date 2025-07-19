package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.media.model.*
import zio.lmdb.json.LMDBCodecJson

case class DaoState(
  originalId: OriginalId,
  mediaAccessKey: MediaAccessKey,
  firstSeen: FirstSeen,
  lastChecked: LastChecked,
  lastSynchronized: Option[LastSynchronized]
) derives LMDBCodecJson
