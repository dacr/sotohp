package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.given
import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

case class ApiStoreUpdate(
  includeMask: Option[IncludeMask] = None,
  ignoreMask: Option[IgnoreMask] = None
)

object ApiStoreUpdate {
  given JsonCodec[ApiStoreUpdate] = DeriveJsonCodec.gen
  given Schema[ApiStoreUpdate]    = Schema.derived[ApiStoreUpdate].name(Schema.SName("StoreUpdate"))
}
