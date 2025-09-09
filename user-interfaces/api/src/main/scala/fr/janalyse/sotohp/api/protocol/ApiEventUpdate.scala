package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{EventDescription, EventName, Keyword}
import fr.janalyse.sotohp.service.json.given
import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

case class ApiEventUpdate(
  name: EventName,
  description: Option[EventDescription],
  keywords: Set[Keyword]
)

object ApiEventUpdate {
  given JsonCodec[ApiEventUpdate]                 = DeriveJsonCodec.gen
  given apiEventUpdateSchema: Schema[ApiEventUpdate] = Schema.derived[ApiEventUpdate].name(Schema.SName("EventUpdate"))
}
