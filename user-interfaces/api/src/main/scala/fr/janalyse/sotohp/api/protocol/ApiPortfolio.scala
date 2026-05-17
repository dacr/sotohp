package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

case class ApiPortfolio(
  id: PortfolioId,
  name: PortfolioName,
  description: Option[PortfolioDescription],
  assetCount: Int,
  assets: List[ApiAsset]
)

object ApiPortfolio {
  given JsonCodec[ApiPortfolio] = DeriveJsonCodec.gen

  given apiPortfolioSchema: Schema[ApiPortfolio] = Schema.derived[ApiPortfolio].name(Schema.SName("Portfolio"))
}
