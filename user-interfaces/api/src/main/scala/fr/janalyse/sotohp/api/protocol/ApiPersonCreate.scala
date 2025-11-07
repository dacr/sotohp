package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{BirthDate, FirstName, LastName}
import fr.janalyse.sotohp.processor.model.PersonDescription
import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}
import fr.janalyse.sotohp.service.json.{*, given}

case class ApiPersonCreate(
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate],
  description: Option[PersonDescription]
)

object ApiPersonCreate {
  given JsonCodec[ApiPersonCreate] = DeriveJsonCodec.gen
  given Schema[ApiPersonCreate]    = Schema.derived[ApiPersonCreate].name(Schema.SName("PersonCreate"))
}
