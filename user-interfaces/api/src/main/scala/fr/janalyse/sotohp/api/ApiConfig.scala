package fr.janalyse.sotohp.api

import fr.janalyse.sotohp.core.ConfigInvalid
import zio.*
import zio.config.*
import zio.config.magnolia.*

case class ApiConfig(
  listeningPort: Int,
  clientResourcesPath: String
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
