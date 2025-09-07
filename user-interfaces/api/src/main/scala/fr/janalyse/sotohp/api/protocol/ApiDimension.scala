package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.*
import zio.json.{DeriveJsonCodec, JsonCodec, jsonHint}
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.encodedName

case class ApiDimension(
  width: Width,
  height: Height
)

object ApiDimension {
  given JsonCodec[ApiDimension] = DeriveJsonCodec.gen
  given Schema[ApiDimension]    = Schema.derived[ApiDimension].name(Schema.SName("Dimension"))
}
