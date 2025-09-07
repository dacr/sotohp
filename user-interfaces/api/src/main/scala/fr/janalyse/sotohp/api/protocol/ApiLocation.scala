package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.*
import zio.json.{DeriveJsonCodec, JsonCodec, jsonHint}
import zio.lmdb.json.LMDBCodecJson
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.encodedName

case class ApiLocation(
  latitude: LatitudeDecimalDegrees,
  longitude: LongitudeDecimalDegrees,
  altitude: Option[AltitudeMeanSeaLevel]
)

object ApiLocation {
  given JsonCodec[ApiLocation] = DeriveJsonCodec.gen
  given Schema[ApiLocation]    = Schema.derived[ApiLocation].name(Schema.SName("Location"))
}
