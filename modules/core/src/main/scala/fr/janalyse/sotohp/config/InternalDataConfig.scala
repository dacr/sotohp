package fr.janalyse.sotohp.config

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.store.{PhotoStoreIssue, PhotoStoreService}
import zio.*
import zio.config.*
import zio.config.magnolia.*
import zio.config.typesafe.*

case class InternalDataConfig(
  baseDirectory: String
)

object InternalDataConfig {
  val config =
    deriveConfig[InternalDataConfig]
      .mapKey(toKebabCase)
      .nested("sotohp", "internal-data")
}
