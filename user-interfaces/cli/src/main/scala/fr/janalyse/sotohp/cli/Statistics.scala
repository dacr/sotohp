package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.NormalizeProcessor
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.MediaService
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
  duplicated: Map[Option[String], Int] = Map.empty, // TODO potentially high memory usage
  missingCount: Int = 0,
  modifiedCount: Int = 0,
  missingShootingDate: Int = 0,
  invalidShootingDateCount: Int = 0,
  eventsCount: Map[Option[EventName], Int] = Map.empty, // TODO potentially high memory usage
  oldestDigitalShootingDate: Option[OffsetDateTime] = None,
  newestDigitalShootingDate: Option[OffsetDateTime] = None
)

object Statistics extends CommonsCLI {

  override def run =
    logic
      .provide(
        configProviderLayer >>> LMDB.live,
        configProviderLayer >>> SearchService.live,
        MediaService.live,
        Scope.default,
        configProviderLayer
      )

  val shootingDateMinimumValidYear        = 1826 // https://en.wikipedia.org/wiki/History_of_photography
  val digitalShootingDateMinimumValidYear = 1989 // https://en.wikipedia.org/wiki/Digital_camera

  def updateStats(stats: Statistics, media: Media) = {
    for {
      state            <- MediaService.stateGet(media.original.id)
      place             = media.original.location
      faces            <- MediaService.faces(media.original.id)
      normalized       <- MediaService.normalized(media.original.id)
      hasNormalized     = normalized.exists(_.status.successful)
      shootingDate      = media.shootDateTime.orElse(media.original.cameraShootDateTime).map(_.offsetDateTime)
      fileHash          = state.flatMap(_.originalHash.map(_.code))
      originalFound    <- ZIO.attempt(media.original.mediaPath.path.toFile.exists())
      events            = media.events
      originalModified <- ZIO
                            .attempt(media.original.fileLastModified.offsetDateTime.toInstant.toEpochMilli != media.original.mediaPath.path.toFile.lastModified())
                            .when(originalFound)
                            .someOrElse(false)
    } yield {
      val updatedCount                     = stats.count + 1
      val updatedGeolocalizedCount         = stats.geoLocalizedCount + (if (place.isDefined) 1 else 0)
      val updatedDeductedGeoLocalizedCount = stats.deductedGeoLocalizedCount + (if (media.deductedLocation.isDefined) 1 else 0)
      val updatedNormalizedFailureCount    = stats.normalizedFailureCount + (if (hasNormalized) 0 else 1)
      val updatedFacesCount                = stats.facesCount + faces.map(_.faces.size).getOrElse(0)
      val updatedMissingCount              = stats.missingCount + (if (originalFound) 0 else 1)
      val updatedModifiedCount             = stats.modifiedCount + (if (originalModified) 1 else 0)
      val updatedDuplicated                = stats.duplicated + (stats.duplicated.get(fileHash) match {
        case None        => fileHash -> 1
        case Some(count) => fileHash -> (count + 1)
      })
      val updatedMissingShootingDateCount  = stats.missingShootingDate + (if (shootingDate.isEmpty) 1 else 0)
      val updatedInvalidShootingDateCount  = stats.invalidShootingDateCount + (if (shootingDate.exists(_.getYear < shootingDateMinimumValidYear)) 1 else 0)

      val updatedEventsCount = stats.eventsCount ++ (events match {
        case Nil         =>
          (stats.eventsCount.get(None) match {
            case None        => None -> 1
            case Some(count) => None -> (count + 1)
          }) :: Nil
        case foundEvents =>
          foundEvents.map(event =>
            (stats.eventsCount.get(Some(event.name)) match {
              case None        => Some(event.name) -> 1
              case Some(count) => Some(event.name) -> (count + 1)
            })
          )
      })

      val updatedOldestValidTimestamp = (stats.oldestDigitalShootingDate, shootingDate) match {
        case (_, Some(date)) if date.getYear < digitalShootingDateMinimumValidYear => stats.oldestDigitalShootingDate
        case (None, Some(date))                                                    => Some(date)
        case (Some(currentOldest), Some(date)) if date.isBefore(currentOldest)     => Some(date)
        case _                                                                     => stats.oldestDigitalShootingDate
      }
      val updatedNewestValidTimestamp = (stats.newestDigitalShootingDate, shootingDate) match {
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

  private def reportStats(stats: Statistics) = {
    import stats.*
    val duplicatedCount = stats.duplicated.count((_, count) => count > 1)
    val eventCount      = eventsCount.count((k, v) => k.isDefined)

    val (digitalShootingMonths, digitalShootingYears) = (oldestDigitalShootingDate, newestDigitalShootingDate) match {
      case (Some(oldest), Some(newest)) => (MONTHS.between(oldest, newest), YEARS.between(oldest, newest))
      case _                            => (0, 0)
    }
    for {
      _ <- Console.printLine(s"${UNDERLINED}${BLUE}Photo statistics :$RESET")
      _ <- Console.printLine(s"${GREEN}- $count photos$RESET")
      _ <- Console.printLine(s"${GREEN}- $eventCount events")
      _ <- Console.printLine(s"${GREEN}- $digitalShootingMonths months of digital/numerized photography ($digitalShootingYears years)$RESET")
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
    val mediaStream = MediaService.mediaList()
    mediaStream
      .runFoldZIO(Statistics())(updateStats)
      .flatMap(reportStats)
      .flatMap(_ => ZIO.logInfo("reported"))
  }
}
