package fr.janalyse.sotohp.cli

import zio.*
import zio.config.*
import zio.config.magnolia.*

case class SearchConfig(
  ownerId: String,
  roots: String,
  includeMask: Option[String],
  ignoreMask: Option[String]
)

object SearchConfig {
  val config =
    deriveConfig[SearchConfig]
      .mapKey(toKebabCase)
      .nested("sotohp", "search")
}
