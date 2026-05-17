package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

case class ApiPortfolioUpdate(
  name: PortfolioName,
  description: Option[PortfolioDescription]
)

object ApiPortfolioUpdate {
  given JsonCodec[ApiPortfolioUpdate] = DeriveJsonCodec.gen
  given Schema[ApiPortfolioUpdate]    = Schema.derived[ApiPortfolioUpdate].name(Schema.SName("PortfolioUpdate"))
}
