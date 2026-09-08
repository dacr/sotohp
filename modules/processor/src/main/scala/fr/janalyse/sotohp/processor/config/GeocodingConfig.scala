package fr.janalyse.sotohp.processor.config

import fr.janalyse.sotohp.core.ConfigInvalid
import zio.*
import zio.config.*
import zio.config.magnolia.*

/** Configuration of the offline reverse-geocoder ([[fr.janalyse.sotohp.processor.GeoPlaceResolver]]).
  *
  * @param geonamesDirectory
  *   directory holding the GeoNames dump files (`<citiesFile>`, `admin1CodesASCII.txt`,
  *   `countryInfo.txt`). When the directory or the cities file is missing the resolver simply
  *   yields no place.
  * @param citiesFile
  *   which GeoNames "cities" extract to load - `cities500.txt` (~200k places, finest) down to
  *   `cities15000.txt` (coarsest, smallest).
  * @param maxDistanceKm
  *   if the nearest populated place is farther than this, no place is deduced (photo taken at sea,
  *   in a desert, ...).
  */
case class GeocodingConfig(
  geonamesDirectory: String,
  citiesFile: String,
  maxDistanceKm: Double
)

object GeocodingConfig {
  private val derivedConfig =
    deriveConfig[GeocodingConfig]
      .mapKey(toKebabCase)
      .nested("sotohp", "processors", "geocoding")

  val config =
    ZIO
      .config(derivedConfig)
      .mapError(err => ConfigInvalid("Couldn't build GeocodingConfig", err))
}
