package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.{BirthDate, FirstName, LastName, OwnerId}
import fr.janalyse.sotohp.service.json.given
import sttp.tapir.Schema

case class ApiOwnerCreate(
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate]
)

object ApiOwnerCreate {
  given JsonValueCodec[ApiOwnerCreate]         = JsonCodecMaker.make
  given apiOwnerSchema: Schema[ApiOwnerCreate] = Schema.derived[ApiOwnerCreate].name(Schema.SName("OwnerCreate"))
}
