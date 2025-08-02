package fr.janalyse.sotohp.processor.config

import fr.janalyse.sotohp.core.ConfigInvalid
import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*

case class CacheDataConfig(
  directory: String
)

object CacheDataConfig {
  private val derivedConfig =
    deriveConfig[CacheDataConfig]
      .mapKey(toKebabCase)
      .nested("sotohp", "processors", "data-cache")

  val config =
    ZIO
      .config(derivedConfig)
      .mapError(err => ConfigInvalid("Couldn't build InternalDataConfig", err))
}
