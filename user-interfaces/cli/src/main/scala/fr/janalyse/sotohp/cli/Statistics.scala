package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.NormalizeProcessor
import fr.janalyse.sotohp.store.PhotoStoreService
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB
import scala.io.AnsiColor.*

case class Statistics(
  count: Int = 0,
  geolocalizedCount: Int = 0,
  normalizedFailureCount: Int = 0,
  duplicated: Map[String, Int] = Map.empty
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

  def updateStats(stats: Statistics, photo: Photo): Statistics = {
    val updatedCount                  = stats.count + 1
    val updatedGeolocalizedCount      = stats.geolocalizedCount + (if (photo.place.isDefined) 1 else 0)
    val updatedNormalizedFailureCount = stats.normalizedFailureCount + (if (photo.normalized.isDefined) 0 else 1)
    val updatedDuplicated             = stats.duplicated + (stats.duplicated.get(photo.source.fileHash.code) match {
      case None        => photo.source.fileHash.code -> 1
      case Some(count) => photo.source.fileHash.code -> (count + 1)
    })
    stats.copy(
      count = updatedCount,
      geolocalizedCount = updatedGeolocalizedCount,
      normalizedFailureCount = updatedNormalizedFailureCount,
      duplicated = updatedDuplicated
    )
  }

  def reportStats(stats: Statistics) = {
    val duplicatedCount = stats.duplicated.filter((_, count) => count > 1).size
    for {
      _ <- Console.printLine(s"${BLUE_B}Photo statistics :${RESET}")
      _ <- Console.printLine(s"${GREEN}- ${stats.count} photos${RESET}")
      _ <- Console.printLine(s"${GREEN}- ${stats.geolocalizedCount} geolocalized photos${RESET}")
      _ <- Console.printLine(s"${RED}- ${stats.normalizedFailureCount} photos normalized failure${RESET}")
      _ <- Console.printLine(s"${YELLOW}- $duplicatedCount duplicated photos${RESET}")
    } yield stats
  }

  val logic = ZIO.logSpan("statistics") {
    val photoStream = PhotoStream.photoStream()
    photoStream
      .runFold(Statistics())(updateStats)
      .flatMap(reportStats)
      .flatMap(_ => ZIO.logInfo("reported"))
  }
}
