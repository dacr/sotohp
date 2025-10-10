package fr.janalyse.sotohp.service

import fr.janalyse.sotohp.core.{ConfigInvalid, FileSystemSearchCoreConfig}
import io.scalaland.chimney.*
import io.scalaland.chimney.dsl.*
import zio.{Config, ZIO}
import zio.config.*
import zio.config.magnolia.*

import java.nio.file.Path

case class FileSystemSearchConfig(
  followLinks: Boolean,
  maxDepth: Int,
  lockDirectory: Option[String]
) derives Config {
  def toCoreConfig: FileSystemSearchCoreConfig = {
    this
      .into[FileSystemSearchCoreConfig]
      .withFieldComputed(_.lockDirectory, _.lockDirectory.filter(_.trim.nonEmpty).map(d => Path.of(d)))
      .transform
  }
}

case class ServiceConfig(
  fileSystemSearch: FileSystemSearchConfig
) derives Config

object ServiceConfig {
  val derivedConfig =
    deriveConfig[ServiceConfig]
      .mapKey(toKebabCase)
      .nested("sotohp", "media-service")

  val config =
    ZIO
      .config(derivedConfig)
      .mapError(err => ConfigInvalid("Couldn't build SearchServiceConfig", err))

}
