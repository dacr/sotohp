package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema

case class ApiPortfolio(
  id: PortfolioId,
  name: PortfolioName,
  description: Option[PortfolioDescription],
  assetCount: Int,
  assets: List[ApiAsset]
)

object ApiPortfolio {
  given JsonValueCodec[ApiPortfolio] = JsonCodecMaker.make

  given apiPortfolioSchema: Schema[ApiPortfolio] = Schema.derived[ApiPortfolio].name(Schema.SName("Portfolio"))
}
