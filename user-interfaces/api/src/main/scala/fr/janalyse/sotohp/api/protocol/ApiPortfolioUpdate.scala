package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema

case class ApiPortfolioUpdate(
  name: PortfolioName,
  description: Option[PortfolioDescription]
)

object ApiPortfolioUpdate {
  given JsonValueCodec[ApiPortfolioUpdate] = JsonCodecMaker.make
  given Schema[ApiPortfolioUpdate]         = Schema.derived[ApiPortfolioUpdate].name(Schema.SName("PortfolioUpdate"))
}
