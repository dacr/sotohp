package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.NormalizeProcessor
import fr.janalyse.sotohp.store.{PhotoStoreService, LazyPhoto}
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB

import java.time.{OffsetDateTime, Instant}
import scala.io.AnsiColor.*

case class Statistics(
  count: Int = 0,
  geolocalizedCount: Int = 0,
  normalizedFailureCount: Int = 0,
  facesCount: Int = 0,
  duplicated: Map[String, Int] = Map.empty,
  missingCount: Int = 0,
  modifiedCount: Int = 0,
  missingShootingDate: Int = 0,
  invalidShootingDateCount: Int = 0
)

object Statistics extends ZIOAppDefault with CommonsCLI {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load()))

  override def run =
    logic
      .provide(
        LMDB.liveWithDatabaseName("photos"),
        PhotoStoreService.live,
        Scope.default
      )

  val shootingDateMinimumValidYear = 1826 // https://en.wikipedia.org/wiki/History_of_photography

  def updateStats(stats: Statistics, zphoto: LazyPhoto) = {
    for {
      source           <- zphoto.source.some
      meta             <- zphoto.metaData
      place            <- zphoto.place
      faces            <- zphoto.foundFaces
      hasNormalized    <- zphoto.hasNormalized
      shootingDate      = meta.flatMap(_.shootDateTime)
      filehash          = source.fileHash.code
      originalFound    <- ZIO.attempt(source.original.path.toFile.exists())
      originalModified <- ZIO
                            .attempt(source.fileLastModified.toInstant.toEpochMilli != source.original.path.toFile.lastModified())
                            .when(originalFound)
                            .someOrElse(false)
    } yield {
      val updatedCount                    = stats.count + 1
      val updatedGeolocalizedCount        = stats.geolocalizedCount + (if (place.isDefined) 1 else 0)
      val updatedNormalizedFailureCount   = stats.normalizedFailureCount + (if (hasNormalized) 0 else 1)
      val updatedFacesCount               = stats.facesCount + faces.map(_.count).getOrElse(0)
      val updatedMissingCount             = stats.missingCount + (if (originalFound) 0 else 1)
      val updatedModifiedCount            = stats.modifiedCount + (if (originalModified) 1 else 0)
      val updatedDuplicated               = stats.duplicated + (stats.duplicated.get(filehash) match {
        case None        => filehash -> 1
        case Some(count) => filehash -> (count + 1)
      })
      val updatedMissingShootingDateCount = stats.missingShootingDate + (if (shootingDate.isEmpty) 1 else 0)
      val updatedInvalidShootingDateCount = stats.invalidShootingDateCount + (if (shootingDate.exists(_.getYear < shootingDateMinimumValidYear)) 1 else 0)
      stats.copy(
        count = updatedCount,
        geolocalizedCount = updatedGeolocalizedCount,
        normalizedFailureCount = updatedNormalizedFailureCount,
        duplicated = updatedDuplicated,
        facesCount = updatedFacesCount,
        missingCount = updatedMissingCount,
        modifiedCount = updatedModifiedCount,
        missingShootingDate = updatedMissingShootingDateCount,
        invalidShootingDateCount = updatedInvalidShootingDateCount
      )
    }
  }

  def reportStats(stats: Statistics) = {
    import stats.*
    val duplicatedCount = stats.duplicated.filter((_, count) => count > 1).size
    for {
      _ <- Console.printLine(s"${UNDERLINED}${BLUE}Photo statistics :$RESET")
      _ <- Console.printLine(s"${GREEN}- $count photos$RESET")
      _ <- Console.printLine(s"${GREEN}- $facesCount people faces$RESET")
      _ <- Console.printLine(s"${GREEN}- $geolocalizedCount geolocalized photos $YELLOW(${count-geolocalizedCount} without GPS infos)$RESET")
      _ <- Console.printLine(s"${YELLOW}- $duplicatedCount duplicated photos$RESET").when(duplicatedCount > 0)
      _ <- Console.printLine(s"${YELLOW}- $missingShootingDate photos without shooting date$RESET").when(missingShootingDate > 0)
      _ <- Console.printLine(s"${YELLOW}- $modifiedCount modified originals$RESET").when(modifiedCount > 0)
      _ <- Console.printLine(s"${RED}- $missingCount missing originals !!$RESET").when(missingCount > 0)
      _ <- Console.printLine(s"${RED}- $invalidShootingDateCount invalid shooting date year (< $shootingDateMinimumValidYear)$RESET").when(invalidShootingDateCount > 0)
      _ <- Console.printLine(s"${RED}- $normalizedFailureCount normalization failures (original file issue)$RESET").when(normalizedFailureCount > 0)
    } yield stats
  }

  val logic = ZIO.logSpan("statistics") {
    val photoStream = PhotoStream.photoLazyStream()
    photoStream
      .runFoldZIO(Statistics())(updateStats)
      .flatMap(reportStats)
      .flatMap(_ => ZIO.logInfo("reported"))
  }
}
