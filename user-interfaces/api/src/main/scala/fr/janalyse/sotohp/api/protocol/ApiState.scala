package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{AddedOn, MediaAccessKey, OriginalId}
import fr.janalyse.sotohp.service.json.given
import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

case class ApiState(
  originalId: OriginalId,
  originalAddedOn: AddedOn,
  mediaAccessKey: MediaAccessKey,
)

object ApiState {
  given JsonCodec[ApiState]              = DeriveJsonCodec.gen
  given apiOwnerSchema: Schema[ApiState] = Schema.derived[ApiState].name(Schema.SName("State"))
}
