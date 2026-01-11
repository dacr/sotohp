package fr.janalyse.sotohp.api

import fr.janalyse.sotohp.core.ConfigInvalid
import zio.*
import zio.config.*
import zio.config.magnolia.*

case class AuthConfig(
  enabled: Boolean = false,
  issuer: String = "http://127.0.0.1:8081/realms/sotohp",
  clientId: String = "sotohp-web",
  jwksUrl: String = "http://127.0.0.1:8081/realms/sotohp/protocol/openid-connect/certs",
  audience: Option[String] = None,
  jwksRefreshIntervalSeconds: Int = 300
)

case class ApiConfig(
  listeningPort: Int,
  clientResourcesPath: String,
  auth: AuthConfig = AuthConfig(),
  cacheMaxAgeSeconds: Int = 900
)

object ApiConfig {
  val derivedConfig =
    deriveConfig[ApiConfig]
      .mapKey(toKebabCase)
      .nested("sotohp", "web-api")

  val config =
    ZIO
      .config(derivedConfig)
      .mapError(err => ConfigInvalid("Couldn't build ApiConfig", err))
}
