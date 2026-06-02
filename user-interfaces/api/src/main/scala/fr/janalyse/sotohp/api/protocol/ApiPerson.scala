package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.{BirthDate, FirstName, LastName}
import fr.janalyse.sotohp.model.{FaceId, PersonDescription, PersonEmail, PersonId}
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema

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
  given JsonValueCodec[ApiPerson] = JsonCodecMaker.make

  given apiOwnerSchema: Schema[ApiPerson] = Schema.derived[ApiPerson].name(Schema.SName("Person"))
}
