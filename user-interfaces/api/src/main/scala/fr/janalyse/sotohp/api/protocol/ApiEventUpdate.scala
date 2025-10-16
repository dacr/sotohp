package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{EventDescription, EventName, Keyword, OriginalId, ShootDateTime}
import fr.janalyse.sotohp.service.json.given
import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

import java.net.{URI, URL}

case class ApiEventUpdate(
  name: EventName,
  description: Option[EventDescription],
  location: Option[ApiLocation],    // reference location for this event
  timestamp: Option[ShootDateTime], // reference date time for this event,
  publishedOn: Option[URL],         // URL where this event album has been published
  keywords: Set[Keyword]
)

object ApiEventUpdate {
  given JsonCodec[URL] = JsonCodec[String].transform(
    str => new URI(str).toURL,
    url => url.toString
  )

  given JsonCodec[ApiEventUpdate] = DeriveJsonCodec.gen

  given apiEventUpdateSchema: Schema[ApiEventUpdate] = Schema.derived[ApiEventUpdate].name(Schema.SName("EventUpdate"))
}
