package fr.janalyse.sotohp.service

import zio.config.magnolia.*
import zio.{Config, ZIO}

case class ServiceConfig(
  listeningPort: Int,
) derives Config

object ServiceConfig {
  val config = ZIO.config[ServiceConfig]
}
