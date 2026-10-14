package fr.janalyse.sotohp.processor

import fr.janalyse.sotohp.core.{CoreIssue, LogContext}
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
  * GeoNames dump, resolved to town / sub-region (admin2) / region (admin1) / country.
  *
  * No network, no rate-limit, no street-level detail. The dump files are expected under
  * `GeocodingConfig.geonamesDirectory` :
  *   - `<citiesFile>` (e.g. `cities500.txt`) - the populated places
  *   - `<countryFiles>` (e.g. `FR.txt`, optional) - full country dumps, for the villages too small
  *     for the cities extract (only inhabited places and administrative seats are kept)
  *   - `admin1CodesASCII.txt` - first-level administrative division names
  *   - `admin2Codes.txt` - second-level administrative division names (département, county, ...)
  *   - `countryInfo.txt` - ISO country code to country name
  *
  * When the directory or the cities file is missing, [[allocate]] still succeeds and every
  * [[resolve]] call yields `None` (mirrors how the AI processors degrade rather than stopping a
  * batch sync). Missing files are downloaded automatically, see [[GeoNamesDownloader]].
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
  private val subRegions: Array[String],
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
          subRegion = Option(subRegions(bestIdx)).filter(_.nonEmpty),
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

  /** Downloads the missing GeoNames dump files (unless `autoDownload` is off), then reads them and
    * builds the resolver. Files still missing degrade to an always-`None` resolver with a single
    * warning; only a bad configuration fails.
    */
  def allocate(): IO[GeocodingIssue, GeoPlaceResolver] = {
    for {
      config   <- GeocodingConfig.config.mapError(err => GeocodingConfigIssue("Unable to read geocoding configuration", err))
      _        <- GeoNamesDownloader.ensureAvailable(config).when(config.autoDownload)
      resolver <- load(config)
    } yield resolver
  }

  private def load(config: GeocodingConfig): IO[GeocodingIssue, GeoPlaceResolver] = {
    val dir        = Path.of(config.geonamesDirectory)
    val citiesPath = dir.resolve(config.citiesFile)

    val logic =
      if (!Files.isReadable(citiesPath))
        (ZIO.logWarning("GeoNames cities file not found - textual places will not be deduced (enable geocoding auto-download or provide the file)")
          @@ LogContext.kv("citiesPath" -> citiesPath))
          .as(empty(config.maxDistanceKm))
      else
        ZIO.attemptBlocking {
          val countryNames   = parseKeyValue(dir.resolve("countryInfo.txt"), keyColumn = 0, valueColumn = 4, skipCommentLines = true)
          val regionNames    = parseKeyValue(dir.resolve("admin1CodesASCII.txt"), keyColumn = 0, valueColumn = 1, skipCommentLines = false)
          val subRegionNames = parseKeyValue(dir.resolve("admin2Codes.txt"), keyColumn = 0, valueColumn = 1, skipCommentLines = false)

          val xs         = mutable.ArrayBuilder.make[Double]
          val ys         = mutable.ArrayBuilder.make[Double]
          val zs         = mutable.ArrayBuilder.make[Double]
          val towns      = mutable.ArrayBuilder.make[String]
          val ccs        = mutable.ArrayBuilder.make[String]
          val subRegions = mutable.ArrayBuilder.make[String]
          val regions    = mutable.ArrayBuilder.make[String]
          val nations    = mutable.ArrayBuilder.make[String]
          // a place present in both the cities extract and a country dump is loaded once
          val seenIds    = mutable.HashSet.empty[String]

          // `townsOnly` applies to the full country dumps; the cities extract is already curated
          def loadPlaces(path: Path, townsOnly: Boolean): Unit =
            Using.resource(Source.fromFile(path.toFile, StandardCharsets.UTF_8.name())) { source =>
              source.getLines().foreach { line =>
                val cols = line.split("\t", -1)
                if (cols.length >= 15 && (!townsOnly || isTownLike(cols(6).trim, cols(7).trim, cols(14).trim)) && seenIds.add(cols(0).trim)) {
                  val name   = cols(1).trim
                  val cc     = cols(8).trim
                  val admin1 = cols(10).trim
                  val admin2 = cols(11).trim
                  val latOpt = cols(4).trim.toDoubleOption
                  val lonOpt = cols(5).trim.toDoubleOption
                  if (latOpt.isDefined && lonOpt.isDefined) {
                    val latRad = math.toRadians(latOpt.get)
                    val lonRad = math.toRadians(lonOpt.get)
                    val cosLat = math.cos(latRad)
                    xs += cosLat * math.cos(lonRad)
                    ys += cosLat * math.sin(lonRad)
                    zs += math.sin(latRad)
                    towns += name
                    ccs += cc
                    subRegions += (if (cc.nonEmpty && admin1.nonEmpty && admin2.nonEmpty) subRegionNames.getOrElse(s"$cc.$admin1.$admin2", "") else "")
                    regions += (if (cc.nonEmpty && admin1.nonEmpty) regionNames.getOrElse(s"$cc.$admin1", "") else "")
                    nations += (if (cc.nonEmpty) countryNames.getOrElse(cc, "") else "")
                  }
                }
              }
            }

          loadPlaces(citiesPath, townsOnly = false)
          countryFilePaths(config).filter(Files.isReadable).foreach(path => loadPlaces(path, townsOnly = true))

          val xa = xs.result()
          new GeoPlaceResolver(
            size = xa.length,
            xs = xa,
            ys = ys.result(),
            zs = zs.result(),
            towns = towns.result(),
            countryCodes = ccs.result(),
            subRegions = subRegions.result(),
            regions = regions.result(),
            countries = nations.result(),
            maxDistanceKm = config.maxDistanceKm
          )
        }.mapError(err => GeocodingLoadIssue(s"Unable to load GeoNames dump from ${config.geonamesDirectory}", err))
          .tap(resolver => ZIO.logInfo("GeoNames reverse-geocoder ready") @@ LogContext.kv("placesCount" -> resolver.size, "citiesPath" -> citiesPath))

    logic @@ LogContext.kv("geonamesDirectory" -> config.geonamesDirectory)
  }

  /** The configured `countryFiles`, e.g. `List("FR.txt")`. */
  def countryFileNames(config: GeocodingConfig): List[String] =
    config.countryFiles.split(",").toList.map(_.trim).filter(_.nonEmpty)

  private def countryFilePaths(config: GeocodingConfig): List[Path] =
    countryFileNames(config).map(Path.of(config.geonamesDirectory).resolve)

  private val seatFeatureCodes = Set("PPLA", "PPLA2", "PPLA3", "PPLA4", "PPLA5", "PPLC", "PPLG")

  /** Keeps actual towns and villages only. A full country dump also lists hamlets, city quarters
    * and localities ("Faubourg Pavé", "Le Tremblay", ...) - GeoNames gives those no population -
    * which would otherwise win over the commune they belong to. Administrative seats are always
    * kept, populated places (`PPL`) only when inhabited; sections (`PPLX`), farms, ruins, ... never.
    */
  private def isTownLike(featureClass: String, featureCode: String, populationText: String): Boolean =
    featureClass == "P" && (
      seatFeatureCodes.contains(featureCode) ||
        (featureCode == "PPL" && populationText.toLongOption.exists(_ > 0))
    )

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
    new GeoPlaceResolver(0, Array.empty, Array.empty, Array.empty, Array.empty, Array.empty, Array.empty, Array.empty, Array.empty, maxDistanceKm)
}
