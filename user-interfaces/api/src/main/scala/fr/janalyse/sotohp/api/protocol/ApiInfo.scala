package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.tapir.Schema

case class ApiInfo(
  authors: List[String],
  version: String,
  message: String,
  originalsCount: Long
)

object ApiInfo {
  given JsonValueCodec[ApiInfo] = JsonCodecMaker.make
  given Schema[ApiInfo]         = Schema.derived[ApiInfo].name(Schema.SName("Info"))
}
