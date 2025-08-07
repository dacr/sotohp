package fr.janalyse.sotohp.webapi

import zio.config.magnolia.*
import zio.{Config, ZIO}

case class ApiConfig(
  listeningPort: Int
) derives Config

object ApiConfig {
  val config = ZIO.config[ApiConfig]
}
