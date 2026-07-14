package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.NormalizeProcessor
import fr.janalyse.sotohp.search.SearchService
import fr.janalyse.sotohp.service.{MediaService, MediaTuple}
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
  userFixedLocationCount: Int = 0,
  userFixedLocationCountByCamera: Map[Option[CameraName], Int] = Map.empty, // TODO potentially high memory usage
  normalizedFailureCount: Int = 0,
  facesCount: Int = 0,
  duplicated: Map[Option[String], Int] = Map.empty,     // TODO potentially high memory usage
  countByFocal: Map[Int, Int] = Map.empty,
  missingCount: Int = 0,
  modifiedCount: Int = 0,
  missingShootingDate: Int = 0,
  invalidShootingDateCount: Int = 0,
  bagsCount: Map[Option[BagName], Int] = Map.empty, // TODO potentially high memory usage
  countByCamera: Map[CameraName, Int] = Map.empty,  // camera name undefined photos are ignored
  oldestDigitalShootingDate: Option[OffsetDateTime] = None,
  newestDigitalShootingDate: Option[OffsetDateTime] = None,
  missing: List[Original] = Nil,
  notLoadable: List[Original] = Nil
)

object Statistics extends CommonsCLI {

  override def run =
    logic
      .provide(
        LMDB.live,
        SearchService.live,
        MediaService.live,
        Scope.default
      )

  val shootingDateMinimumValidYear = 1826 // https://en.wikipedia.org/wiki/History_of_photography
  // val digitalShootingDateMinimumValidYear = 1989 // https://en.wikipedia.org/wiki/Digital_camera
  val focalRangeStep = 5

  def updateStats(stats: Statistics, mediaTuple: MediaTuple) = {
    val media = mediaTuple.media
    for {
      state            <- MediaService.stateGet(media.original.id)
      place             = media.original.location
      faces            <- MediaService.originalFaces(media.original.id)
      normalized       <- MediaService.originalNormalized(media.original.id)
      hasNormalized     = normalized.exists(_.status.successful)
      shootingDate      = media.shootDateTime
                            .orElse(media.original.cameraShootDateTime)
                            .map(_.offsetDateTime)
      fileHash          = state.flatMap(_.originalHash.map(_.code))
      originalFound    <- ZIO.attempt(media.original.absoluteMediaPath.toFile.exists())
      bags              = media.bag.toList
      originalModified <- ZIO
                            .attempt(media.original.fileLastModified.offsetDateTime.toInstant.toEpochMilli != media.original.absoluteMediaPath.toFile.lastModified())
                            .when(originalFound)
                            .someOrElse(false)
    } yield {
      val updatedCount                     = stats.count + 1
      val updatedGeolocalizedCount         = stats.geoLocalizedCount + (if (place.isDefined) 1 else 0)
      val updatedDeductedGeoLocalizedCount = stats.deductedGeoLocalizedCount + (if (media.deductedLocation.isDefined) 1 else 0)

      // a location is considered "fixed" when the original carried its own GPS and the user overrode it
      val isUserFixedLocation                    = media.original.location.isDefined && media.userDefinedLocation.isDefined
      val updatedUserFixedLocationCount          = stats.userFixedLocationCount + (if (isUserFixedLocation) 1 else 0)
      val updatedUserFixedLocationCountByCamera  =
        if (!isUserFixedLocation) stats.userFixedLocationCountByCamera
        else {
          val camera = media.original.cameraName
          stats.userFixedLocationCountByCamera + (camera -> (stats.userFixedLocationCountByCamera.getOrElse(camera, 0) + 1))
        }
      val updatedNormalizedFailureCount    = stats.normalizedFailureCount + (if (hasNormalized) 0 else 1)
      val updatedFacesCount                = stats.facesCount + faces.map(_.faces.size).getOrElse(0)
      val updatedMissingCount              = stats.missingCount + (if (originalFound) 0 else 1)
      val updatedModifiedCount             = stats.modifiedCount + (if (originalModified) 1 else 0)
      val updatedMissingShootingDateCount  = stats.missingShootingDate + (if (shootingDate.isEmpty) 1 else 0)
      val updatedInvalidShootingDateCount  = stats.invalidShootingDateCount + (if (shootingDate.exists(_.getYear < shootingDateMinimumValidYear)) 1 else 0)

      val updatedDuplicated = stats.duplicated + (stats.duplicated.get(fileHash) match {
        case None        => fileHash -> 1
        case Some(count) => fileHash -> (count + 1)
      })
      val updatedCountByFocal = media.original.focalLength.filter(f => f.selected > 0d && f.selected < 999d) match {
        case None              => stats.countByFocal
        case Some(focalLength) =>
          val f = (focalLength.selected / focalRangeStep).toInt * focalRangeStep
          stats.countByFocal + (stats.countByFocal.get(f) match {
            case None        => f -> 1
            case Some(count) => f -> (count + 1)
          })
      }

      val updatedCountByCamera = media.original.cameraName match {
        case None         => stats.countByCamera
        case Some(camera) => stats.countByCamera + (camera -> (stats.countByCamera.getOrElse(camera, 0) + 1))
      }

      val updatedBagsCount = stats.bagsCount ++ (bags match {
        case Nil       =>
          (stats.bagsCount.get(None) match {
            case None        => None -> 1
            case Some(count) => None -> (count + 1)
          }) :: Nil
        case foundBags =>
          foundBags.map(bag =>
            (stats.bagsCount.get(Some(bag.name)) match {
              case None        => Some(bag.name) -> 1
              case Some(count) => Some(bag.name) -> (count + 1)
            })
          )
      })

      val updatedOldestValidTimestamp = (stats.oldestDigitalShootingDate, shootingDate) match {
        // case (_, Some(date)) if date.getYear < digitalShootingDateMinimumValidYear => stats.oldestDigitalShootingDate
        case (None, Some(date))                                                => Some(date)
        case (Some(currentOldest), Some(date)) if date.isBefore(currentOldest) => Some(date)
        case _                                                                 => stats.oldestDigitalShootingDate
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
        userFixedLocationCount = updatedUserFixedLocationCount,
        userFixedLocationCountByCamera = updatedUserFixedLocationCountByCamera,
        normalizedFailureCount = updatedNormalizedFailureCount,
        duplicated = updatedDuplicated,
        countByFocal = updatedCountByFocal,
        facesCount = updatedFacesCount,
        missingCount = updatedMissingCount,
        modifiedCount = updatedModifiedCount,
        missingShootingDate = updatedMissingShootingDateCount,
        invalidShootingDateCount = updatedInvalidShootingDateCount,
        bagsCount = updatedBagsCount,
        countByCamera = updatedCountByCamera,
        oldestDigitalShootingDate = updatedOldestValidTimestamp,
        newestDigitalShootingDate = updatedNewestValidTimestamp,
        missing = if (originalFound) stats.missing else media.original :: stats.missing,
        notLoadable = if (hasNormalized) stats.notLoadable else media.original :: stats.notLoadable
      )
    }
  }

  private def reportStats(stats: Statistics) = {
    import stats.*
    val duplicatedCount = stats.duplicated.count((_, count) => count > 1)
    val bagCount        = bagsCount.count((k, v) => k.isDefined)

    val (digitalShootingMonths, digitalShootingYears) = (oldestDigitalShootingDate, newestDigitalShootingDate) match {
      case (Some(oldest), Some(newest)) => (MONTHS.between(oldest, newest), YEARS.between(oldest, newest))
      case _                            => (0, 0)
    }
    val focals =
      stats.countByFocal
        .toList
        .filter(_._2 >= 1000)
        .sortBy(- _._2)
    for {
      _ <- Console.printLine("-----------------------------------------------------------------------------------------")
      _ <- ZIO.foreachDiscard(stats.missing)(original => Console.printLine(s"${RED}Missing original : ${original.mediaPath.path}$RESET"))
      _ <- Console.printLine("-----------------------------------------------------------------------------------------")
      _ <- Console.printLine(s"${UNDERLINED}${BLUE}Photo statistics :$RESET")
      _ <- Console.printLine(s"${GREEN}- $count photos$RESET")
      _ <- Console.printLine(s"${GREEN}- $bagCount bags")
      _ <- Console.printLine(s"${GREEN}- $digitalShootingMonths months of digital/numerized photography ($digitalShootingYears years)$RESET")
      _ <- Console.printLine(s"${GREEN}  - ${oldestDigitalShootingDate.get} -> ${newestDigitalShootingDate.get}$RESET").when(oldestDigitalShootingDate.isDefined && newestDigitalShootingDate.isDefined)
      _ <- Console.printLine(s"${GREEN}- $facesCount people faces$RESET")
      _ <- Console.printLine(s"${GREEN}- $geoLocalizedCount geolocalized photos $YELLOW(${count - geoLocalizedCount - deductedGeoLocalizedCount} without GPS infos)$RESET")
      _ <- Console.printLine(s"${YELLOW}  - ${deductedGeoLocalizedCount} deducted GPS info from time/space nearby photos$RESET")
      _ <- Console.printLine(s"${YELLOW}- $userFixedLocationCount user-fixed GPS locations (original GPS overridden by the user)$RESET").when(userFixedLocationCount > 0)
      _ <- ZIO
             .foreachDiscard(userFixedLocationCountByCamera.toList.sortBy((_, cnt) => -cnt)) { (camera, cnt) =>
               Console.printLine(s"${YELLOW}  - ${camera.map(_.text).getOrElse("unknown camera")} : $cnt$RESET")
             }
             .when(userFixedLocationCount > 0)
      _ <- Console.printLine(s"${YELLOW}- $duplicatedCount duplicated photos$RESET").when(duplicatedCount > 0)
      _ <- Console.printLine(s"${YELLOW}- $missingShootingDate photos without shooting date (coming from camera or user given)$RESET").when(missingShootingDate > 0)
      _ <- Console.printLine(s"${YELLOW}- $modifiedCount modified originals$RESET").when(modifiedCount > 0)
      _ <- Console.printLine(s"${YELLOW}- ${bagsCount.getOrElse(None, 0)} orphan photos (no related bag)$RESET")
      _ <- Console.printLine(s"${RED}- $missingCount missing originals !!$RESET").when(missingCount > 0)
      _ <- Console.printLine(s"${RED}- $invalidShootingDateCount invalid shooting date year (< $shootingDateMinimumValidYear)$RESET").when(invalidShootingDateCount > 0)
      _ <- Console.printLine(s"${RED}- $normalizedFailureCount not loadable photos (probably not supported format or corrupted)$RESET").when(normalizedFailureCount > 0)
      _ <- ZIO.foreachDiscard(stats.notLoadable)(original => Console.printLine(s"${RED}  - ${original.absoluteMediaPath}$RESET"))
      _ <- Console.printLine("-----------------------------------------------------------------------------------------")
      _ <- Console.printLine(s"${UNDERLINED}${BLUE}Photo count by camera :$RESET")
      _ <- Console.printLine(s"${GREEN}${stats.countByCamera.toList.sortBy((_, c) => -c).map { case (camera, c) => s"${camera.text} : $c" }.mkString("\n")}$RESET")
      _ <- Console.printLine("-----------------------------------------------------------------------------------------")
      _ <- Console.printLine(s"${UNDERLINED}${BLUE}Photo count by focal length :$RESET")
      _ <- Console.printLine(s"${GREEN}${focals.map { case (f, c) => s"${f}->${f+focalRangeStep-1}mm : $c" }.mkString("\n")}$RESET")
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
