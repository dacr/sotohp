package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.*
import zio.json.JsonCodec
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{given, *}

case class ApiLocation(
  latitude: LatitudeDecimalDegrees,
  longitude: LongitudeDecimalDegrees,
  altitude: Option[AltitudeMeanSeaLevel]
) derives JsonCodec
