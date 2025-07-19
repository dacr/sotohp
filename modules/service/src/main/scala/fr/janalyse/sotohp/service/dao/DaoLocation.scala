package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.media.model.AltitudeMeanSeaLevel
import fr.janalyse.sotohp.media.model.DecimalDegrees.{LatitudeDecimalDegrees, LongitudeDecimalDegrees}
import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson

case class DaoLocation(
  latitude: LatitudeDecimalDegrees,
  longitude: LongitudeDecimalDegrees,
  altitude: Option[AltitudeMeanSeaLevel]
) derives LMDBCodecJson
