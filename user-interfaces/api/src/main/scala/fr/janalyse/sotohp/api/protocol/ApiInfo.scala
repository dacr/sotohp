package fr.janalyse.sotohp.api.protocol

import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.encodedName
import zio.json.*

case class ApiInfo(
  authors: List[String],
  version: String,
  message: String,
  originalsCount: Long
)

object ApiInfo {
  given JsonCodec[ApiInfo] = DeriveJsonCodec.gen
  given Schema[ApiInfo]    = Schema.derived[ApiInfo].name(Schema.SName("Info"))
}

