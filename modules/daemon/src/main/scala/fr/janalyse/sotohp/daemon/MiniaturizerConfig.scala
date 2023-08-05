package fr.janalyse.sotohp.daemon

import zio.*
import zio.config.*
import zio.config.typesafe.*
import zio.config.magnolia.*
import java.io.File
import java.nio.file.Path
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.core.{OriginalsStream, PhotoOperations, PhotoFileIssue, NotFoundInStore, StreamIOIssue}
import fr.janalyse.sotohp.store.{PhotoStoreService, PhotoStoreIssue}
import net.coobird.thumbnailator.Thumbnails

case class MiniaturizerConfig(
  baseDirectory: String,
  referenceSizes: List[Int],
  quality: Double,
  format: String
)

object MiniaturizerConfig {
  val config =
    deriveConfig[MiniaturizerConfig]
      .mapKey(toKebabCase)
      .nested("sotohp", "daemons", "miniaturizer")
}
