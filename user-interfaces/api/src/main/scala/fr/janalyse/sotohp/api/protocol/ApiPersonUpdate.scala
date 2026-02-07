package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{BirthDate, FirstName, LastName}
import fr.janalyse.sotohp.model.{FaceId, PersonDescription, PersonEmail}
import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}
import fr.janalyse.sotohp.service.json.{*, given}

case class ApiPersonUpdate(
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate],
  email: Option[PersonEmail],
  description: Option[PersonDescription],
  chosenFaceId: Option[FaceId]
)

object ApiPersonUpdate {
  given JsonCodec[ApiPersonUpdate] = DeriveJsonCodec.gen
  given Schema[ApiPersonUpdate]    = Schema.derived[ApiPersonUpdate].name(Schema.SName("PersonUpdate"))
}
