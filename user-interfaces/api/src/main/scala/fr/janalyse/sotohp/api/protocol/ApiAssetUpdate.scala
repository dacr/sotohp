package fr.janalyse.sotohp.api.protocol

import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

case class ApiAssetUpdate(
  oldAsset: ApiAsset,
  newAsset: ApiAsset
)

object ApiAssetUpdate {
  given JsonCodec[ApiAssetUpdate] = DeriveJsonCodec.gen
  given Schema[ApiAssetUpdate]    = Schema.derived[ApiAssetUpdate].name(Schema.SName("AssetUpdate"))
}
