package fr.janalyse.sotohp.config

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.store.{PhotoStoreIssue, PhotoStoreService}
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
  val config =
    deriveConfig[MiniaturizerConfig]
      .mapKey(toKebabCase)
      .nested("sotohp", "miniaturizer")
}
