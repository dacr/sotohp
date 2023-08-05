package fr.janalyse.sotohp.daemon

import zio.*
import zio.config.magnolia.*
import zio.config.*

case class NormalizerConfig(
  baseDirectory: String,
  referenceSize: Int,
  quality: Double
)

object NormalizerConfig {
  val config = deriveConfig[NormalizerConfig].mapKey(toKebabCase).nested("sotohp", "daemons", "normalizer")
}
