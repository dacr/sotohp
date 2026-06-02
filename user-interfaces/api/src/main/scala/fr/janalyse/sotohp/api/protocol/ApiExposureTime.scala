package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.tapir.Schema

case class ApiExposureTime(
  numerator: Long,
  denominator: Long
)

object ApiExposureTime {
  given JsonValueCodec[ApiExposureTime] = JsonCodecMaker.make
  given Schema[ApiExposureTime]         = Schema.derived[ApiExposureTime].name(Schema.SName("ExposureTime"))
}
