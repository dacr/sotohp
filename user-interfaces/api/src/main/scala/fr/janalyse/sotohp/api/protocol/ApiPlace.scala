package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.tapir.Schema

/** Human-readable place of a media - town / region / country, reverse-geocoded from its GPS point
  * (`street` is only ever present on a user-defined place). Mirrors [[ApiLocation]].
  */
case class ApiPlace(
  street: Option[String],
  town: Option[String],
  region: Option[String],
  country: Option[String],
  countryCode: Option[String]
)

object ApiPlace {
  given JsonValueCodec[ApiPlace] = JsonCodecMaker.make
  given Schema[ApiPlace]         = Schema.derived[ApiPlace].name(Schema.SName("Place"))
}
