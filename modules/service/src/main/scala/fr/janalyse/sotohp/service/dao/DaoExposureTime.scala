package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.service
import fr.janalyse.sotohp.service.json.{given, *}
import zio.lmdb.json.LMDBCodecJson

case class DaoExposureTime(
  numerator: Long,
  denominator: Long
) derives LMDBCodecJson
