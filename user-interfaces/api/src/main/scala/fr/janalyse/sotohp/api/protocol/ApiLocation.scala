package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema

case class ApiLocation(
  latitude: LatitudeDecimalDegrees,
  longitude: LongitudeDecimalDegrees,
  altitude: Option[AltitudeMeanSeaLevel]
)

object ApiLocation {
  given JsonValueCodec[ApiLocation] = JsonCodecMaker.make
  given Schema[ApiLocation]         = Schema.derived[ApiLocation].name(Schema.SName("Location"))
}
