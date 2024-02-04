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
  missingShootingDate: Int = 0
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
                            .someOrElse(true)
    } yield {
      val updatedCount                  = stats.count + 1
      val updatedGeolocalizedCount      = stats.geolocalizedCount + (if (place.isDefined) 1 else 0)
      val updatedNormalizedFailureCount = stats.normalizedFailureCount + (if (hasNormalized) 0 else 1)
      val updatedFacesCount             = stats.facesCount + faces.map(_.count).getOrElse(0)
      val updatedMissingCount           = stats.missingCount + (if (originalFound) 0 else 1)
      val updatedModifiedCount          = stats.modifiedCount + (if (originalModified) 1 else 0)
      val updatedDuplicated             = stats.duplicated + (stats.duplicated.get(filehash) match {
        case None        => filehash -> 1
        case Some(count) => filehash -> (count + 1)
      })
      val updatedMissingShootingDate    = stats.missingShootingDate + (if (shootingDate.isEmpty) 1 else 0)
      stats.copy(
        count = updatedCount,
        geolocalizedCount = updatedGeolocalizedCount,
        normalizedFailureCount = updatedNormalizedFailureCount,
        duplicated = updatedDuplicated,
        facesCount = updatedFacesCount,
        missingCount = updatedMissingCount,
        modifiedCount = updatedModifiedCount,
        missingShootingDate = updatedMissingShootingDate
      )
    }
  }

  def reportStats(stats: Statistics) = {
    val duplicatedCount = stats.duplicated.filter((_, count) => count > 1).size
    for {
      _ <- Console.printLine(s"${UNDERLINED}${BLUE}Photo statistics :$RESET")
      _ <- Console.printLine(s"${GREEN}- ${stats.count} photos$RESET")
      _ <- Console.printLine(s"${GREEN}- ${stats.facesCount} people faces$RESET")
      _ <- Console.printLine(s"${GREEN}- ${stats.geolocalizedCount} geolocalized photos$RESET")
      _ <- Console.printLine(s"${RED}- ${stats.normalizedFailureCount} normalization failures$RESET")
      _ <- Console.printLine(s"${RED}- ${stats.missingCount} missing originals !!$RESET")
      _ <- Console.printLine(s"${YELLOW}- ${stats.modifiedCount} modified originals$RESET")
      _ <- Console.printLine(s"${YELLOW}- $duplicatedCount duplicated photos$RESET")
      _ <- Console.printLine(s"${YELLOW}- ${stats.missingShootingDate} photos without shooting date$RESET")
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
