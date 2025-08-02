package fr.janalyse.sotohp.search

import fr.janalyse.sotohp.core.ConfigInvalid
import zio.*
import zio.config.*
import zio.config.magnolia.*

case class SearchServiceConfig(
  enabled: Boolean,
  elasticUrl: String,                 // WARNING : ELASTIC_URL DEFAULT PORT IS 9200 !! (and not 80 or 443) SO BE EXPLICIT
  elasticUrlTrustSelfSigned: Boolean, // shall we trust self-signed ssl certificates
  elasticUsername: Option[String],
  elasticPassword: Option[String],
  indexPrefix: String
)

object SearchServiceConfig {
  val derivedConfig =
    deriveConfig[SearchServiceConfig]
      .mapKey(toKebabCase)
      .nested("sotohp", "search-service")

  val config =
    ZIO
      .config(derivedConfig)
      .mapError(err => ConfigInvalid("Couldn't build SearchServiceConfig", err))
}
