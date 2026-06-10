package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.{BirthDate, BirthName, FirstName, LastName}
import fr.janalyse.sotohp.model.{PersonDescription, PersonEmail}
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema

case class ApiPersonCreate(
  firstName: FirstName,
  lastName: LastName,
  birthName: Option[BirthName],
  birthDate: Option[BirthDate],
  email: Option[PersonEmail],
  description: Option[PersonDescription]
)

object ApiPersonCreate {
  given JsonValueCodec[ApiPersonCreate] = JsonCodecMaker.make
  given Schema[ApiPersonCreate]         = Schema.derived[ApiPersonCreate].name(Schema.SName("PersonCreate"))
}
