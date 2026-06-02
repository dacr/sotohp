package fr.janalyse.sotohp.api.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

case class ApiClientAuth(
  enabled: Boolean,
  url: String,
  realm: String,
  clientId: String
)

object ApiClientAuth {
  implicit val codec: JsonValueCodec[ApiClientAuth] = JsonCodecMaker.make
}

case class ApiClientConfig(
  auth: ApiClientAuth
)

object ApiClientConfig {
  implicit val codec: JsonValueCodec[ApiClientConfig] = JsonCodecMaker.make
}
