package fr.janalyse.sotohp.core

import zio.*
import zio.stream.*
import scala.util.Try
import scala.util.matching.Regex
import zio.stream.ZPipeline.{splitLines, utf8Decode}

import java.io.{File, IOException}
import java.nio.charset.Charset
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path, Paths}
import java.time.{Instant, OffsetDateTime, ZoneId, ZoneOffset, ZonedDateTime}

import scala.Console.{BLUE, GREEN, RED, RESET, YELLOW}

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.model.DegreeMinuteSeconds.*
import fr.janalyse.sotohp.model.DecimalDegrees.*

object PhotoStream {

  /*
  tags.gPSGPSLatitude : 45° 19' 19,29"
  tags.gPSGPSLatitudeRef : N
  tags.gPSGPSLongitude : 6° 32' 39,47"
  tags.gPSGPSLongitudeRef : E
   */

  def extractGeoPoint(photoTags: Map[String, String]) =
    for {
      latitude     <- from(photoTags.get("gPSGPSLatitude"))
      latitudeRef  <- from(photoTags.get("gPSGPSLatitudeRef"))
      longitude    <- from(photoTags.get("gPSGPSLongitude"))
      longitudeRef <- from(photoTags.get("gPSGPSLongitudeRef"))
      altitude     <- from(photoTags.get(""))
      altitudeRef  <- from(photoTags.get(""))
      latitudeDMS  <- from(LatitudeDegreeMinuteSeconds.fromSpec(latitude, latitudeRef))
      longitudeDMS <- from(LongitudeDegreeMinuteSeconds.fromSpec(longitude, longitudeRef))
    } yield GeoPoint(
      latitudeDMS.toDecimalDegrees,
      longitudeDMS.toDecimalDegrees,
      altitude
    )

  // -------------------------------------------------------------------------------------------------------------------

  def searchPredicate(includeMaskRegex: Option[Regex], ignoreMaskRegex: Option[Regex])(path: Path, attrs: BasicFileAttributes): Boolean = {
    attrs.isRegularFile &&
    (ignoreMaskRegex.isEmpty || ignoreMaskRegex.get.findFirstIn(path.toString).isEmpty) &&
    (includeMaskRegex.isEmpty || includeMaskRegex.get.findFirstIn(path.toString).isDefined)
  }

  def findFromSearchRoot(
    searchRoot: Path,
    includeMaskRegex: Option[Regex],
    ignoreMaskRegex: Option[Regex]
  ) = {
    val result = for {
      searchPath <- ZIO.attempt(searchRoot)
      javaStream  = Files.find(searchPath, 10, searchPredicate(includeMaskRegex, ignoreMaskRegex))
      pathStream  = ZStream.fromJavaStream(javaStream).map(path => searchRoot -> path)
    } yield pathStream

    ZStream.unwrap(result)
  }

  def fetch() = {
    val result = for {
      _                 <- ZIO.logInfo("photos inventory")
      searchRoots       <- System
                             .env("PHOTOS_SEARCH_ROOTS")
                             .someOrFail("nowhere to search")
                             .map(_.split("[,;]").toList.map(_.trim))
      includeMask       <- System.env("PHOTOS_SEARCH_INCLUDE_MASK")
      includeMaskRegex  <- ZIO.attempt(includeMask.map(_.r))
      ignoreMask        <- System.env("PHOTOS_SEARCH_IGNORE_MASK")
      ignoreMaskRegex   <- ZIO.attempt(ignoreMask.map(_.r))
      searchRootsStreams = Chunk.fromIterable(searchRoots).map(searchRoot => findFromSearchRoot(Path.of(searchRoot), includeMaskRegex, ignoreMaskRegex))
      zCandidatesStream  = ZStream.concatAll(searchRootsStreams)
    } yield zCandidatesStream

    ZStream.unwrap(result)
  }

  def run = for {
    started <- Clock.instant
    _       <- Console.printLine(s"${GREEN}Synchronizing photos database$RESET")
    results <- fetch()
                 .mapZIOParUnordered(1)((searchPath, path) => makePhoto(searchPath, path))
                 .runDrain
                 .tapError(err => Console.printLine(err))

    finished <- Clock.instant
    duration  = finished.getEpochSecond - started.getEpochSecond
    _        <- Console.printLine(s"${GREEN}Synchronize operations done in $duration seconds$RESET")
  } yield ()

}
