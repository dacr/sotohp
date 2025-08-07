package fr.janalyse.sotohp.webapi.protocol

import zio.json.*

case class ServiceStatus(
  alive: Boolean
)

object ServiceStatus {
  given JsonCodec[ServiceStatus] = DeriveJsonCodec.gen
}
