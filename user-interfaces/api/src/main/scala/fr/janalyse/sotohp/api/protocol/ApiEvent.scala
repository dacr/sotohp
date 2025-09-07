package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{EventAttachment, EventDescription, EventId, EventName, Keyword}
import zio.json.{DeriveJsonCodec, JsonCodec, jsonHint}
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.encodedName

case class ApiEvent(
  id: EventId,
  name: EventName,
  description: Option[EventDescription],
  keywords: Set[Keyword]
)

object ApiEvent {
  given JsonCodec[ApiEvent] = DeriveJsonCodec.gen
  given Schema[ApiEvent]    = Schema.derived[ApiEvent].name(Schema.SName("Event"))
}
