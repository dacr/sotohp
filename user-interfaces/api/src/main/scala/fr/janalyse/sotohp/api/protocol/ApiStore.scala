package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.{BaseDirectoryPath, IgnoreMask, IncludeMask, OwnerId, StoreId, StoreName}
import fr.janalyse.sotohp.service.json.given
import sttp.tapir.Schema

case class ApiStore(
  id: StoreId,
  name: Option[StoreName],
  ownerId: OwnerId,
  baseDirectory: BaseDirectoryPath,
  includeMask: Option[IncludeMask] = None,
  ignoreMask: Option[IgnoreMask] = None
)

object ApiStore {
  given JsonValueCodec[ApiStore]         = JsonCodecMaker.make
  given apiStoreSchema: Schema[ApiStore] = Schema.derived[ApiStore].name(Schema.SName("Store"))
}
