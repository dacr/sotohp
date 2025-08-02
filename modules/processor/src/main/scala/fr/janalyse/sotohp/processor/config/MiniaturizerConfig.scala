package fr.janalyse.sotohp.processor.config

import fr.janalyse.sotohp.core.ConfigInvalid
import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*

import java.io.File
import java.nio.file.Path

case class MiniaturizerConfig(
  referenceSizes: List[Int],
  quality: Double,
  format: String
)

object MiniaturizerConfig {
  private val derivedConfig =
    deriveConfig[MiniaturizerConfig]
      .mapKey(toKebabCase)
      .nested("sotohp", "processors", "miniaturizer")

  val config =
    ZIO.config(derivedConfig)
      .mapError(err => ConfigInvalid("Couldn't build MiniaturizerConfig", err))

}
