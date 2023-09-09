package fr.janalyse.sotohp.config

import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*
import zio.config.toKebabCase

case class SotohpConfig(
  internalData: InternalDataConfig,
  miniaturizer: MiniaturizerConfig,
  normalizer: NormalizerConfig
)

object SotohpConfig {
  val config =
    deriveConfig[SotohpConfig]
      .mapKey(toKebabCase)
      .nested("sotohp")

  val zioConfig =
    ZIO
      .config(SotohpConfig.config)
      .mapError(th => SotohpConfigIssue(s"Couldn't get configuration", th))
}
