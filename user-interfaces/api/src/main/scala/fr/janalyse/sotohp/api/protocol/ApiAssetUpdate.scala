package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.tapir.Schema

case class ApiAssetUpdate(
  oldAsset: ApiAsset,
  newAsset: ApiAsset
)

object ApiAssetUpdate {
  given JsonValueCodec[ApiAssetUpdate] = JsonCodecMaker.make
  given Schema[ApiAssetUpdate]         = Schema.derived[ApiAssetUpdate].name(Schema.SName("AssetUpdate"))
}
