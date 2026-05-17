package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

case class ApiAsset(
  originalId: OriginalId,
  selectedBox: Option[ApiBoundingBox],
  description: Option[AssetDescription]
)

object ApiAsset {
  given JsonCodec[ApiAsset] = DeriveJsonCodec.gen

  given apiAssetSchema: Schema[ApiAsset] = Schema.derived[ApiAsset].name(Schema.SName("Asset"))
}
