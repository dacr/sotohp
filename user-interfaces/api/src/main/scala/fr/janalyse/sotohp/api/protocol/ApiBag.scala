package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.{BagAttachment, BagDescription, BagId, BagName, Keyword, Location, OriginalId, ShootDateTime}
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema

import java.net.{URI, URL}

case class ApiBag(
  id: BagId,
  name: BagName,
  description: Option[BagDescription],
  location: Option[ApiLocation],    // reference location for this bag
  timestamp: Option[ShootDateTime], // reference date time for this bag,
  originalId: Option[OriginalId],   // reference/chosen original, which will be shown as the bag cover
  publishedOn: Option[URL],         // URL where this bag album has been published
  keywords: Set[Keyword]
)

object ApiBag {
  given JsonValueCodec[URL] = new JsonValueCodec[URL] {
    override def nullValue: URL                                 = null
    override def encodeValue(x: URL, out: JsonWriter): Unit     = out.writeVal(x.toString)
    override def decodeValue(in: JsonReader, default: URL): URL = new URI(in.readString(null)).toURL
  }

  given JsonValueCodec[ApiBag] = JsonCodecMaker.make

  given apiBagSchema: Schema[ApiBag] = Schema.derived[ApiBag].name(Schema.SName("Bag"))
}
