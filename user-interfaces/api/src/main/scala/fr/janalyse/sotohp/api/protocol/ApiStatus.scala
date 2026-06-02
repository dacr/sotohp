package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.tapir.Schema

case class ApiStatus(
  alive: Boolean
)

object ApiStatus {
  given JsonValueCodec[ApiStatus] = JsonCodecMaker.make
  given Schema[ApiStatus]         = Schema.derived[ApiStatus].name(Schema.SName("Status"))
}
