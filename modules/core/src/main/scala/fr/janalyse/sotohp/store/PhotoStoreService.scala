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
  def photoStateContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean]
  def photoStateUpsert(photoId: PhotoId, photoState: PhotoState): IO[PhotoStoreIssue, Unit]
  def photoStateDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit]

  // photo sources collection
  def photoSourceGet(originalId: OriginalId): IO[PhotoStoreIssue, Option[PhotoSource]]
  def photoSourceContains(originalId: OriginalId): IO[PhotoStoreIssue, Boolean]
  def photoSourceUpsert(originalId: OriginalId, photoSource: PhotoSource): IO[PhotoStoreIssue, Unit]
  def photoSourceDelete(originalId: OriginalId): IO[PhotoStoreIssue, Unit]

  // photos metadata collection
  def photoMetaDataGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoMetaData]]
  def photoMetaDataContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean]
  def photoMetaDataUpsert(photoId: PhotoId, metaData: PhotoMetaData): IO[PhotoStoreIssue, Unit]
  def photoMetaDataDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit]

  // photos places collection
  def photoPlaceGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoPlace]]
  def photoPlaceContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean]
  def photoPlaceUpsert(photoId: PhotoId, place: PhotoPlace): IO[PhotoStoreIssue, Unit]
  def photoPlaceDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit]

  // photos miniatures collection
  def photoMiniaturesGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[Miniatures]]
  def photoMiniaturesContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean]
  def photoMiniaturesUpsert(photoId: PhotoId, miniatures: Miniatures): IO[PhotoStoreIssue, Unit]
  def photoMiniaturesDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit]

  // Normalized photos collection
  def photoNormalizedGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[NormalizedPhoto]]
  def photoNormalizedContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean]
  def photoNormalizedUpsert(photoId: PhotoId, normalized: NormalizedPhoto): IO[PhotoStoreIssue, Unit]
  def photoNormalizedDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit]
}

object PhotoStoreService {
  def photoStateGet(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoState]]              = serviceWithZIO(_.photoStateGet(photoId))
  def photoStateContains(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Boolean]                    = serviceWithZIO(_.photoStateContains(photoId))
  def photoStateUpsert(photoId: PhotoId, photoState: PhotoState): ZIO[PhotoStoreService, PhotoStoreIssue, Unit] = serviceWithZIO(_.photoStateUpsert(photoId, photoState))
  def photoStateDelete(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Unit]                         = serviceWithZIO(_.photoStateDelete(photoId))

  def photoSourceGet(originalId: OriginalId): ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoSource]]               = serviceWithZIO(_.photoSourceGet(originalId))
  def photoSourceContains(OriginalId: OriginalId): ZIO[PhotoStoreService, PhotoStoreIssue, Boolean]                      = serviceWithZIO(_.photoSourceContains(OriginalId))
  def photoSourceUpsert(originalId: OriginalId, photoSource: PhotoSource): ZIO[PhotoStoreService, PhotoStoreIssue, Unit] = serviceWithZIO(_.photoSourceUpsert(originalId, photoSource))
  def photoSourceDelete(originalId: OriginalId): ZIO[PhotoStoreService, PhotoStoreIssue, Unit]                           = serviceWithZIO(_.photoSourceDelete(originalId))

  def photoMetaDataGet(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoMetaData]]            = serviceWithZIO(_.photoMetaDataGet(photoId))
  def photoMetaDataContains(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Boolean]                     = serviceWithZIO(_.photoMetaDataContains(photoId))
  def photoMetaDataUpsert(photoId: PhotoId, metaData: PhotoMetaData): ZIO[PhotoStoreService, PhotoStoreIssue, Unit] = serviceWithZIO(_.photoMetaDataUpsert(photoId, metaData))
  def photoMetaDataDelete(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Unit]                          = serviceWithZIO(_.photoMetaDataDelete(photoId))

  def photoPlaceGet(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoPlace]]         = serviceWithZIO(_.photoPlaceGet(photoId))
  def photoPlaceContains(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Boolean]               = serviceWithZIO(_.photoPlaceContains(photoId))
  def photoPlaceUpsert(photoId: PhotoId, place: PhotoPlace): ZIO[PhotoStoreService, PhotoStoreIssue, Unit] = serviceWithZIO(_.photoPlaceUpsert(photoId, place))
  def photoPlaceDelete(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Unit]                    = serviceWithZIO(_.photoPlaceDelete(photoId))

  def photoMiniaturesGet(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Option[Miniatures]]              = serviceWithZIO(_.photoMiniaturesGet(photoId))
  def photoMiniaturesContains(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Boolean]                    = serviceWithZIO(_.photoMiniaturesContains(photoId))
  def photoMiniaturesUpsert(photoId: PhotoId, miniatures: Miniatures): ZIO[PhotoStoreService, PhotoStoreIssue, Unit] = serviceWithZIO(_.photoMiniaturesUpsert(photoId, miniatures))
  def photoMiniaturesDelete(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Unit]                         = serviceWithZIO(_.photoMiniaturesDelete(photoId))

  def photoNormalizedGet(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Option[NormalizedPhoto]]              = serviceWithZIO(_.photoNormalizedGet(photoId))
  def photoNormalizedContains(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Boolean]                         = serviceWithZIO(_.photoNormalizedContains(photoId))
  def photoNormalizedUpsert(photoId: PhotoId, normalized: NormalizedPhoto): ZIO[PhotoStoreService, PhotoStoreIssue, Unit] = serviceWithZIO(_.photoNormalizedUpsert(photoId, normalized))
  def photoNormalizedDelete(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Unit]                              = serviceWithZIO(_.photoNormalizedDelete(photoId))

  val live: ZLayer[LMDB, LMDBIssues, PhotoStoreService] = ZLayer.fromZIO(
    for {
      lmdb                  <- service[LMDB]
      photoStoreServiceLive <- PhotoStoreServiceLive.setup(lmdb)
    } yield photoStoreServiceLive
  )
}
