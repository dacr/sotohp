package fr.janalyse.sotohp.service.dao

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.service.json.{*, given}
import zio.lmdb.json.LMDBCodecJson

case class DaoPortfolio(
  id: PortfolioId,
  name: PortfolioName,
  description: Option[PortfolioDescription]
) derives LMDBCodecJson
