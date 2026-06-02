package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.given
import sttp.tapir.Schema

case class ApiStoreUpdate(
  name: Option[StoreName],
  baseDirectory: BaseDirectoryPath,
  includeMask: Option[IncludeMask] = None,
  ignoreMask: Option[IgnoreMask] = None
)

object ApiStoreUpdate {
  given JsonValueCodec[ApiStoreUpdate] = JsonCodecMaker.make
  given Schema[ApiStoreUpdate]         = Schema.derived[ApiStoreUpdate].name(Schema.SName("StoreUpdate"))
}
