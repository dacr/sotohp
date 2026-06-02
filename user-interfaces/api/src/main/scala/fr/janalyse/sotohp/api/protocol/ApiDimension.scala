package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema

case class ApiDimension(
  width: Width,
  height: Height
)

object ApiDimension {
  given JsonValueCodec[ApiDimension] = JsonCodecMaker.make
  given Schema[ApiDimension]         = Schema.derived[ApiDimension].name(Schema.SName("Dimension"))
}
