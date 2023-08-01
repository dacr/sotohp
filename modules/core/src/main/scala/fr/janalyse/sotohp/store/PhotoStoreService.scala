package fr.janalyse.sotohp.store

import fr.janalyse.sotohp.model.*
import zio.*
import zio.ZIO.*
import zio.lmdb.*

case class PhotoStoreUserIssue(message: String)
case class PhotoStoreSystemIssue(message: String)
type PhotoStoreIssue = PhotoStoreUserIssue | PhotoStoreSystemIssue
type LMDBIssues      = StorageUserError | StorageSystemError

trait PhotoStoreService {
  // photo states collection
  def photoStateGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoState]]
  def photoStateUpsert(photoId: PhotoId, photoState: PhotoState): IO[PhotoStoreIssue, Unit]
  def photoStateDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit]

  // photo sources collection
  def photoSourceGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoSource]]
  def photoSourceUpsert(photoId: PhotoId, photoSource: PhotoSource): IO[PhotoStoreIssue, Unit]
  def photoSourceDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit]

  // photos metadata collection
  def photoMetaDataGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoMetaData]]
  def photoMetaDataUpsert(photoId: PhotoId, metaData: PhotoMetaData): IO[PhotoStoreIssue, Unit]
  def photoMetaDataDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit]

  // photos places collection
  def photoPlaceGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoPlace]]
  def photoPlaceUpsert(photoId: PhotoId, place: PhotoPlace): IO[PhotoStoreIssue, Unit]
  def photoPlaceDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit]
}

object PhotoStoreService {
  def photoStateGet(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoState]]              = serviceWithZIO(_.photoStateGet(photoId))
  def photoStateUpsert(photoId: PhotoId, photoState: PhotoState): ZIO[PhotoStoreService, PhotoStoreIssue, Unit] = serviceWithZIO(_.photoStateUpsert(photoId, photoState))
  def photoStateDelete(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Unit]                         = serviceWithZIO(_.photoStateDelete(photoId))

  def photoSourceGet(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoSource]]               = serviceWithZIO(_.photoSourceGet(photoId))
  def photoSourceUpsert(photoId: PhotoId, photoSource: PhotoSource): ZIO[PhotoStoreService, PhotoStoreIssue, Unit] = serviceWithZIO(_.photoSourceUpsert(photoId, photoSource))
  def photoSourceDelete(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Unit]                           = serviceWithZIO(_.photoStateDelete(photoId))

  def photoMetaDataGet(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoMetaData]]            = serviceWithZIO(_.photoMetaDataGet(photoId))
  def photoMetaDataUpsert(photoId: PhotoId, metaData: PhotoMetaData): ZIO[PhotoStoreService, PhotoStoreIssue, Unit] = serviceWithZIO(_.photoMetaDataUpsert(photoId, metaData))
  def photoMetaDataDelete(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Unit]                          = serviceWithZIO(_.photoMetaDataDelete(photoId))

  def photoPlaceGet(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoPlace]]         = serviceWithZIO(_.photoPlaceGet(photoId))
  def photoPlaceUpsert(photoId: PhotoId, place: PhotoPlace): ZIO[PhotoStoreService, PhotoStoreIssue, Unit] = serviceWithZIO(_.photoPlaceUpsert(photoId, place))
  def photoPlaceDelete(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Unit]                    = serviceWithZIO(_.photoPlaceDelete(photoId))

  val live: ZLayer[LMDB, LMDBIssues, PhotoStoreService] = ZLayer.fromZIO(
    for {
      lmdb                  <- service[LMDB]
      photoStoreServiceLive <- PhotoStoreServiceLive.setup(lmdb)
    } yield photoStoreServiceLive
  )
}
