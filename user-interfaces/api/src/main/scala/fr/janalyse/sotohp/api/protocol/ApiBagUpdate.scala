package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.{BagDescription, BagName, Keyword, OriginalId, ShootDateTime}
import fr.janalyse.sotohp.service.json.given
import sttp.tapir.Schema

import java.net.{URI, URL}

case class ApiBagUpdate(
  name: BagName,
  description: Option[BagDescription],
  location: Option[ApiLocation],    // reference location for this bag
  timestamp: Option[ShootDateTime], // reference date time for this bag,
  publishedOn: Option[URL],         // URL where this bag album has been published
  keywords: Set[Keyword]
)

object ApiBagUpdate {
  given JsonValueCodec[URL] = new JsonValueCodec[URL] {
    override def nullValue: URL                                 = null
    override def encodeValue(x: URL, out: JsonWriter): Unit     = out.writeVal(x.toString)
    override def decodeValue(in: JsonReader, default: URL): URL = new URI(in.readString(null)).toURL
  }

  given JsonValueCodec[ApiBagUpdate] = JsonCodecMaker.make

  given apiBagUpdateSchema: Schema[ApiBagUpdate] = Schema.derived[ApiBagUpdate].name(Schema.SName("BagUpdate"))
}
