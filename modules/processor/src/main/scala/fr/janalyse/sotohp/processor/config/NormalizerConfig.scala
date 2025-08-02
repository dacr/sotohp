package fr.janalyse.sotohp.processor.config

import fr.janalyse.sotohp.core.ConfigInvalid
import zio.*
import zio.config.*
import zio.config.magnolia.*

case class NormalizerConfig(
  referenceSize: Int,
  quality: Double,
  format: String
)

object NormalizerConfig {
  private val derivedConfig =
    deriveConfig[NormalizerConfig]
      .mapKey(toKebabCase)
      .nested("sotohp", "processors", "normalizer")

  val config =
    ZIO
      .config(derivedConfig)
      .mapError(err => ConfigInvalid("Couldn't build MiniaturizerConfig", err))

}
