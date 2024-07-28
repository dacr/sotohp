package fr.janalyse.sotohp.cli

import fr.janalyse.sotohp.core.*
import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.processor.NormalizeProcessor
import fr.janalyse.sotohp.store.{LazyPhoto, PhotoStoreIssue, PhotoStoreService}
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
)

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
      if !state.fixed.contains(current.state.photoId)
      foundNearestBeforeWithGPS = photos.take(index).findLast(_.place.exists(!_.deducted))
      foundNearestAfterWithGPS  = photos.drop(index).find(_.place.exists(!_.deducted))
      if foundNearestBeforeWithGPS.isDefined && foundNearestAfterWithGPS.isDefined
      nearestPlaceBefore        = foundNearestBeforeWithGPS.get.place.get
      nearestPlaceAfter         = foundNearestAfterWithGPS.get.place.get
      distance                  = nearestPlaceBefore.distanceTo(nearestPlaceAfter)
      nearestTimestampBefore    = foundNearestBeforeWithGPS.get.state.photoTimestamp.toEpochSecond
      nearestTimestampAfter     = foundNearestAfterWithGPS.get.state.photoTimestamp.toEpochSecond
      elapsed                   = nearestTimestampAfter - nearestTimestampBefore
      if distance < 400 // 400 meters
      if elapsed < 5 * 3600
      toFixPhotoId              = current.state.photoId
      deductedPlace             = nearestPlaceBefore.copy(deducted = true)
    } yield toFixPhotoId -> deductedPlace

    for {
      newFixed <- ZIO.foreach(fixable)(fixPlaceInStorage)
    } yield state.copy(state.fixed ++ newFixed)
  }

  val logic = ZIO.logSpan("PlacesFix") {
    val photoStream  = PhotoStream.photoLazyStream()
    val initialState = QuickFixState()
    for {
      step1State <- photoStream
                      .mapZIO(convert)
                      .sliding(300, 50)
                      .runFoldZIO(initialState)((state, photos) => quickFixByTimeWindow(state, photos))
      // events     <- photoStream
      //                .mapZIO(convert)
      //                .filter(_.event.isDefined)
      //                .map(_.event.get)
      //                .runFold(Set.empty[PhotoEvent])((set, event) => set + event)
      //      step2State <- photoStream
      //                      .mapZIO(convert)
      //                      .filter(_.event.isDefined)
      //                      .runFoldZIO(step1State)((state, photos) => ???)
      finalState  = step1State
      _          <- ZIO.logInfo(s"${finalState.fixed.size} places fixed using timeWindow")
    } yield ()
  }
}
