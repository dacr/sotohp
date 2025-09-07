package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.service
import zio.json.JsonCodec
import fr.janalyse.sotohp.service.json.{given,*}

case class DaoExposureTime(
  numerator: Long,
  denominator: Long
) derives JsonCodec
