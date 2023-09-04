package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.NormalizeProcessor
import fr.janalyse.sotohp.store.{PhotoStoreService, ZPhoto}
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

  def updateStats(stats: Statistics, zphoto: ZPhoto) = {
    for {
      source        <- zphoto.source.some
      place         <- zphoto.place
      hasNormalized <- zphoto.hasNormalized
      filehash       = source.fileHash.code
    } yield {
      val updatedCount                  = stats.count + 1
      val updatedGeolocalizedCount      = stats.geolocalizedCount + (if (place.isDefined) 1 else 0)
      val updatedNormalizedFailureCount = stats.normalizedFailureCount + (if (hasNormalized) 0 else 1)
      val updatedDuplicated             = stats.duplicated + (stats.duplicated.get(filehash) match {
        case None        => filehash -> 1
        case Some(count) => filehash -> (count + 1)
      })
      stats.copy(
        count = updatedCount,
        geolocalizedCount = updatedGeolocalizedCount,
        normalizedFailureCount = updatedNormalizedFailureCount,
        duplicated = updatedDuplicated
      )
    }
  }

  def reportStats(stats: Statistics) = {
    val duplicatedCount = stats.duplicated.filter((_, count) => count > 1).size
    for {
      _ <- Console.printLine(s"${UNDERLINED}${BLUE}Photo statistics :${RESET}")
      _ <- Console.printLine(s"${GREEN}- ${stats.count} photos${RESET}")
      _ <- Console.printLine(s"${GREEN}- ${stats.geolocalizedCount} geolocalized photos${RESET}")
      _ <- Console.printLine(s"${RED}- ${stats.normalizedFailureCount} normalization failures${RESET}")
      _ <- Console.printLine(s"${YELLOW}- $duplicatedCount duplicated photos${RESET}")
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
