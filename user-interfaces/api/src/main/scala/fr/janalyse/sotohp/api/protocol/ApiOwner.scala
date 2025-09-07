package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{BirthDate, FirstName, LastName, OwnerId}
import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}
import fr.janalyse.sotohp.service.json.given

case class ApiOwner(
  id: OwnerId,
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate]
)

object ApiOwner {
  given JsonCodec[ApiOwner] = DeriveJsonCodec.gen
  given Schema[ApiOwner]    = Schema.derived[ApiOwner].name(Schema.SName("Owner"))
}
