package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema

case class ApiEventCreate(
  name: EventName,
  description: Option[EventDescription],
  keywords: Set[Keyword]
)

object ApiEventCreate {
  given JsonValueCodec[ApiEventCreate] = JsonCodecMaker.make
  given Schema[ApiEventCreate]         = Schema.derived[ApiEventCreate].name(Schema.SName("EventCreate"))
}
