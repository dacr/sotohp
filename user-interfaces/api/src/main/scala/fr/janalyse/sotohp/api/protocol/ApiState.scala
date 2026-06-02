package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.{AddedOn, MediaAccessKey, OriginalId}
import fr.janalyse.sotohp.service.json.given
import sttp.tapir.Schema

case class ApiState(
  originalId: OriginalId,
  originalAddedOn: AddedOn,
  mediaAccessKey: Option[MediaAccessKey]
)

object ApiState {
  given JsonValueCodec[ApiState]         = JsonCodecMaker.make
  given apiOwnerSchema: Schema[ApiState] = Schema.derived[ApiState].name(Schema.SName("State"))
}
