package fr.janalyse.sotohp.processor

import fr.janalyse.sotohp.core.CoreIssue
import fr.janalyse.sotohp.model.Place
import fr.janalyse.sotohp.processor.config.GeocodingConfig
import zio.*
import zio.ZIOAspect.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.io.Source
import scala.util.Using

trait GeocodingIssue(message: String, mayBeErr: Option[Throwable]) extends Exception with CoreIssue
case class GeocodingConfigIssue(message: String, err: Throwable) extends GeocodingIssue(message, Some(err))
case class GeocodingLoadIssue(message: String, err: Throwable)   extends GeocodingIssue(message, Some(err))

/** Fully offline reverse-geocoder : given a GPS point, returns the nearest populated place from a
  * GeoNames dump, resolved to town / region (admin1) / country.
  *
  * No network, no rate-limit, no street-level detail. The dump files are expected under
  * `GeocodingConfig.geonamesDirectory` :
  *   - `<citiesFile>` (e.g. `cities500.txt`) - the populated places
  *   - `admin1CodesASCII.txt` - first-level administrative division names
  *   - `countryInfo.txt` - ISO country code to country name
  *
  * When the directory or the cities file is missing, [[allocate]] still succeeds and every
  * [[resolve]] call yields `None` (mirrors how the AI processors degrade rather than stopping a
  * batch sync). Get the files with `make download-geonames`.
  *
  * Nearest-place search is a brute-force scan over primitive coordinate arrays projected onto the
  * unit sphere (~1ms for 200k places) - fast enough for one-at-a-time sync and for a parallel
  * backfill. The resolver is immutable once allocated, so it is safe to share across fibers.
  */
final class GeoPlaceResolver private (
  private val size: Int,
  private val xs: Array[Double],
  private val ys: Array[Double],
  private val zs: Array[Double],
  private val towns: Array[String],
  private val countryCodes: Array[String],
  private val regions: Array[String],
  private val countries: Array[String],
  private val maxDistanceKm: Double
) {

  /** The place the given coordinates fall in, or `None` when nothing was loaded or the nearest
    * known place is farther than `maxDistanceKm`.
    */
  def resolve(latitude: Double, longitude: Double): UIO[Option[Place]] = ZIO.succeed {
    if (size == 0) None
    else {
      val latRad = math.toRadians(latitude)
      val lonRad = math.toRadians(longitude)
      val cosLat = math.cos(latRad)
      val qx     = cosLat * math.cos(lonRad)
      val qy     = cosLat * math.sin(lonRad)
      val qz     = math.sin(latRad)

      var bestIdx = -1
      var bestSq  = Double.MaxValue
      var i       = 0
      while (i < size) {
        val dx = xs(i) - qx
        val dy = ys(i) - qy
        val dz = zs(i) - qz
        val sq = dx * dx + dy * dy + dz * dz
        if (sq < bestSq) {
          bestSq = sq
          bestIdx = i
        }
        i += 1
      }

      // chord length -> central angle -> great-circle distance
      val chord       = math.sqrt(math.max(0d, bestSq))
      val centralRad  = 2d * math.asin(math.min(1d, chord / 2d))
      val distanceKm  = GeoPlaceResolver.earthRadiusKm * centralRad

      if (bestIdx < 0 || distanceKm > maxDistanceKm) None
      else {
        val cc    = countryCodes(bestIdx)
        val place = Place(
          street = None,
          town = Option(towns(bestIdx)).filter(_.nonEmpty),
          region = Option(regions(bestIdx)).filter(_.nonEmpty),
          country = Option(countries(bestIdx)).filter(_.nonEmpty),
          countryCode = Option(cc).filter(_.nonEmpty)
        )
        if (place.isEmpty) None else Some(place)
      }
    }
  }
}

object GeoPlaceResolver {

  private val earthRadiusKm = 6371.0088

  /** Reads the configured GeoNames dump and builds the resolver. Missing files degrade to an
    * always-`None` resolver with a single warning; only a bad configuration fails.
    */
  def allocate(): IO[GeocodingIssue, GeoPlaceResolver] = {
    for {
      config   <- GeocodingConfig.config.mapError(err => GeocodingConfigIssue("Unable to read geocoding configuration", err))
      resolver <- load(config)
    } yield resolver
  }

  private def load(config: GeocodingConfig): IO[GeocodingIssue, GeoPlaceResolver] = {
    val dir        = Path.of(config.geonamesDirectory)
    val citiesPath = dir.resolve(config.citiesFile)

    val logic =
      if (!Files.isReadable(citiesPath))
        ZIO
          .logWarning(
            s"GeoNames cities file not found at $citiesPath - textual places will not be deduced (run 'make download-geonames')"
          )
          .as(empty(config.maxDistanceKm))
      else
        ZIO.attemptBlocking {
          val countryNames = parseKeyValue(dir.resolve("countryInfo.txt"), keyColumn = 0, valueColumn = 4, skipCommentLines = true)
          val regionNames  = parseKeyValue(dir.resolve("admin1CodesASCII.txt"), keyColumn = 0, valueColumn = 1, skipCommentLines = false)

          val xs      = mutable.ArrayBuilder.make[Double]
          val ys      = mutable.ArrayBuilder.make[Double]
          val zs      = mutable.ArrayBuilder.make[Double]
          val towns   = mutable.ArrayBuilder.make[String]
          val ccs     = mutable.ArrayBuilder.make[String]
          val regions = mutable.ArrayBuilder.make[String]
          val nations = mutable.ArrayBuilder.make[String]

          Using.resource(Source.fromFile(citiesPath.toFile, StandardCharsets.UTF_8.name())) { source =>
            source.getLines().foreach { line =>
              val cols = line.split("\t", -1)
              if (cols.length >= 11) {
                val name    = cols(1).trim
                val latText = cols(4).trim
                val lonText = cols(5).trim
                val cc      = cols(8).trim
                val admin1  = cols(10).trim
                val latOpt  = latText.toDoubleOption
                val lonOpt  = lonText.toDoubleOption
                if (latOpt.isDefined && lonOpt.isDefined) {
                  val latRad = math.toRadians(latOpt.get)
                  val lonRad = math.toRadians(lonOpt.get)
                  val cosLat = math.cos(latRad)
                  xs += cosLat * math.cos(lonRad)
                  ys += cosLat * math.sin(lonRad)
                  zs += math.sin(latRad)
                  towns += name
                  ccs += cc
                  regions += (if (cc.nonEmpty && admin1.nonEmpty) regionNames.getOrElse(s"$cc.$admin1", "") else "")
                  nations += (if (cc.nonEmpty) countryNames.getOrElse(cc, "") else "")
                }
              }
            }
          }

          val xa = xs.result()
          new GeoPlaceResolver(
            size = xa.length,
            xs = xa,
            ys = ys.result(),
            zs = zs.result(),
            towns = towns.result(),
            countryCodes = ccs.result(),
            regions = regions.result(),
            countries = nations.result(),
            maxDistanceKm = config.maxDistanceKm
          )
        }.mapError(err => GeocodingLoadIssue(s"Unable to load GeoNames dump from ${config.geonamesDirectory}", err))
          .tap(resolver => ZIO.logInfo(s"GeoNames reverse-geocoder ready with ${resolver.size} places from $citiesPath"))

    logic @@ annotated("geonamesDirectory" -> config.geonamesDirectory)
  }

  /** Parses a GeoNames tab-separated `key -> value` file into a map. */
  private def parseKeyValue(path: Path, keyColumn: Int, valueColumn: Int, skipCommentLines: Boolean): Map[String, String] = {
    if (!Files.isReadable(path)) Map.empty
    else
      Using.resource(Source.fromFile(path.toFile, StandardCharsets.UTF_8.name())) { source =>
        source
          .getLines()
          .filterNot(line => line.isEmpty || (skipCommentLines && line.startsWith("#")))
          .flatMap { line =>
            val cols = line.split("\t", -1)
            if (cols.length > math.max(keyColumn, valueColumn)) {
              val key   = cols(keyColumn).trim
              val value = cols(valueColumn).trim
              if (key.nonEmpty && value.nonEmpty) Some(key -> value) else None
            } else None
          }
          .toMap
      }
  }

  private def empty(maxDistanceKm: Double): GeoPlaceResolver =
    new GeoPlaceResolver(0, Array.empty, Array.empty, Array.empty, Array.empty, Array.empty, Array.empty, Array.empty, maxDistanceKm)
}
