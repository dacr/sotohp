package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.given
import sttp.tapir.Schema

case class ApiStoreCreate(
  name: Option[StoreName],
  ownerId: OwnerId,
  baseDirectory: BaseDirectoryPath,
  includeMask: Option[IncludeMask] = None,
  ignoreMask: Option[IgnoreMask] = None
)

object ApiStoreCreate {
  given JsonValueCodec[ApiStoreCreate]         = JsonCodecMaker.make
  given apiStoreSchema: Schema[ApiStoreCreate] = Schema.derived[ApiStoreCreate].name(Schema.SName("StoreCreate"))
}
