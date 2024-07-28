package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.NormalizeProcessor
import fr.janalyse.sotohp.store.{LazyPhoto, PhotoStoreService}
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB

import java.time.temporal.ChronoUnit.{MONTHS, YEARS}
import java.time.{Instant, OffsetDateTime}
import scala.io.AnsiColor.*

case class Statistics(
  count: Int = 0,
  geoLocalizedCount: Int = 0,
  deductedGeoLocalizedCount: Int = 0,
  normalizedFailureCount: Int = 0,
  facesCount: Int = 0,
  duplicated: Map[String, Int] = Map.empty,              // TODO potentially high memory usage
  missingCount: Int = 0,
  modifiedCount: Int = 0,
  missingShootingDate: Int = 0,
  invalidShootingDateCount: Int = 0,
  eventsCount: Map[Option[PhotoEvent], Int] = Map.empty, // TODO potentially high memory usage
  oldestDigitalShootingDate: Option[OffsetDateTime] = None,
  newestDigitalShootingDate: Option[OffsetDateTime] = None
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

  val shootingDateMinimumValidYear        = 1826 // https://en.wikipedia.org/wiki/History_of_photography
  val digitalShootingDateMinimumValidYear = 1989 // https://en.wikipedia.org/wiki/Digital_camera

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
      description      <- zphoto.description.some
      originalModified <- ZIO
                            .attempt(source.fileLastModified.toInstant.toEpochMilli != source.original.path.toFile.lastModified())
                            .when(originalFound)
                            .someOrElse(false)
    } yield {
      val updatedCount                     = stats.count + 1
      val updatedGeolocalizedCount         = stats.geoLocalizedCount + (if (place.isDefined) 1 else 0)
      val updatedDeductedGeoLocalizedCount = stats.deductedGeoLocalizedCount + (if (place.exists(_.deducted)) 1 else 0)
      val updatedNormalizedFailureCount    = stats.normalizedFailureCount + (if (hasNormalized) 0 else 1)
      val updatedFacesCount                = stats.facesCount + faces.map(_.count).getOrElse(0)
      val updatedMissingCount              = stats.missingCount + (if (originalFound) 0 else 1)
      val updatedModifiedCount             = stats.modifiedCount + (if (originalModified) 1 else 0)
      val updatedDuplicated                = stats.duplicated + (stats.duplicated.get(filehash) match {
        case None        => filehash -> 1
        case Some(count) => filehash -> (count + 1)
      })
      val updatedMissingShootingDateCount  = stats.missingShootingDate + (if (shootingDate.isEmpty) 1 else 0)
      val updatedInvalidShootingDateCount  = stats.invalidShootingDateCount + (if (shootingDate.exists(_.getYear < shootingDateMinimumValidYear)) 1 else 0)
      val updatedEventsCount               = stats.eventsCount + (stats.eventsCount.get(description.event) match {
        case None        => description.event -> 1
        case Some(count) => description.event -> (count + 1)
      })
      val updatedOldestValidTimestamp      = (stats.oldestDigitalShootingDate, shootingDate) match {
        case (_, Some(date)) if date.getYear < digitalShootingDateMinimumValidYear => stats.oldestDigitalShootingDate
        case (None, Some(date))                                                    => Some(date)
        case (Some(currentOldest), Some(date)) if date.isBefore(currentOldest)     => Some(date)
        case _                                                                     => stats.oldestDigitalShootingDate
      }
      val updatedNewestValidTimestamp      = (stats.newestDigitalShootingDate, shootingDate) match {
        case (None, Some(date))                                               => Some(date)
        case (Some(currentNewest), Some(date)) if date.isAfter(currentNewest) => Some(date)
        case _                                                                => stats.newestDigitalShootingDate
      }
      stats.copy(
        count = updatedCount,
        geoLocalizedCount = updatedGeolocalizedCount,
        deductedGeoLocalizedCount = updatedDeductedGeoLocalizedCount,
        normalizedFailureCount = updatedNormalizedFailureCount,
        duplicated = updatedDuplicated,
        facesCount = updatedFacesCount,
        missingCount = updatedMissingCount,
        modifiedCount = updatedModifiedCount,
        missingShootingDate = updatedMissingShootingDateCount,
        invalidShootingDateCount = updatedInvalidShootingDateCount,
        eventsCount = updatedEventsCount,
        oldestDigitalShootingDate = updatedOldestValidTimestamp,
        newestDigitalShootingDate = updatedNewestValidTimestamp
      )
    }
  }

  def reportStats(stats: Statistics) = {
    import stats.*
    val duplicatedCount                               = stats.duplicated.count((_, count) => count > 1)
    val eventCount                                    = eventsCount.count((k, v) => k.isDefined)
    val (digitalShootingMonths, digitalShootingYears) = (oldestDigitalShootingDate, newestDigitalShootingDate) match {
      case (Some(oldest), Some(newest)) => (MONTHS.between(oldest, newest), YEARS.between(oldest, newest))
      case _                            => (0, 0)
    }
    for {
      _ <- Console.printLine(s"${UNDERLINED}${BLUE}Photo statistics :$RESET")
      _ <- Console.printLine(s"${GREEN}- $count photos$RESET")
      _ <- Console.printLine(s"${GREEN}- $eventCount events")
      _ <- Console.printLine(s"${GREEN}- $digitalShootingMonths months of digital photography ($digitalShootingYears years)$RESET")
      _ <- Console.printLine(s"${GREEN}  - ${oldestDigitalShootingDate.get} -> ${newestDigitalShootingDate.get}$RESET").when(oldestDigitalShootingDate.isDefined && newestDigitalShootingDate.isDefined)
      _ <- Console.printLine(s"${GREEN}- $facesCount people faces$RESET")
      _ <- Console.printLine(s"${GREEN}- $geoLocalizedCount geolocalized photos $YELLOW(${count - geoLocalizedCount - deductedGeoLocalizedCount} without GPS infos)$RESET")
      _ <- Console.printLine(s"${YELLOW}  - ${deductedGeoLocalizedCount} deducted GPS info from time/space nearby photos$RESET")
      _ <- Console.printLine(s"${YELLOW}- $duplicatedCount duplicated photos$RESET").when(duplicatedCount > 0)
      _ <- Console.printLine(s"${YELLOW}- $missingShootingDate photos without shooting date$RESET").when(missingShootingDate > 0)
      _ <- Console.printLine(s"${YELLOW}- $modifiedCount modified originals$RESET").when(modifiedCount > 0)
      _ <- Console.printLine(s"${YELLOW}- ${eventsCount.getOrElse(None, 0)} orphan photos (no related event)$RESET")
      _ <- Console.printLine(s"${RED}- $missingCount missing originals !!$RESET").when(missingCount > 0)
      _ <- Console.printLine(s"${RED}- $invalidShootingDateCount invalid shooting date year (< $shootingDateMinimumValidYear)$RESET").when(invalidShootingDateCount > 0)
      _ <- Console.printLine(s"${RED}- $normalizedFailureCount not loadable photos (probably not supported format or corrupted)$RESET").when(normalizedFailureCount > 0)
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
