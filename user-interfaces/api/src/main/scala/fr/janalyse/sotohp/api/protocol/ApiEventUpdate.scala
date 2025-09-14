package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{EventDescription, EventName, Keyword, OriginalId, ShootDateTime}
import fr.janalyse.sotohp.service.json.given
import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

case class ApiEventUpdate(
  name: EventName,
  description: Option[EventDescription],
  location: Option[ApiLocation],    // reference location for this event
  timestamp: Option[ShootDateTime], // reference date time for this event,
  originalId: Option[OriginalId],   // reference/chosen original which will be shown when the event is displayed
  keywords: Set[Keyword]
)

object ApiEventUpdate {
  given JsonCodec[ApiEventUpdate]                    = DeriveJsonCodec.gen
  given apiEventUpdateSchema: Schema[ApiEventUpdate] = Schema.derived[ApiEventUpdate].name(Schema.SName("EventUpdate"))
}
