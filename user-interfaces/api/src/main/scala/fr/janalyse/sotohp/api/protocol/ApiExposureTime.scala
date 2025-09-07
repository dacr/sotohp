package fr.janalyse.sotohp.api.protocol

import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.encodedName
import zio.json.{DeriveJsonCodec, JsonCodec, jsonHint}

case class ApiExposureTime(
  numerator: Long,
  denominator: Long
)

object ApiExposureTime {
  given JsonCodec[ApiExposureTime] = DeriveJsonCodec.gen
  given Schema[ApiExposureTime]    = Schema.derived[ApiExposureTime].name(Schema.SName("ExposureTime"))
}
