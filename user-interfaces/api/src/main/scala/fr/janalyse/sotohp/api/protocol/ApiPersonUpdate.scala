package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.{BirthDate, FirstName, LastName}
import fr.janalyse.sotohp.model.{FaceId, PersonDescription, PersonEmail}
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema

case class ApiPersonUpdate(
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate],
  email: Option[PersonEmail],
  description: Option[PersonDescription],
  chosenFaceId: Option[FaceId]
)

object ApiPersonUpdate {
  given JsonValueCodec[ApiPersonUpdate] = JsonCodecMaker.make
  given Schema[ApiPersonUpdate]         = Schema.derived[ApiPersonUpdate].name(Schema.SName("PersonUpdate"))
}
