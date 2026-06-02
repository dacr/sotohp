package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.{BirthDate, FirstName, LastName, OwnerId}
import fr.janalyse.sotohp.service.json.given
import sttp.tapir.Schema

case class ApiOwnerUpdate(
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate]
)

object ApiOwnerUpdate {
  given JsonValueCodec[ApiOwnerUpdate]         = JsonCodecMaker.make
  given apiOwnerSchema: Schema[ApiOwnerUpdate] = Schema.derived[ApiOwnerUpdate].name(Schema.SName("OwnerUpdate"))
}
