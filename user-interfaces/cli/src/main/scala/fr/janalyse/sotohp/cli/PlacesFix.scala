package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.NormalizeProcessor
import fr.janalyse.sotohp.store.{LazyPhoto, PhotoStoreIssue, PhotoStoreService, PhotoStoreSystemIssue}
import zio.*
import zio.config.typesafe.*
import zio.lmdb.LMDB

import java.io.IOException
import java.time.{Instant, OffsetDateTime}
import scala.io.AnsiColor.*

case class QuickFixState(
  fixed: Set[PhotoId] = Set.empty
)

case class QuickFixPhoto(
  state: PhotoState,
  event: Option[PhotoEvent] = None,
  place: Option[PhotoPlace] = None
) {
  def epoch = state.photoTimestamp.toEpochSecond
  def id    = state.photoId
}

object PlacesFix extends ZIOAppDefault with CommonsCLI {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(TypesafeConfigProvider.fromTypesafeConfig(com.typesafe.config.ConfigFactory.load()))

  override def run =
    logic
      .provide(
        LMDB.liveWithDatabaseName("photos"),
        PhotoStoreService.live,
        Scope.default
      )

  def convert(input: LazyPhoto): ZIO[PhotoStoreService, PhotoStoreIssue, QuickFixPhoto] = {
    for {
      foundPlace <- input.place
      foundState  = input.state
      foundEvent <- input.description.map(_.flatMap(_.event))
    } yield QuickFixPhoto(state = foundState, event = foundEvent, place = foundPlace)
  }

  def fixPlaceInStorage(photoId: PhotoId, photoPlace: PhotoPlace): ZIO[PhotoStoreService, PhotoStoreIssue, PhotoId] = {
    for {
      _ <- PhotoStoreService.photoPlaceUpsert(photoId, photoPlace)
      _ <- PhotoStoreService.photoStateUpdate(photoId, s => s.copy(lastSynchronized = None))
    } yield photoId
  }

  def quickFixByTimeWindow(state: QuickFixState, photos: Chunk[QuickFixPhoto]): ZIO[PhotoStoreService, PhotoStoreIssue, QuickFixState] = {
    val fixable = for {
      index                    <- photos.indices
      current                   = photos(index)
      if current.place.isEmpty
      if !state.fixed.contains(current.id)
      foundNearestBeforeWithGPS = photos.take(index).findLast(_.place.exists(!_.deducted))
      foundNearestAfterWithGPS  = photos.drop(index).find(_.place.exists(!_.deducted))
      if foundNearestBeforeWithGPS.isDefined && foundNearestAfterWithGPS.isDefined
      nearestPlaceBefore        = foundNearestBeforeWithGPS.get.place.get
      nearestPlaceAfter         = foundNearestAfterWithGPS.get.place.get
      distance                  = nearestPlaceBefore.distanceTo(nearestPlaceAfter)
      nearestTimestampBefore    = foundNearestBeforeWithGPS.get.epoch
      nearestTimestampAfter     = foundNearestAfterWithGPS.get.epoch
      elapsed                   = nearestTimestampAfter - nearestTimestampBefore
      if distance < 500 // 500 meters
      if elapsed < 5 * 3600 // 5 hours
      toFixPhotoId              = current.id
      deductedPlace             = nearestPlaceBefore.copy(deducted = true)
    } yield toFixPhotoId -> deductedPlace

    for {
      newFixed <- ZIO.foreach(fixable)(fixPlaceInStorage)
    } yield state.copy(state.fixed ++ newFixed)
  }

  def quickFixByFirstTakenPhotosWithoutPlace(state: QuickFixState, photos: Chunk[QuickFixPhoto]): ZIO[PhotoStoreService, PhotoStoreIssue, QuickFixState] = {
    val fixable = photos.toList match {
      case Nil                                                         => Nil
      case ref :: Nil                                                  => Nil
      case ref :: _ :: Nil                                             => Nil
      case ref :: others if (others.head.epoch - ref.epoch) > 6 * 3600 =>
        others.takeWhile(o => o.place.isEmpty) match {
          case Nil   => Nil
          case toFix =>
            others.find(_.place.isDefined).filter(p => (p.epoch - toFix.head.epoch) < 30 * 60).flatMap(_.place) match {
              case None        => Nil
              case Some(found) => toFix.map(p => p.id -> found)
            }
        }

      case _ => Nil
    }

    for {
      newFixed <- ZIO.foreach(fixable)(fixPlaceInStorage)
      // newFixed <- ZIO.foreach(fixable)(p => Console.printLine(p).as(p._1)).mapError(err => PhotoStoreSystemIssue(err.getMessage))
    } yield state.copy(state.fixed ++ newFixed)
  }

  val logic = ZIO.logSpan("PlacesFix") {
    val photoStream = PhotoStream.photoLazyStream()
    for {
      _          <- ZIO.logInfo("step 1 - quickFixByTimeWindow")
      step1State <- photoStream
                      .mapZIO(convert)
                      .sliding(200, 50)
                      .runFoldZIO(QuickFixState())((state, photos) => quickFixByTimeWindow(state, photos))
      _          <- ZIO.logInfo("step 2 - quickFixByFirstTakenPhotosWithoutPlace")
      step2state <- photoStream
                      .mapZIO(convert)
                      .sliding(100, 1)
                      .runFoldZIO(QuickFixState())((state, photos) => quickFixByFirstTakenPhotosWithoutPlace(state, photos))
      //                .filter(_.event.isDefined)
      //                .map(_.event.get)
      //                .runFold(Set.empty[PhotoEvent])((set, event) => set + event)
      //      step2State <- photoStream
      //                      .mapZIO(convert)
      //                      .filter(_.event.isDefined)
      //                      .runFoldZIO(step1State)((state, photos) => ???)
      fixed       = step1State.fixed ++ step2state.fixed
      _          <- ZIO.logInfo(s"${fixed.size} places fixed using timeWindow")
    } yield ()
  }
}
