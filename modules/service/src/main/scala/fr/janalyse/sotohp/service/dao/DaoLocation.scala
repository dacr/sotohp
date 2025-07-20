package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.media.model.*
import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson

case class DaoLocation(
  latitude: LatitudeDecimalDegrees,
  longitude: LongitudeDecimalDegrees,
  altitude: Option[AltitudeMeanSeaLevel]
) derives LMDBCodecJson
