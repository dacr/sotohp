package fr.janalyse.sotohp.store

import fr.janalyse.sotohp.model.*
import zio.*
import zio.stream.*
import zio.ZIO.*
import zio.lmdb.*

case class PhotoStoreUserIssue(message: String) extends Exception(message)
case class PhotoStoreSystemIssue(message: String) extends Exception(message)
case class PhotoStoreNotFoundIssue(message: String) extends Exception(message)
type PhotoStoreIssue = PhotoStoreUserIssue | PhotoStoreSystemIssue | PhotoStoreNotFoundIssue
type LMDBIssues      = StorageUserError | StorageSystemError

trait PhotoStoreService {
  // Lazy Photo content stream
  def photoLazyStream(): ZStream[Any, PhotoStoreIssue, LazyPhoto] = photoStateStream().map(LazyPhoto.apply)

  // Global operations
  def photoDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit]

  // Navigate through photos
  def photoFirst(): IO[PhotoStoreIssue, Option[LazyPhoto]]
  def photoNext(after: PhotoId): IO[PhotoStoreIssue, Option[LazyPhoto]]
  def photoPrevious(before: PhotoId): IO[PhotoStoreIssue, Option[LazyPhoto]]
  def photoLast(): IO[PhotoStoreIssue, Option[LazyPhoto]]

  // photo states collection
  def photoStateGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoState]]
  def photoStateStream(): ZStream[Any, PhotoStoreIssue, PhotoState]
  def photoStateContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean]
  def photoStateUpsert(photoId: PhotoId, photoState: PhotoState): IO[PhotoStoreIssue, Unit]
  def photoStateUpdate(photoId: PhotoId, photoStateUpdater: PhotoState => PhotoState): IO[PhotoStoreIssue, Option[PhotoState]]
  def photoStateDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit]
  def photoStateFirst(): IO[PhotoStoreIssue, Option[PhotoState]]
  def photoStateNext(after: PhotoId): IO[PhotoStoreIssue, Option[PhotoState]]
  def photoStatePrevious(before: PhotoId): IO[PhotoStoreIssue, Option[PhotoState]]
  def photoStateLast(): IO[PhotoStoreIssue, Option[PhotoState]]

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

  // photo classifications collection
  def photoClassificationsGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoClassifications]]
  def photoClassificationsContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean]
  def photoClassificationsUpsert(photoId: PhotoId, photoClassifications: PhotoClassifications): IO[PhotoStoreIssue, Unit]
  def photoClassificationsDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit]

  // photo objects collection
  def photoObjectsGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoObjects]]
  def photoObjectsContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean]
  def photoObjectsUpsert(photoId: PhotoId, photoObjects: PhotoObjects): IO[PhotoStoreIssue, Unit]
  def photoObjectsDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit]

  // photo faces collection
  def photoFacesGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoFaces]]
  def photoFacesContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean]
  def photoFacesUpsert(photoId: PhotoId, photoFaces: PhotoFaces): IO[PhotoStoreIssue, Unit]
  def photoFacesDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit]

  // photo face features collection
  def photoFaceFeaturesStream(): Stream[PhotoStoreIssue, (FaceId, FaceFeatures)]
  def photoFaceFeaturesGet(faceId: FaceId): IO[PhotoStoreIssue, Option[FaceFeatures]]
  def photoFaceFeaturesContains(faceId: FaceId): IO[PhotoStoreIssue, Boolean]
  def photoFaceFeaturesUpsert(faceId: FaceId, faceFeatures: FaceFeatures): IO[PhotoStoreIssue, Unit]
  def photoFaceFeaturesDelete(faceId: FaceId): IO[PhotoStoreIssue, Unit]

  // photo description collection
  def photoDescriptionGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoDescription]]
  def photoDescriptionContains(photoId: PhotoId): IO[PhotoStoreIssue, Boolean]
  def photoDescriptionUpsert(photoId: PhotoId, photoDescription: PhotoDescription): IO[PhotoStoreIssue, Unit]
  def photoDescriptionDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit]

}

object PhotoStoreService {
  def photoLazyStream(): ZStream[PhotoStoreService, PhotoStoreIssue, LazyPhoto] = ZStream.serviceWithStream(_.photoLazyStream())

  def photoDelete(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Unit] = serviceWithZIO(_.photoDelete(photoId))

  def photoFirst(): ZIO[PhotoStoreService, PhotoStoreIssue, Option[LazyPhoto]]                   = serviceWithZIO(_.photoFirst())
  def photoNext(after: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Option[LazyPhoto]]      = serviceWithZIO(_.photoNext(after))
  def photoPrevious(before: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Option[LazyPhoto]] = serviceWithZIO(_.photoPrevious(before))
  def photoLast(): ZIO[PhotoStoreService, PhotoStoreIssue, Option[LazyPhoto]]                    = serviceWithZIO(_.photoLast())

  def photoStateGet(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoState]]                                                 = serviceWithZIO(_.photoStateGet(photoId))
  def photoStateStream(): ZStream[PhotoStoreService, PhotoStoreIssue, PhotoState]                                                                  = ZStream.serviceWithStream(_.photoStateStream())
  def photoStateContains(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Boolean]                                                       = serviceWithZIO(_.photoStateContains(photoId))
  def photoStateUpsert(photoId: PhotoId, photoState: PhotoState): ZIO[PhotoStoreService, PhotoStoreIssue, Unit]                                    = serviceWithZIO(_.photoStateUpsert(photoId, photoState))
  def photoStateUpdate(photoId: PhotoId, photoStateUpdater: PhotoState => PhotoState): ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoState]] = serviceWithZIO(_.photoStateUpdate(photoId, photoStateUpdater))
  def photoStateDelete(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Unit]                                                            = serviceWithZIO(_.photoStateDelete(photoId))

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

  def photoClassificationsGet(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoClassifications]]            = serviceWithZIO(_.photoClassificationsGet(photoId))
  def photoClassificationsContains(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Boolean]                            = serviceWithZIO(_.photoClassificationsContains(photoId))
  def photoClassificationsUpsert(photoId: PhotoId, metaData: PhotoClassifications): ZIO[PhotoStoreService, PhotoStoreIssue, Unit] = serviceWithZIO(_.photoClassificationsUpsert(photoId, metaData))
  def photoClassificationsDelete(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Unit]                                 = serviceWithZIO(_.photoClassificationsDelete(photoId))

  def photoObjectsGet(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoObjects]]            = serviceWithZIO(_.photoObjectsGet(photoId))
  def photoObjectsContains(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Boolean]                    = serviceWithZIO(_.photoObjectsContains(photoId))
  def photoObjectsUpsert(photoId: PhotoId, metaData: PhotoObjects): ZIO[PhotoStoreService, PhotoStoreIssue, Unit] = serviceWithZIO(_.photoObjectsUpsert(photoId, metaData))
  def photoObjectsDelete(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Unit]                         = serviceWithZIO(_.photoObjectsDelete(photoId))

  def photoFacesGet(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoFaces]]            = serviceWithZIO(_.photoFacesGet(photoId))
  def photoFacesContains(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Boolean]                  = serviceWithZIO(_.photoFacesContains(photoId))
  def photoFacesUpsert(photoId: PhotoId, metaData: PhotoFaces): ZIO[PhotoStoreService, PhotoStoreIssue, Unit] = serviceWithZIO(_.photoFacesUpsert(photoId, metaData))
  def photoFacesDelete(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Unit]                       = serviceWithZIO(_.photoFacesDelete(photoId))

  def photoFaceFeaturesStream(): ZStream[PhotoStoreService, PhotoStoreIssue, (FaceId, FaceFeatures)]                      = ZStream.serviceWithStream(_.photoFaceFeaturesStream())
  def photoFaceFeaturesGet(faceId: FaceId): ZIO[PhotoStoreService, PhotoStoreIssue, Option[FaceFeatures]]                = serviceWithZIO(_.photoFaceFeaturesGet(faceId))
  def photoFaceFeaturesContains(faceId: FaceId): ZIO[PhotoStoreService, PhotoStoreIssue, Boolean]                        = serviceWithZIO(_.photoFaceFeaturesContains(faceId))
  def photoFaceFeaturesUpsert(faceId: FaceId, faceFeatures: FaceFeatures): ZIO[PhotoStoreService, PhotoStoreIssue, Unit] = serviceWithZIO(_.photoFaceFeaturesUpsert(faceId, faceFeatures))
  def photoFaceFeaturesDelete(faceId: FaceId): ZIO[PhotoStoreService, PhotoStoreIssue, Unit]                             = serviceWithZIO(_.photoFaceFeaturesDelete(faceId))

  def photoDescriptionGet(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Option[PhotoDescription]]            = serviceWithZIO(_.photoDescriptionGet(photoId))
  def photoDescriptionContains(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Boolean]                        = serviceWithZIO(_.photoDescriptionContains(photoId))
  def photoDescriptionUpsert(photoId: PhotoId, metaData: PhotoDescription): ZIO[PhotoStoreService, PhotoStoreIssue, Unit] = serviceWithZIO(_.photoDescriptionUpsert(photoId, metaData))
  def photoDescriptionDelete(photoId: PhotoId): ZIO[PhotoStoreService, PhotoStoreIssue, Unit]                             = serviceWithZIO(_.photoDescriptionDelete(photoId))

  val live: ZLayer[LMDB, LMDBIssues, PhotoStoreService] = ZLayer.fromZIO(
    for {
      lmdb                  <- service[LMDB]
      photoStoreServiceLive <- PhotoStoreServiceLive.setup(lmdb)
    } yield photoStoreServiceLive
  )
}
