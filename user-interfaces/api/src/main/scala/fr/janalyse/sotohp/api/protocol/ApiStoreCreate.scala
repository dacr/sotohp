package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.given
import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

case class ApiStoreCreate(
  name: Option[StoreName],
  ownerId: OwnerId,
  baseDirectory: BaseDirectoryPath,
  includeMask: Option[IncludeMask] = None,
  ignoreMask: Option[IgnoreMask] = None
)

object ApiStoreCreate {
  given JsonCodec[ApiStoreCreate] = DeriveJsonCodec.gen
  given apiStoreSchema:Schema[ApiStoreCreate]    = Schema.derived[ApiStoreCreate].name(Schema.SName("StoreCreate"))
}
