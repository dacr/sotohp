package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{BirthDate, FirstName, LastName, OwnerId}
import fr.janalyse.sotohp.service.json.given
import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

case class ApiOwnerCreate(
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate]
)

object ApiOwnerCreate {
  given JsonCodec[ApiOwnerCreate]              = DeriveJsonCodec.gen
  given apiOwnerSchema: Schema[ApiOwnerCreate] = Schema.derived[ApiOwnerCreate].name(Schema.SName("OwnerCreate"))
}
