package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{BirthDate, FirstName, LastName, OwnerId}
import fr.janalyse.sotohp.service.json.given
import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

case class ApiOwnerUpdate(
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate]
)

object ApiOwnerUpdate {
  given JsonCodec[ApiOwnerUpdate]              = DeriveJsonCodec.gen
  given apiOwnerSchema: Schema[ApiOwnerUpdate] = Schema.derived[ApiOwnerUpdate].name(Schema.SName("OwnerUpdate"))
}
