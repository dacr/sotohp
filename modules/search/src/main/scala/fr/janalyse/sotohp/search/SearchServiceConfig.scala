package fr.janalyse.sotohp.search

import zio.*
import zio.config.*
import zio.config.magnolia.*

case class SearchServiceConfig(
  enabled: Boolean,
  elasticUrl: String,       // WARNING : ELASTIC_URL DEFAULT PORT IS 9200 !! (and not 80 or 443) SO BE EXPLICIT
  elasticUrlTrust: Boolean, // shall we trust self-signed ssl certificates
  elasticUsername: Option[String],
  elasticPassword: Option[String],
  indexPrefix: String
)

object SearchServiceConfig {
  val config =
    deriveConfig[SearchServiceConfig]
      .mapKey(toKebabCase)
      .nested("sotohp", "search-service")
}
