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
    } yield QuickFixPhoto(state = foundState, place = foundPlace)
  }

  def quickFix(state: QuickFixState, photos: Chunk[QuickFixPhoto]): ZIO[PhotoStoreService, IOException, QuickFixState] = {
    val fixable = for {
      index                    <- photos.indices
      current                   = photos(index)
      if current.place.isEmpty
      if !state.fixed.contains(current.state.photoId)
      foundNearestBeforeWithGPS = photos.take(index).findLast(_.place.isDefined)
      foundNearestAfterWithGPS  = photos.drop(index).find(_.place.isDefined)
      if foundNearestBeforeWithGPS.isDefined && foundNearestAfterWithGPS.isDefined
      distance                  = foundNearestBeforeWithGPS.get.place.get.distanceTo(foundNearestAfterWithGPS.get.place.get)
      elapsed                   = foundNearestAfterWithGPS.get.state.photoTimestamp.toEpochSecond - foundNearestBeforeWithGPS.get.state.photoTimestamp.toEpochSecond
      if distance < 200 // 100 meters
      if elapsed < 4 * 3600
    } yield {
      println(s"${distance}m ${elapsed}s")
      current.state.photoId
    }

    ZIO.succeed(state.copy(state.fixed ++ fixable))
  }

  val logic = ZIO.logSpan("PlacesFix") {
    val photoStream = PhotoStream.photoLazyStream()
    photoStream
      .mapZIO(convert)
      .sliding(300, 50)
      .runFoldZIO(QuickFixState())((state, photos) => quickFix(state, photos))
      .flatMap(state => ZIO.logInfo(s"${state.fixed.size} places fixed"))
  }
}
