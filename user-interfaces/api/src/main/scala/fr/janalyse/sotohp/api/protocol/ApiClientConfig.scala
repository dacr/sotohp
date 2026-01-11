package fr.janalyse.sotohp.api.protocol

import zio.json.*

case class ApiClientAuth(
  enabled: Boolean,
  url: String,
  realm: String,
  clientId: String
)

object ApiClientAuth {
  implicit val codec: JsonCodec[ApiClientAuth] = DeriveJsonCodec.gen
}

case class ApiClientConfig(
  auth: ApiClientAuth
)

object ApiClientConfig {
  implicit val codec: JsonCodec[ApiClientConfig] = DeriveJsonCodec.gen
}
