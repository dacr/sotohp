package fr.janalyse.sotohp.api.protocol

import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.encodedName
import zio.json.*

case class ApiStatus(
  alive: Boolean
)

object ApiStatus {
  given JsonCodec[ApiStatus] = DeriveJsonCodec.gen
  given Schema[ApiStatus] = Schema.derived[ApiStatus].name(Schema.SName("Status"))
}
