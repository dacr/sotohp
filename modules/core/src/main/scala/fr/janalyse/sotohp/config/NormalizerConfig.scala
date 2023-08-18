package fr.janalyse.sotohp.config

import zio.*
import zio.config.*
import zio.config.magnolia.*

case class NormalizerConfig(
  referenceSize: Int,
  quality: Double,
  format: String
)

object NormalizerConfig {
  val config = deriveConfig[NormalizerConfig].mapKey(toKebabCase).nested("sotohp", "normalizer")
}
