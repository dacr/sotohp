package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{EventAttachment, EventDescription, EventId, EventName, Keyword, Location, OriginalId, ShootDateTime}
import fr.janalyse.sotohp.service.json.{*, given}
import zio.json.{DeriveJsonCodec, JsonCodec, jsonHint}
import sttp.tapir.Schema
import sttp.tapir.Schema.annotations.encodedName

import java.net.{URI, URL}

case class ApiEvent(
  id: EventId,
  name: EventName,
  description: Option[EventDescription],
  location: Option[ApiLocation],    // reference location for this event
  timestamp: Option[ShootDateTime], // reference date time for this event,
  originalId: Option[OriginalId],   // reference/chosen original, which will be shown as the event cover
  publishedOn: Option[URL],         // URL where this event album has been published
  keywords: Set[Keyword]
)

object ApiEvent {
  given JsonCodec[URL] = JsonCodec[String].transform(
    str => new URI(str).toURL,
    url => url.toString
  )

  given JsonCodec[ApiEvent] = DeriveJsonCodec.gen

  given apiEventSchema: Schema[ApiEvent] = Schema.derived[ApiEvent].name(Schema.SName("Event"))
}
