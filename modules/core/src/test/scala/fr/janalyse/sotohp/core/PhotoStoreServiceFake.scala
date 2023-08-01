package fr.janalyse.sotohp.core

import fr.janalyse.sotohp.model.*
import fr.janalyse.sotohp.store.*
import zio.*
import zio.ZIO.*

class PhotoStoreServiceFake(
  statesCollectionRef: Ref[Map[PhotoId, PhotoState]],
  sourcesCollectionRef: Ref[Map[PhotoId, PhotoSource]],
  metaDataCollectionRef: Ref[Map[PhotoId, PhotoMetaData]],
  placesCollectionRef: Ref[Map[PhotoId, PhotoPlace]]
) extends PhotoStoreService {

  // ===================================================================================================================

  override def photoStateGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoState]] = for {
    collection <- statesCollectionRef.get
  } yield collection.get(photoId)

  override def photoStateUpsert(photoId: PhotoId, photoState: PhotoState): IO[PhotoStoreIssue, Unit] =
    statesCollectionRef.update(_.updated(photoId, photoState))

  override def photoStateDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    statesCollectionRef.update(collection => collection.removed(photoId))

  // ===================================================================================================================
  override def photoSourceGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoSource]] = for {
    collection <- sourcesCollectionRef.get
  } yield collection.get(photoId)

  override def photoSourceUpsert(photoId: PhotoId, photoSource: PhotoSource): IO[PhotoStoreIssue, Unit] =
    sourcesCollectionRef.update(_.updated(photoId, photoSource))

  override def photoSourceDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    sourcesCollectionRef.update(collection => collection.removed(photoId))

  // ===================================================================================================================
  override def photoMetaDataGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoMetaData]] = for {
    collection <- metaDataCollectionRef.get
  } yield collection.get(photoId)

  override def photoMetaDataUpsert(photoId: PhotoId, photoMetaData: PhotoMetaData): IO[PhotoStoreIssue, Unit] =
    metaDataCollectionRef.update(_.updated(photoId, photoMetaData))

  override def photoMetaDataDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    metaDataCollectionRef.update(collection => collection.removed(photoId))

  // ===================================================================================================================
  override def photoPlaceGet(photoId: PhotoId): IO[PhotoStoreIssue, Option[PhotoPlace]] = for {
    collection <- placesCollectionRef.get
  } yield collection.get(photoId)

  override def photoPlaceUpsert(photoId: PhotoId, place: PhotoPlace): IO[PhotoStoreIssue, Unit] =
    placesCollectionRef.update(_.updated(photoId, place))

  override def photoPlaceDelete(photoId: PhotoId): IO[PhotoStoreIssue, Unit] =
    placesCollectionRef.update(collection => collection.removed(photoId))

  // ===================================================================================================================
}

object PhotoStoreServiceFake extends PhotoStoreCollections {

  val default = ZLayer.fromZIO(
    for {
      statesCollection   <- Ref.make(Map.empty[PhotoId, PhotoState])
      sourcesCollection  <- Ref.make(Map.empty[PhotoId, PhotoSource])
      metaDataCollection <- Ref.make(Map.empty[PhotoId, PhotoMetaData])
      placesCollection <- Ref.make(Map.empty[PhotoId, PhotoPlace])
    } yield PhotoStoreServiceFake(
      statesCollection,
      sourcesCollection,
      metaDataCollection,
      placesCollection,
    )
  )

}
