package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.{EventDescription, EventName, Keyword, OriginalId, ShootDateTime}
import fr.janalyse.sotohp.service.json.given
import sttp.tapir.Schema

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
  given JsonValueCodec[URL] = new JsonValueCodec[URL] {
    override def nullValue: URL                                 = null
    override def encodeValue(x: URL, out: JsonWriter): Unit     = out.writeVal(x.toString)
    override def decodeValue(in: JsonReader, default: URL): URL = new URI(in.readString(null)).toURL
  }

  given JsonValueCodec[ApiEventUpdate] = JsonCodecMaker.make

  given apiEventUpdateSchema: Schema[ApiEventUpdate] = Schema.derived[ApiEventUpdate].name(Schema.SName("EventUpdate"))
}
