package fr.janalyse.sotohp.processor

import fr.janalyse.sotohp.model.Place
import zio.*
import zio.test.*

import java.nio.file.Path

/** Runs against a handful of real GeoNames lines (see `src/test/resources/geonames`) around
  * Marchais-en-Brie and Verdun: the cities extract only knows Montmirail / Verdun, the FR dump adds
  * the village of Marchais-en-Brie, a hamlet, a city quarter and a commune (ADM4) point.
  */
object GeoPlaceResolverSpec extends BaseSpecDefault {

  private val geonamesDirectory = Path.of(getClass.getResource("/geonames").toURI).toString

  private def resolverWith(countryFiles: String) =
    GeoPlaceResolver
      .allocate()
      .withConfigProvider(
        ConfigProvider.fromMap(
          Map(
            "sotohp.processors.geocoding.geonames-directory" -> geonamesDirectory,
            "sotohp.processors.geocoding.cities-file"        -> "cities500.txt",
            "sotohp.processors.geocoding.country-files"      -> countryFiles,
            "sotohp.processors.geocoding.auto-download"      -> "false",
            "sotohp.processors.geocoding.download-base-url"  -> "http://127.0.0.1:1",
            "sotohp.processors.geocoding.max-distance-km"    -> "150"
          )
        )
      )

  override def spec = suite("GeoPlaceResolver")(
    test("without country dump, the nearest cities500 town wins") {
      for {
        resolver <- resolverWith(countryFiles = "")
        place    <- resolver.resolve(48.88218166666667, 3.48452)
      } yield assertTrue(place.flatMap(_.town).contains("Montmirail"))
    },
    test("a country dump brings the small villages, with their own sub-region and region") {
      for {
        resolver <- resolverWith(countryFiles = "FR.txt")
        place    <- resolver.resolve(48.88218166666667, 3.48452)
      } yield assertTrue(
        place.contains(
          Place(
            street = None,
            town = Some("Marchais-en-Brie"),
            subRegion = Some("Aisne"),
            region = Some("Hauts-de-France"),
            country = Some("France"),
            countryCode = Some("FR")
          )
        )
      )
    },
    test("uninhabited hamlets and quarters of a country dump are ignored") {
      for {
        resolver     <- resolverWith(countryFiles = "FR.txt")
        nearTremblay <- resolver.resolve(48.8858, 3.4955)   // hamlet of Marchais-en-Brie
        nearFaubourg <- resolver.resolve(49.16152, 5.40197) // quarter of Verdun
      } yield assertTrue(
        nearTremblay.flatMap(_.town).contains("Marchais-en-Brie"),
        nearFaubourg.flatMap(_.town).contains("Verdun"),
        nearFaubourg.flatMap(_.subRegion).contains("Meuse")
      )
    },
    test("a missing country dump is skipped") {
      for {
        resolver <- resolverWith(countryFiles = "XX.txt")
        place    <- resolver.resolve(49.15953825, 5.383669833333334)
      } yield assertTrue(place.flatMap(_.town).contains("Verdun"))
    }
  )
}
