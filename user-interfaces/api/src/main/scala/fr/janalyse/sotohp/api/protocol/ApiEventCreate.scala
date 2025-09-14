package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.encodedName
import zio.json.{DeriveJsonCodec, JsonCodec, jsonHint}

case class ApiEventCreate(
  name: EventName,
  description: Option[EventDescription],
  location: Option[ApiLocation],    // reference location for this event
  timestamp: Option[ShootDateTime], // reference date time for this event,
  originalId: Option[OriginalId],   // reference/chosen original which will be shown when the event is displayed
  keywords: Set[Keyword]
)

object ApiEventCreate {
  given JsonCodec[ApiEventCreate] = DeriveJsonCodec.gen
  given Schema[ApiEventCreate]    = Schema.derived[ApiEventCreate].name(Schema.SName("EventCreate"))
}
