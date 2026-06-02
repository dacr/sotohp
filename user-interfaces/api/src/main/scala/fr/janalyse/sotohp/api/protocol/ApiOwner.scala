package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.{BirthDate, FirstName, LastName, OriginalId, OwnerId}
import fr.janalyse.sotohp.service.json.given
import sttp.tapir.Schema

case class ApiOwner(
  id: OwnerId,
  firstName: FirstName,
  lastName: LastName,
  birthDate: Option[BirthDate],
  originalId: Option[OriginalId] // reference/chosen original, which will be shown as the owner cover
)

object ApiOwner {
  given JsonValueCodec[ApiOwner]         = JsonCodecMaker.make
  given apiOwnerSchema: Schema[ApiOwner] = Schema.derived[ApiOwner].name(Schema.SName("Owner"))
}
