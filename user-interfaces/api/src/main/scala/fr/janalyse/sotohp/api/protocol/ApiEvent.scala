package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{EventAttachment, EventDescription, EventId, EventName, Keyword, Location, OriginalId, ShootDateTime}
import zio.json.{DeriveJsonCodec, JsonCodec, jsonHint}
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.encodedName

case class ApiEvent(
  id: EventId,
  name: EventName,
  description: Option[EventDescription],
  location: Option[ApiLocation],    // reference location for this event
  timestamp: Option[ShootDateTime], // reference date time for this event,
  originalId: Option[OriginalId],   // reference/chosen original, which will be shown as the event cover
  keywords: Set[Keyword]
)

object ApiEvent {
  given JsonCodec[ApiEvent]              = DeriveJsonCodec.gen
  given apiEventSchema: Schema[ApiEvent] = Schema.derived[ApiEvent].name(Schema.SName("Event"))
}
