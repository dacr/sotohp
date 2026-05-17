package fr.janalyse.sotohp.api.protocol

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec}

case class ApiPortfolioCreate(
  name: PortfolioName,
  description: Option[PortfolioDescription]
)

object ApiPortfolioCreate {
  given JsonCodec[ApiPortfolioCreate] = DeriveJsonCodec.gen
  given Schema[ApiPortfolioCreate]    = Schema.derived[ApiPortfolioCreate].name(Schema.SName("PortfolioCreate"))
}
