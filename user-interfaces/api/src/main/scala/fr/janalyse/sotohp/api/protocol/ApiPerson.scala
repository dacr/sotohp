package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{BirthDate, FirstName, LastName}
import fr.janalyse.sotohp.model.{FaceId, PersonDescription, PersonEmail, PersonId}
import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}
import fr.janalyse.sotohp.service.json.{*, given}

case class ApiPerson(
  id: PersonId,
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate],
  email: Option[PersonEmail],
  description: Option[PersonDescription],
  chosenFaceId: Option[FaceId]
)

object ApiPerson {
  given JsonCodec[ApiPerson] = DeriveJsonCodec.gen

  given apiOwnerSchema: Schema[ApiPerson] = Schema.derived[ApiPerson].name(Schema.SName("Person"))
}
