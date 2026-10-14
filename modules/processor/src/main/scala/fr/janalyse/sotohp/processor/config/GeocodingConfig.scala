package fr.janalyse.sotohp.processor.config

import fr.janalyse.sotohp.core.ConfigInvalid
import zio.*
import zio.config.*
import zio.config.magnolia.*

/** Configuration of the offline reverse-geocoder ([[fr.janalyse.sotohp.processor.GeoPlaceResolver]]).
  *
  * @param geonamesDirectory
  *   directory holding the GeoNames dump files (`<citiesFile>`, `admin1CodesASCII.txt`,
  *   `admin2Codes.txt`, `countryInfo.txt`, the optional `<countryFiles>`). When the directory or
  *   the cities file is missing the resolver simply yields no place.
  * @param citiesFile
  *   which GeoNames "cities" extract to load - `cities500.txt` (~200k places, finest) down to
  *   `cities15000.txt` (coarsest, smallest).
  * @param countryFiles
  *   comma-separated full per-country dumps (e.g. `FR.txt,BE.txt`) loaded on top of the cities
  *   file, so that villages under 500 inhabitants are known too. Missing files are skipped.
  * @param autoDownload
  *   download the missing dump files from `downloadBaseUrl` before loading them (in the background
  *   at startup). Only missing files are fetched: delete the directory, or some of its files, to
  *   refresh the dump.
  * @param downloadBaseUrl
  *   where the GeoNames dump files are published.
  * @param maxDistanceKm
  *   if the nearest populated place is farther than this, no place is deduced (photo taken at sea,
  *   in a desert, ...).
  */
case class GeocodingConfig(
  geonamesDirectory: String,
  citiesFile: String,
  countryFiles: String,
  autoDownload: Boolean,
  downloadBaseUrl: String,
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
