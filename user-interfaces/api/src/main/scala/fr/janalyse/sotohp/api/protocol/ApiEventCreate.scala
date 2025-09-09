package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.encodedName
import zio.json.{DeriveJsonCodec, JsonCodec, jsonHint}

case class ApiEventCreate(
  name: EventName,
  description: Option[EventDescription],
  keywords: Set[Keyword]
)

object ApiEventCreate {
  given JsonCodec[ApiEventCreate] = DeriveJsonCodec.gen
  given Schema[ApiEventCreate]    = Schema.derived[ApiEventCreate].name(Schema.SName("EventCreate"))
}
