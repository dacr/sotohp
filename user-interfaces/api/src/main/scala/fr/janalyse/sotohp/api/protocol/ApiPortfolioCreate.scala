package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.{*, given}
import sttp.tapir.Schema

case class ApiPortfolioCreate(
  name: PortfolioName,
  description: Option[PortfolioDescription]
)

object ApiPortfolioCreate {
  given JsonValueCodec[ApiPortfolioCreate] = JsonCodecMaker.make
  given Schema[ApiPortfolioCreate]         = Schema.derived[ApiPortfolioCreate].name(Schema.SName("PortfolioCreate"))
}
