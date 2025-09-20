package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{BirthDate, FirstName, LastName, OriginalId, OwnerId}
import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}
import fr.janalyse.sotohp.service.json.given

case class ApiOwner(
  id: OwnerId,
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate],
  originalId: Option[OriginalId] // reference/chosen original, which will be shown as the owner cover
)

object ApiOwner {
  given JsonCodec[ApiOwner]              = DeriveJsonCodec.gen
  given apiOwnerSchema: Schema[ApiOwner] = Schema.derived[ApiOwner].name(Schema.SName("Owner"))
}
