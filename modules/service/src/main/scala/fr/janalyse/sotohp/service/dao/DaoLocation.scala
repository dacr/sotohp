package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}
import zio.lmdb.schema.LMDBSchema

case class DaoLocation(
  latitude: LatitudeDecimalDegrees,
  longitude: LongitudeDecimalDegrees,
  altitude: Option[AltitudeMeanSeaLevel]
) derives LMDBCodecJson, LMDBSchema
