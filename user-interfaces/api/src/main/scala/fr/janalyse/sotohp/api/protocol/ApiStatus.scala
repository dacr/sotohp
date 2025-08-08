package fr.janalyse.sotohp.api.protocol

import zio.json.*

case class ApiStatus(
  alive: Boolean
)

object ApiStatus {
  given JsonCodec[ApiStatus] = DeriveJsonCodec.gen
}
