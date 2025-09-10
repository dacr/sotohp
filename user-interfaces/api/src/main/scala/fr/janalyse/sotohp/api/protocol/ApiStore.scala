package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.{BaseDirectoryPath, IgnoreMask, IncludeMask, OwnerId, StoreId}
import fr.janalyse.sotohp.service.json.given
import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

case class ApiStore(
  id: StoreId,
  ownerId: OwnerId,
  baseDirectory: BaseDirectoryPath,
  includeMask: Option[IncludeMask] = None,
  ignoreMask: Option[IgnoreMask] = None
)

object ApiStore {
  given JsonCodec[ApiStore] = DeriveJsonCodec.gen
  given apiStoreSchema:Schema[ApiStore]    = Schema.derived[ApiStore].name(Schema.SName("Store"))
}
